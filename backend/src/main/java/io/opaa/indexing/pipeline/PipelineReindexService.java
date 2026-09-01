package io.opaa.indexing.pipeline;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.FilesystemPathAllowlist;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.VectorStoreWriter;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.UploadProperties;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The selective re-index by pipeline version (docs/features/ingestion-pipelines.md, cross-cutting
 * rule (d)): every chunk below version N of one pipeline, triggerable, resumable, and with progress
 * queryable per library.
 *
 * <p><b>Resumable by construction, not through a cursor table.</b> The remaining work is always
 * re-derived from the chunk metadata itself: a chunk rewritten at the current version is no longer
 * selected, so a run interrupted at any point simply continues where it stood on the next call -
 * the same property {@link FullTextBackfillService} has, and for the same reason.
 *
 * <p><b>Every call terminates and every call makes progress.</b> A candidate that cannot be
 * advanced right now - its file is outside what this deployment is allowed to read, or the pipeline
 * could not parse it this time - stays in the candidate set on purpose (nothing about it is
 * falsified in the database to hide it). {@link #reindexBatch} therefore scans past such a
 * candidate using an offset rather than reselecting it forever, and reports it under {@link
 * PipelineReindexResult#skippedDocuments()}. A call that advanced nothing at all is the signal to
 * stop; the chunks left behind stay visible in {@link #progressForOrganization}, never silently
 * reported as done.
 *
 * <p><b>Never scheduled.</b> Whether a corpus is caught up at all, and when, is deliberately left
 * open in the specification - so this is only ever driven by an explicit admin call ({@code
 * IndexingAdminController}), never by a background tick that would re-index a whole corpus the
 * moment a version is raised.
 *
 * <p>Table/schema name come from the same {@code spring.ai.vectorstore.pgvector.*} properties
 * {@code PgVectorStore} itself binds, mirroring {@link FullTextBackfillService}'s pattern.
 */
public class PipelineReindexService {

  private static final Logger log = LoggerFactory.getLogger(PipelineReindexService.class);

  /**
   * How many candidates one call may scan past, relative to its own batch size, before giving up
   * for this call. Bounds the work a corpus consisting mostly of unreachable documents can cause in
   * a single request; the next call starts over and reaches further only if earlier candidates
   * became advanceable in the meantime.
   */
  private static final int MAX_SKIP_SCAN_FACTOR = 10;

  private final JdbcTemplate jdbcTemplate;
  private final DocumentPipelineRegistry pipelineRegistry;
  private final DocumentRepository documentRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final FileProcessingService fileProcessingService;
  private final VectorChunkStore vectorChunkStore;
  private final FilesystemPathAllowlist filesystemAllowlist;
  private final UploadProperties uploadProperties;
  private final String vectorStoreTable;

  public PipelineReindexService(
      JdbcTemplate jdbcTemplate,
      DocumentPipelineRegistry pipelineRegistry,
      DocumentRepository documentRepository,
      KnowledgeLibraryRepository libraryRepository,
      FileProcessingService fileProcessingService,
      VectorChunkStore vectorChunkStore,
      FilesystemPathAllowlist filesystemAllowlist,
      UploadProperties uploadProperties,
      @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
    this.jdbcTemplate = jdbcTemplate;
    this.pipelineRegistry = pipelineRegistry;
    this.documentRepository = documentRepository;
    this.libraryRepository = libraryRepository;
    this.fileProcessingService = fileProcessingService;
    this.vectorChunkStore = vectorChunkStore;
    this.filesystemAllowlist = filesystemAllowlist;
    this.uploadProperties = uploadProperties;
    this.vectorStoreTable = schemaName + "." + tableName;
  }

  /**
   * The pipeline-version fill state of every library of {@code organizationId} that has at least
   * one chunk, computed from a single grouped query over the chunk metadata rather than one query
   * per library or per pipeline.
   *
   * <p>Reads {@code vector_store} with a {@code metadata->>...} predicate that no expression index
   * backs - the same accepted cost {@code FullTextBackfillProgressService} already carries at
   * today's data volumes.
   */
  public List<PipelineVersionProgress> progressForOrganization(UUID organizationId) {
    Map<String, Short> currentVersions = currentVersionsById();
    String sql =
        "SELECT (metadata->>'library_id')::uuid AS library_id, "
            + "       COALESCE(metadata->>'"
            + ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY
            + "', ?) AS pipeline_id, "
            + "       COALESCE((metadata->>'"
            + ChunkPipelineMetadata.PIPELINE_VERSION_METADATA_KEY
            + "')::int, ?) AS pipeline_version, "
            + "       count(*) AS chunk_count "
            + "FROM "
            + vectorStoreTable
            + " WHERE metadata->>'library_id' IS NOT NULL "
            + "  AND metadata->>'organization_id' = ? "
            + "GROUP BY 1, 2, 3";

    Map<UUID, long[]> byLibrary = new HashMap<>();
    jdbcTemplate.query(
        sql,
        rs -> {
          UUID libraryId = (UUID) rs.getObject("library_id");
          String pipelineId = rs.getString("pipeline_id");
          int version = rs.getInt("pipeline_version");
          long count = rs.getLong("chunk_count");
          long[] counters = byLibrary.computeIfAbsent(libraryId, key -> new long[3]);
          counters[0] += count;
          Short currentVersion = currentVersions.get(pipelineId);
          if (currentVersion == null) {
            // A chunk naming a pipeline this deployment does not have: neither current nor
            // re-indexable. Counted in the total only, so it is visible without being promised.
            return;
          }
          if (version >= currentVersion) {
            counters[1] += count;
          } else {
            counters[2] += count;
          }
        },
        ChunkPipelineMetadata.LEGACY_PIPELINE_ID,
        ChunkPipelineMetadata.LEGACY_PIPELINE_VERSION,
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
   * from {@code pipelineId} below {@code belowVersion}. Call repeatedly until the result {@link
   * PipelineReindexResult#isEmpty() is empty}.
   *
   * <p>A document whose source file is locally readable <em>and</em> passes the same runtime
   * containment checks a download of that file would (see {@link #localSourceFile}) is re-read,
   * re-chunked and stored again <b>under its own document id</b>, so citations and deep links into
   * it survive. A document whose source is remote can only be re-read by its own connector run and
   * is marked for it instead ({@link DocumentRepository#markForReindexOnNextRun}). Anything else is
   * counted as skipped and scanned past.
   *
   * <p>Deliberately not {@code @Transactional}: one batch re-indexes several documents, each of
   * which embeds (a network round trip) and writes through {@link VectorChunkStore}'s own
   * transaction. Holding one transaction across the whole batch would keep a pooled connection open
   * for every embedding call in it, the very failure {@link VectorStoreWriter} exists to avoid. The
   * consequence is intended: an interrupted batch keeps whatever documents it already finished, and
   * the next call simply picks up the rest.
   */
  public PipelineReindexResult reindexBatch(
      UUID organizationId, String pipelineId, int belowVersion, int batchSize) {
    if (batchSize <= 0) {
      return PipelineReindexResult.NOTHING_TO_DO;
    }
    int reindexed = 0;
    int marked = 0;
    int skipped = 0;
    int orphans = 0;
    int maxSkips = batchSize * MAX_SKIP_SCAN_FACTOR;

    while (reindexed + marked + orphans < batchSize && skipped < maxSkips) {
      List<UUID> candidates =
          selectStaleDocuments(organizationId, pipelineId, belowVersion, batchSize, skipped);
      if (candidates.isEmpty()) {
        break;
      }
      boolean exhausted = true;
      for (UUID documentId : candidates) {
        if (reindexed + marked + orphans >= batchSize) {
          exhausted = false;
          break;
        }
        switch (advance(documentId)) {
          case REINDEXED -> reindexed++;
          case MARKED_FOR_NEXT_RUN -> marked++;
          case ORPHAN_REMOVED -> orphans++;
          // Left in place on purpose - the offset in the next selection is what scans past it,
          // rather than a database write that would misrepresent why it was not advanced.
          case SKIPPED -> skipped++;
        }
        if (skipped >= maxSkips) {
          exhausted = false;
          break;
        }
      }
      if (exhausted && candidates.size() < batchSize) {
        break;
      }
    }
    return new PipelineReindexResult(reindexed, marked, skipped, orphans);
  }

  private enum Advance {
    REINDEXED,
    MARKED_FOR_NEXT_RUN,
    ORPHAN_REMOVED,
    SKIPPED
  }

  private Advance advance(UUID documentId) {
    Optional<Document> found = documentRepository.findById(documentId);
    if (found.isEmpty()) {
      // Chunks outliving their document row: nothing left to re-read, so the only correct
      // treatment is removing them - they are unreachable either way.
      log.warn("Removing chunks of document {}, whose row no longer exists", documentId);
      vectorChunkStore.deleteByDocumentId(documentId);
      return Advance.ORPHAN_REMOVED;
    }
    Document document = found.get();
    DocumentSourceType sourceType = document.getSourceType();
    if (sourceType == DocumentSourceType.HTTP_DIRECTORY
        || sourceType == DocumentSourceType.RSS_FEED) {
      documentRepository.markForReindexOnNextRun(documentId);
      return Advance.MARKED_FOR_NEXT_RUN;
    }
    Path localFile = localSourceFile(document);
    if (localFile == null) {
      log.info(
          "Skipping document {} in the pipeline re-index: its file is not readable within the"
              + " directories this deployment is configured to read",
          documentId);
      return Advance.SKIPPED;
    }
    return fileProcessingService.reindexStoredDocument(documentId, localFile)
        ? Advance.REINDEXED
        : Advance.SKIPPED;
  }

  /**
   * The document's own file on this machine, or {@code null} when this deployment may not read it
   * again. Applies the same runtime containment discipline {@code
   * LibraryDocumentService#filesystemFileIfWithinConfiguredDirectory}/{@code
   * #uploadedFileIfManagedByThisService} apply before serving an original, and for the same reason
   * (ADR-0018, Entscheidung 6): {@code file_path} was validated when the document was indexed, but
   * the allowlist can be narrowed - or emptied, which disables the {@code FILESYSTEM} source type
   * entirely - afterwards, and a re-index must not be the one path that silently keeps reading from
   * a directory an operator has since withdrawn.
   *
   * <ul>
   *   <li>{@code FILESYSTEM}: the library's own {@code sourcePath} must still pass {@link
   *       FilesystemPathAllowlist}, and the file must resolve underneath it - both via {@link
   *       Path#toRealPath}, so a symlink out of the configured directory cannot pass the lexical
   *       prefix check.
   *   <li>{@code UPLOAD}: the file must lie inside this library's own subdirectory of the managed
   *       upload storage - the only files this system wrote itself.
   * </ul>
   */
  private Path localSourceFile(Document document) {
    if (document.getFilePath() == null || document.getLibraryId() == null) {
      return null;
    }
    Path candidate;
    try {
      candidate = Path.of(document.getFilePath());
    } catch (InvalidPathException e) {
      log.warn("Document {} has a file path that is not a local path", document.getId(), e);
      return null;
    }
    return switch (document.getSourceType()) {
      case FILESYSTEM -> filesystemFileWithinConfiguredDirectory(document, candidate);
      case UPLOAD -> uploadedFileWithinManagedStorage(document, candidate);
      case HTTP_DIRECTORY, RSS_FEED -> null;
    };
  }

  private Path filesystemFileWithinConfiguredDirectory(Document document, Path candidate) {
    KnowledgeLibrary library = libraryRepository.findById(document.getLibraryId()).orElse(null);
    if (library == null || library.getSourcePath() == null) {
      return null;
    }
    if (!filesystemAllowlist.isAllowed(library.getSourcePath())) {
      return null;
    }
    Path real = resolveReal(candidate);
    Path configuredDirectory = resolveReal(Path.of(library.getSourcePath()));
    if (real == null || configuredDirectory == null) {
      return null;
    }
    return real.startsWith(configuredDirectory) ? real : null;
  }

  private Path uploadedFileWithinManagedStorage(Document document, Path candidate) {
    Path libraryUploadDirectory =
        Paths.get(uploadProperties.storagePath())
            .resolve(document.getLibraryId().toString())
            .toAbsolutePath()
            .normalize();
    Path real = resolveReal(candidate);
    Path managedDirectory = resolveReal(libraryUploadDirectory);
    if (real == null || managedDirectory == null) {
      return null;
    }
    return real.startsWith(managedDirectory) ? real : null;
  }

  /**
   * {@link Path#toRealPath()}, or {@code null} if the path does not (or no longer) exist - a file
   * that has since disappeared is skipped, not an error.
   */
  private Path resolveReal(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return null;
    }
  }

  private List<UUID> selectStaleDocuments(
      UUID organizationId, String pipelineId, int belowVersion, int batchSize, int offset) {
    String sql =
        "SELECT DISTINCT (v.metadata->>'document_id')::uuid AS document_id "
            + "FROM "
            + vectorStoreTable
            + " v "
            + "LEFT JOIN documents d ON d.id = (v.metadata->>'document_id')::uuid "
            + "WHERE v.metadata->>'document_id' IS NOT NULL "
            + "  AND v.metadata->>'organization_id' = ? "
            + "  AND COALESCE(v.metadata->>'"
            + ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY
            + "', ?) = ? "
            + "  AND COALESCE((v.metadata->>'"
            + ChunkPipelineMetadata.PIPELINE_VERSION_METADATA_KEY
            + "')::int, ?) < ? "
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
    List<UUID> ids = new ArrayList<>();
    jdbcTemplate.query(
        sql,
        rs -> {
          ids.add((UUID) rs.getObject("document_id"));
        },
        organizationId.toString(),
        ChunkPipelineMetadata.LEGACY_PIPELINE_ID,
        pipelineId,
        ChunkPipelineMetadata.LEGACY_PIPELINE_VERSION,
        belowVersion,
        offset,
        batchSize);
    return ids;
  }

  private Map<String, Short> currentVersionsById() {
    Map<String, Short> versions = new HashMap<>();
    for (DocumentPipeline pipeline : pipelineRegistry.pipelines()) {
      versions.put(pipeline.id(), pipeline.version());
    }
    return versions;
  }
}
