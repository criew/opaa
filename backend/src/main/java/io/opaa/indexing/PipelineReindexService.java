package io.opaa.indexing;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.pipeline.ChunkPipelineMetadata;
import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineRegistry;
import io.opaa.indexing.source.attachment.AttachmentAccess;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The selective re-index by pipeline version (ingestion-pipelines.md, Querschnittsregel (d)): every
 * chunk below version N of one pipeline, triggerable, resumable, with progress queryable per
 * library. Also the only repair path for a chunk whose {@code chunk_full_text} row is missing or
 * older than {@link FullTextChunkStore#CURRENT_TSV_VERSION}, at the price of re-embedding.
 *
 * <p>Resumable by construction - the remaining work is re-derived from the chunk metadata - and
 * every call terminates and makes progress: a candidate that cannot be advanced stays in the set
 * and is scanned past by {@link DocumentBatchLoop}'s offset rather than hidden by a write, and is
 * reported as skipped. Only an explicit admin call ever drives this, never a background tick.
 */
public class PipelineReindexService {

  private static final Logger log = LoggerFactory.getLogger(PipelineReindexService.class);

  private final JdbcTemplate jdbcTemplate;
  private final DocumentPipelineRegistry pipelineRegistry;
  private final DocumentRepository documentRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final FileProcessingService fileProcessingService;
  private final VectorChunkStore vectorChunkStore;
  private final StoredDocumentSourceAccess sourceAccess;
  private final String vectorStoreTable;

  public PipelineReindexService(
      JdbcTemplate jdbcTemplate,
      DocumentPipelineRegistry pipelineRegistry,
      DocumentRepository documentRepository,
      KnowledgeLibraryRepository libraryRepository,
      FileProcessingService fileProcessingService,
      VectorChunkStore vectorChunkStore,
      StoredDocumentSourceAccess sourceAccess,
      @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
    this.jdbcTemplate = jdbcTemplate;
    this.pipelineRegistry = pipelineRegistry;
    this.documentRepository = documentRepository;
    this.libraryRepository = libraryRepository;
    this.fileProcessingService = fileProcessingService;
    this.vectorChunkStore = vectorChunkStore;
    this.sourceAccess = sourceAccess;
    this.vectorStoreTable = schemaName + "." + tableName;
  }

  /**
   * The pipeline-version fill state of every library of {@code organizationId} that has at least
   * one chunk, from a single grouped query over the chunk metadata rather than one query per
   * library. Reads {@code vector_store} with a {@code metadata->>...} predicate that no expression
   * index backs - an accepted cost at today's data volumes.
   */
  public List<PipelineVersionProgress> progressForOrganization(UUID organizationId) {
    Map<String, Short> currentVersions = currentVersionsById();
    String sql =
        "SELECT (v.metadata->>'library_id')::uuid AS library_id, "
            + "       COALESCE(v.metadata->>'"
            + ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY
            + "', ?) AS pipeline_id, "
            + "       COALESCE((v.metadata->>'"
            + ChunkPipelineMetadata.PIPELINE_VERSION_METADATA_KEY
            + "')::int, ?) AS pipeline_version, "
            + "       d.file_name AS file_name, "
            + "       d.source_type AS source_type, "
            + "       v.metadata->>'"
            + ChunkPipelineMetadata.ROUTING_EXTENSION_METADATA_KEY
            + "' AS routing_extension, "
            + "       count(*) AS chunk_count, "
            // Chunks whose lexical index entry is missing or built under an older tsv version are
            // stale for this display too, even when their pipeline version is current.
            + "       count(*) FILTER (WHERE f.chunk_id IS NULL) AS tsv_stale_count "
            + "FROM "
            + vectorStoreTable
            + " v "
            // A text comparison, not d.id = (...)::uuid: the join is evaluated over every row this
            // query touches before the WHERE below can exclude anything, so a single chunk whose
            // document_id metadata is not a well-formed UUID (rather than merely null, which this
            // comparison also tolerates - NULL never equals a non-null d.id::text) would otherwise
            // fail the entire admin status page with "invalid input syntax for type uuid".
            + "LEFT JOIN documents d ON d.id::text = v.metadata->>'document_id' "
            + "LEFT JOIN chunk_full_text f ON f.chunk_id = v.id AND f.content_tsv_version = ? "
            + "WHERE v.metadata->>'library_id' IS NOT NULL "
            + "  AND v.metadata->>'organization_id' = ? "
            + "GROUP BY 1, 2, 3, 4, 5, 6";

    Map<UUID, long[]> byLibrary = new HashMap<>();
    jdbcTemplate.query(
        sql,
        rs -> {
          UUID libraryId = (UUID) rs.getObject("library_id");
          String pipelineId = rs.getString("pipeline_id");
          int version = rs.getInt("pipeline_version");
          String fileName = rs.getString("file_name");
          String sourceType = rs.getString("source_type");
          String routingExtension = rs.getString("routing_extension");
          long count = rs.getLong("chunk_count");
          long tsvStale = rs.getLong("tsv_stale_count");
          long[] counters = byLibrary.computeIfAbsent(libraryId, key -> new long[3]);
          counters[0] += count;
          boolean isRss = DocumentSourceType.RSS_FEED.name().equals(sourceType);
          if (routingExtension != null) {
            // Exact via pipelineIdForRoutingExtension: stale in both directions - out of the
            // fallback, out of another specialized pipeline, or out of a pipeline_id this
            // deployment does not register at all - since resolving the target needs only the
            // stored routing key. Never for an RSS entry (ADR-0017, decision 2).
            String targetPipelineId =
                pipelineRegistry.pipelineIdForRoutingExtension(routingExtension);
            if (!isRss && !pipelineId.equals(targetPipelineId)) {
              counters[2] += count;
              return;
            }
            Short currentVersion = currentVersions.get(pipelineId);
            if (currentVersion == null) {
              // Routing-current but a pipeline_id this deployment does not register - only
              // reachable for an RSS entry. Counted in the total only, so it is visible without
              // being promised.
              return;
            }
            if (version >= currentVersion) {
              counters[1] += count - tsvStale;
              counters[2] += tsvStale;
            } else {
              counters[2] += count;
            }
            return;
          }
          // Altbestand without a routing key: the fallback+file-name approximation, narrower than
          // the exact branch above - see #currentPipelineIdForFileName on why it cannot widen.
          Short currentVersion = currentVersions.get(pipelineId);
          if (currentVersion == null) {
            // A chunk naming a pipeline this deployment does not have, with no routing key to
            // resolve it exactly: neither current nor re-indexable. Counted in the total only, so
            // it is visible without being promised.
            return;
          }
          boolean routingStale =
              !isRss
                  && pipelineId.equals(pipelineRegistry.fallbackPipeline().id())
                  && fileName != null
                  && !pipelineId.equals(currentPipelineIdForFileName(fileName));
          if (!routingStale && version >= currentVersion) {
            counters[1] += count - tsvStale;
            counters[2] += tsvStale;
          } else {
            counters[2] += count;
          }
        },
        ChunkPipelineMetadata.LEGACY_PIPELINE_ID,
        ChunkPipelineMetadata.LEGACY_PIPELINE_VERSION,
        FullTextChunkStore.CURRENT_TSV_VERSION,
        organizationId.toString());

    return byLibrary.entrySet().stream()
        .map(
            entry ->
                new PipelineVersionProgress(
                    entry.getKey(), entry.getValue()[0], entry.getValue()[1], entry.getValue()[2]))
        .sorted(Comparator.comparing(PipelineVersionProgress::libraryId))
        .toList();
  }

  /**
   * Advances up to {@code batchSize} documents of {@code organizationId} that still hold chunks
   * from {@code pipelineId} below {@code belowVersion}; call repeatedly until the result is empty.
   * A source that passes {@link StoredDocumentSourceAccess#localSourceFile} is rewritten under its
   * own id, a remote one marked for its next run, anything else skipped. Deliberately not
   * {@code @Transactional}: one transaction would pin a connection for every embedding call.
   */
  public PipelineReindexResult reindexBatch(
      UUID organizationId, String pipelineId, int belowVersion, int batchSize) {
    if (batchSize <= 0) {
      return PipelineReindexResult.NOTHING_TO_DO;
    }
    // A skipped candidate is left in place on purpose - the offset in the next selection is what
    // scans past it, rather than a database write that would misrepresent why it was not advanced.
    Map<Advance, Integer> counts =
        DocumentBatchLoop.run(
            batchSize,
            Advance.class,
            Advance.SKIPPED,
            (limit, offset) ->
                selectStaleDocuments(organizationId, pipelineId, belowVersion, limit, offset),
            documentId -> advance(documentId, pipelineId));
    return new PipelineReindexResult(
        counts.get(Advance.REINDEXED),
        counts.get(Advance.MARKED_FOR_NEXT_RUN),
        counts.get(Advance.SKIPPED),
        counts.get(Advance.ORPHAN_REMOVED));
  }

  private enum Advance {
    REINDEXED,
    MARKED_FOR_NEXT_RUN,
    ORPHAN_REMOVED,
    SKIPPED
  }

  private Advance advance(UUID documentId, String pipelineId) {
    Optional<Document> found = documentRepository.findById(documentId);
    if (found.isEmpty()) {
      // Chunks outliving their document row: nothing left to re-read, so the only correct
      // treatment is removing them - they are unreachable either way.
      log.warn("Removing chunks of document {}, whose row no longer exists", documentId);
      vectorChunkStore.deleteByDocumentId(documentId);
      return Advance.ORPHAN_REMOVED;
    }
    Document document = found.get();
    if (StoredDocumentSourceAccess.isRemote(document)) {
      // Never deletes the row itself (ADR-0022, Entscheidung 3): this only clears the change
      // markers a future connector run consults, which then re-processes the entry through the
      // executor's own update-in-place path if its content actually changed - so an RSS entry's
      // attachments (parent_document_id, ADR-0022 Entscheidung 4) are never at risk from this
      // path itself.
      return sourceAccess.markRemoteChainForNextRun(document)
          ? Advance.MARKED_FOR_NEXT_RUN
          : Advance.SKIPPED;
    }
    // Read before the rewrite: it decides how a rewritten-but-still-fallback-labeled document is
    // counted below - a document selected for its stale lexical index was genuinely repaired.
    boolean hadFullTextGap = !fullTextRowsCurrent(documentId);
    boolean advanced;
    if (document.getParentDocumentId() != null) {
      // Re-runs the current pipeline over an attachment re-extracted from its root ancestor, so a
      // raised sub-pipeline version (e.g. PDF) reaches an attachment inside a Mail without waiting
      // for the Mail file itself to change.
      advanced = sourceAccess.withReextractedAttachment(document, file -> reindex(document, file));
    } else {
      Path localFile = sourceAccess.localSourceFile(document);
      if (localFile == null) {
        log.info(
            "Skipping document {} in the pipeline re-index: its file is not readable within the"
                + " directories this deployment is configured to read",
            documentId);
        return Advance.SKIPPED;
      }
      advanced = reindex(document, localFile);
    }
    if (!advanced) {
      return Advance.SKIPPED;
    }
    // Loop protection for the file-name-approximation branch: such a candidate was picked purely
    // on its file name, which content-based routing does not have to agree with. If the
    // just-written chunks still name the fallback pipeline, re-selecting this document for the
    // same pipelineId would never converge - counted as skipped so the offset scans past it. Not
    // needed for the exact routing-key branches: DocumentPipelineRegistry maps each extension to at
    // most one pipeline, so a freshly written key never satisfies the selecting predicate again.
    // Not applied to a document selected for its stale or missing lexical index either: that
    // document was genuinely repaired, and reporting it as skipped would understate the call.
    if (!hadFullTextGap && stillFallbackLabeledAfterReindex(documentId, pipelineId)) {
      return Advance.SKIPPED;
    }
    return Advance.REINDEXED;
  }

  /** Whether every chunk of {@code documentId} carries its {@code chunk_full_text} row today. */
  private boolean fullTextRowsCurrent(UUID documentId) {
    Long missing =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM "
                + vectorStoreTable
                + " v WHERE v.metadata->>'document_id' = ? AND NOT EXISTS ("
                + "  SELECT 1 FROM chunk_full_text f "
                + "  WHERE f.chunk_id = v.id AND f.content_tsv_version = ?)",
            Long.class,
            documentId.toString(),
            FullTextChunkStore.CURRENT_TSV_VERSION);
    return missing != null && missing == 0;
  }

  /**
   * Re-runs the current pipeline over {@code document}'s file {@code file} and replaces its chunks
   * under the same row id, so citations survive. A document that cannot be re-chunked keeps its
   * chunks and its {@code INDEXED} row (ingestion-pipelines.md, "Übergabepunkt") - that is {@link
   * DocumentIngest.Builder#reindex}'s contract, and a failure is reported as "not re-indexed".
   *
   * @return whether the document was actually re-indexed
   */
  private boolean reindex(Document document, Path file) {
    KnowledgeLibrary library =
        document.getLibraryId() == null
            ? null
            : libraryRepository.findById(document.getLibraryId()).orElse(null);
    if (library == null) {
      log.warn(
          "Skipping document {} in the pipeline re-index: its library is gone", document.getId());
      return false;
    }
    try {
      return fileProcessingService.ingest(
              DocumentIngest.builder(library)
                  .file(file)
                  .filePath(document.getFilePath())
                  .fileName(document.getFileName())
                  .sourceType(document.getSourceType())
                  .changeMarker(document.getLastModifiedRemote())
                  .reindex()
                  .build(),
              attachmentAccessFor(document, library))
          == FileProcessingResult.PROCESSED;
    } catch (Exception e) {
      log.error("Failed to re-index document {}", document.getFileName(), e);
      return false;
    }
  }

  /**
   * The {@link AttachmentAccess} a re-index hands to {@link FileProcessingService#ingest} so
   * attachments a re-run pipeline discovers reach the generalized attachment path - FILESYSTEM and
   * UPLOAD, the two source types whose files this machine can re-read. There is no job here, so
   * events are only logged and no progress counted.
   */
  private static AttachmentAccess attachmentAccessFor(Document document, KnowledgeLibrary library) {
    if (document.getSourceType() != DocumentSourceType.FILESYSTEM
        && document.getSourceType() != DocumentSourceType.UPLOAD) {
      return null;
    }
    return new StandaloneAttachmentAccess(library, "Pipeline re-index");
  }

  /**
   * Whether {@code documentId}'s chunks, just rewritten for a re-index requested against {@code
   * pipelineId}, still name the fallback pipeline - see {@link #advance} for why that means the
   * heuristic branch of {@link #selectStaleDocuments} would select it again unchanged.
   */
  private boolean stillFallbackLabeledAfterReindex(UUID documentId, String pipelineId) {
    String fallbackId = pipelineRegistry.fallbackPipeline().id();
    if (pipelineId.equals(fallbackId)) {
      // The fallback-target branch of #misroutedPredicateFor is exact, not a guess: a re-index
      // writes pipeline_id=fallback whenever content still resolves to no claimed extension,
      // exactly the condition that selected the candidate, so it converges without this guard.
      return false;
    }
    List<String> pipelineIds =
        jdbcTemplate.queryForList(
            "SELECT DISTINCT COALESCE(metadata->>'"
                + ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY
                + "', ?) AS pipeline_id FROM "
                + vectorStoreTable
                + " WHERE metadata->>'document_id' = ?",
            String.class,
            ChunkPipelineMetadata.LEGACY_PIPELINE_ID,
            documentId.toString());
    return pipelineIds.contains(fallbackId);
  }

  private List<UUID> selectStaleDocuments(
      UUID organizationId, String pipelineId, int belowVersion, int batchSize, int offset) {
    MisroutedPredicate misrouted = misroutedPredicateFor(pipelineId);
    String sql =
        "SELECT DISTINCT v.metadata->>'document_id' AS document_id "
            + "FROM "
            + vectorStoreTable
            + " v "
            // A text comparison, not d.id = (...)::uuid: mirrors progressForOrganization's own
            // join - a chunk whose document_id metadata is not a well-formed UUID must not fail
            // this query with "invalid input syntax for type uuid", it must simply not join to any
            // document row.
            + "LEFT JOIN documents d ON d.id::text = v.metadata->>'document_id' "
            + "WHERE v.metadata->>'document_id' IS NOT NULL "
            // Excludes the same malformed metadata the join above already tolerates, so this
            // column's values are always safe to parse as UUID in Java below.
            + "  AND v.metadata->>'document_id' ~* "
            + "'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' "
            + "  AND v.metadata->>'organization_id' = ? "
            + "  AND ("
            + "       (COALESCE(v.metadata->>'"
            + ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY
            + "', ?) = ? "
            + "        AND COALESCE((v.metadata->>'"
            + ChunkPipelineMetadata.PIPELINE_VERSION_METADATA_KEY
            + "')::int, ?) < ?)"
            // The routing gap: a document whose routing key or, absent that, its file name names
            // pipelineId as claiming it today, but whose chunks still carry a different
            // pipeline_id - stale regardless of the stored pipeline's own version, since no request
            // naming that stored pipeline would select it. Excludes RSS_FEED, whose body always
            // goes to the fallback pipeline regardless of its name (ADR-0017, decision 2).
            + "       OR ("
            + misrouted.sql()
            + "            AND COALESCE(d.source_type, '') <> 'RSS_FEED')"
            // The lexical-index gap: a chunk without a chunk_full_text row at the current
            // FullTextChunkStore#CURRENT_TSV_VERSION is invisible to lexical search, and this
            // re-index is the only thing that repairs it. Deliberately independent of pipelineId
            // and belowVersion - raising CURRENT_TSV_VERSION raises no DocumentPipeline#version().
            // It converges, because the rewritten rows carry the current version.
            + "       OR NOT EXISTS ("
            + "            SELECT 1 FROM chunk_full_text f "
            + "            WHERE f.chunk_id = v.id AND f.content_tsv_version = ?)"
            + "      ) "
            // A remote document already marked for its next run (both change markers cleared, see
            // DocumentRepository#markForReindexOnNextRun) has had everything done to it that this
            // run can do; keeping it selected would make every further batch report the same
            // document as newly marked and never drain.
            + "  AND (d.id IS NULL "
            + "       OR d.source_type IN ('FILESYSTEM', 'UPLOAD') "
            + "       OR d.checksum IS NOT NULL) "
            // Stable order so the offset below actually scans past the documents this call already
            // found unadvanceable, instead of reshuffling them back into view.
            + "ORDER BY 1 "
            + "OFFSET ? LIMIT ?";
    List<Object> params = new ArrayList<>();
    params.add(organizationId.toString());
    params.add(ChunkPipelineMetadata.LEGACY_PIPELINE_ID);
    params.add(pipelineId);
    params.add(ChunkPipelineMetadata.LEGACY_PIPELINE_VERSION);
    params.add(belowVersion);
    params.addAll(misrouted.params());
    params.add(FullTextChunkStore.CURRENT_TSV_VERSION);
    params.add(offset);
    params.add(batchSize);

    List<UUID> ids = new ArrayList<>();
    jdbcTemplate.query(
        sql,
        rs -> {
          ids.add(UUID.fromString(rs.getString("document_id")));
        },
        params.toArray());
    return ids;
  }

  /** A SQL fragment over {@code d.file_name} plus the positional parameters it needs. */
  private record MisroutedPredicate(String sql, List<Object> params) {}

  /**
   * Whether a chunk belongs to {@code pipelineId} today but is not stored under it - exactly, via
   * its {@link ChunkPipelineMetadata#ROUTING_EXTENSION_METADATA_KEY}, or via the file-name
   * approximation where that key is absent. Expressed in SQL so {@link #selectStaleDocuments} can
   * filter and paginate in the database. The exact branch compares in every direction and always
   * converges; the heuristic one stays narrow, or it could disagree on every call and never do.
   */
  private MisroutedPredicate misroutedPredicateFor(String pipelineId) {
    String fallbackId = pipelineRegistry.fallbackPipeline().id();
    if (pipelineId.equals(fallbackId)) {
      return misroutedPredicateForFallback(fallbackId);
    }
    DocumentPipeline pipeline =
        pipelineRegistry.pipelines().stream()
            .filter(candidate -> candidate.id().equals(pipelineId))
            .findFirst()
            .orElse(null);
    if (pipeline == null) {
      return new MisroutedPredicate("FALSE", List.of());
    }
    Set<String> extensions = pipeline.handledFormats();
    if (extensions.isEmpty()) {
      return new MisroutedPredicate("FALSE", List.of());
    }
    // Exact branch: this pipeline's own extensions against the chunk's stored routing key, with no
    // constraint on the chunk's stored pipeline_id beyond "not already pipelineId" - `IN` over a
    // NULL left side (the key was never written) is neither true nor false in SQL, so this alone
    // already excludes a legacy chunk without falling through to the heuristic by accident.
    String exactSql =
        "v.metadata->>'"
            + ChunkPipelineMetadata.ROUTING_EXTENSION_METADATA_KEY
            + "' IN ("
            + extensions.stream().map(extension -> "?").collect(Collectors.joining(", "))
            + ") AND COALESCE(v.metadata->>'"
            + ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY
            + "', ?) <> ?";
    List<Object> exactParams = new ArrayList<>(extensions);
    exactParams.add(ChunkPipelineMetadata.LEGACY_PIPELINE_ID);
    exactParams.add(pipelineId);
    // Heuristic branch: only reached for a chunk that never had the routing key written at all,
    // and only while it is still fallback-labeled (see this method's own Javadoc for why).
    String heuristicSql =
        extensions.stream()
            .map(extension -> "LOWER(d.file_name) LIKE ?")
            .collect(Collectors.joining(" OR ", "(", ")"));
    List<Object> heuristicParams =
        extensions.stream()
            .map(extension -> "%" + extension.toLowerCase(Locale.ROOT))
            .map(Object.class::cast)
            .toList();
    String sql =
        "(("
            + exactSql
            + ") OR (v.metadata->>'"
            + ChunkPipelineMetadata.ROUTING_EXTENSION_METADATA_KEY
            + "' IS NULL AND "
            + heuristicSql
            + " AND COALESCE(v.metadata->>'"
            + ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY
            + "', ?) = ?))";
    List<Object> params = new ArrayList<>(exactParams);
    params.addAll(heuristicParams);
    params.add(ChunkPipelineMetadata.LEGACY_PIPELINE_ID);
    params.add(fallbackId);
    return new MisroutedPredicate(sql, params);
  }

  /**
   * The fallback-target counterpart of {@link #misroutedPredicateFor}'s exact branch: a chunk whose
   * routing key names an extension no registered pipeline claims today, but whose stored {@code
   * pipeline_id} still names something else - typically a since-deinstalled specialized pipeline.
   * No heuristic counterpart: without the routing key there is no way to tell "no pipeline claims
   * this extension" from "the file-name approximation does not recognize it".
   */
  private MisroutedPredicate misroutedPredicateForFallback(String fallbackId) {
    Set<String> claimedExtensions =
        pipelineRegistry.pipelines().stream()
            .filter(candidate -> candidate != pipelineRegistry.fallbackPipeline())
            .flatMap(candidate -> candidate.handledFormats().stream())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    StringBuilder sql =
        new StringBuilder(
            "(v.metadata->>'"
                + ChunkPipelineMetadata.ROUTING_EXTENSION_METADATA_KEY
                + "' IS NOT NULL");
    List<Object> params = new ArrayList<>();
    if (!claimedExtensions.isEmpty()) {
      sql.append(" AND v.metadata->>'")
          .append(ChunkPipelineMetadata.ROUTING_EXTENSION_METADATA_KEY)
          .append("' NOT IN (")
          .append(
              claimedExtensions.stream().map(extension -> "?").collect(Collectors.joining(", ")))
          .append(")");
      params.addAll(claimedExtensions);
    }
    sql.append(" AND COALESCE(v.metadata->>'")
        .append(ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY)
        .append("', ?) <> ?)");
    params.add(ChunkPipelineMetadata.LEGACY_PIPELINE_ID);
    params.add(fallbackId);
    return new MisroutedPredicate(sql.toString(), params);
  }

  /**
   * The id of the pipeline that would claim {@code fileName} today, purely by its extension - the
   * Java counterpart of {@link #misroutedPredicateFor}'s heuristic branch, for a chunk without a
   * routing key. A deliberately narrower approximation of routing, not a second implementation: an
   * actual re-index always re-routes on re-detected content, so every gap left open here is
   * read-only and merely leaves a chunk where it is.
   */
  private String currentPipelineIdForFileName(String fileName) {
    String lowerCased = fileName.toLowerCase(Locale.ROOT);
    for (DocumentPipeline pipeline : pipelineRegistry.pipelines()) {
      if (pipeline == pipelineRegistry.fallbackPipeline()) {
        continue;
      }
      for (String extension : pipeline.handledFormats()) {
        if (lowerCased.endsWith(extension)) {
          return pipeline.id();
        }
      }
    }
    return pipelineRegistry.fallbackPipeline().id();
  }

  private Map<String, Short> currentVersionsById() {
    Map<String, Short> versions = new HashMap<>();
    for (DocumentPipeline pipeline : pipelineRegistry.pipelines()) {
      versions.put(pipeline.id(), pipeline.version());
    }
    return versions;
  }
}
