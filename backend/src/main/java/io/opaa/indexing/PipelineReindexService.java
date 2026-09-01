package io.opaa.indexing;

import io.opaa.api.types.DocumentSourceType;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * The selective re-index by pipeline version (docs/features/ingestion-pipelines.md,
 * Querschnittsregel (d)): "alle Chunks unterhalb Version N dieser Pipeline" - auslösbar,
 * wiederaufnehmbar, und mit Fortschritt je Bibliothek abfragbar.
 *
 * <p><b>Wiederaufnehmbar by construction, not through a cursor table.</b> The remaining work is
 * always re-derived from the chunk metadata itself: a chunk that has been rewritten at the current
 * version is no longer selected, so a run that is interrupted at any point simply continues where
 * it stood on the next call - the same property {@code FullTextBackfillService} has, and for the
 * same reason.
 *
 * <p><b>Never scheduled.</b> Whether a bestand is nachgezogen at all, and when, is deliberately
 * left open in the specification ("Offen bleibt die Auslösung") - so this is only ever driven by an
 * explicit admin call ({@code IndexingAdminController}), never by a background tick that would
 * re-index a whole bestand the moment a version is raised.
 *
 * <p>Table/schema name come from the same {@code spring.ai.vectorstore.pgvector.*} properties
 * {@code PgVectorStore} itself binds, mirroring {@link FullTextBackfillService}'s pattern.
 */
public class PipelineReindexService {

  private static final Logger log = LoggerFactory.getLogger(PipelineReindexService.class);

  private final JdbcTemplate jdbcTemplate;
  private final DocumentPipelineRegistry pipelineRegistry;
  private final DocumentRepository documentRepository;
  private final FileProcessingService fileProcessingService;
  private final VectorChunkStore vectorChunkStore;
  private final String vectorStoreTable;

  public PipelineReindexService(
      JdbcTemplate jdbcTemplate,
      DocumentPipelineRegistry pipelineRegistry,
      DocumentRepository documentRepository,
      FileProcessingService fileProcessingService,
      VectorChunkStore vectorChunkStore,
      @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
    this.jdbcTemplate = jdbcTemplate;
    this.pipelineRegistry = pipelineRegistry;
    this.documentRepository = documentRepository;
    this.fileProcessingService = fileProcessingService;
    this.vectorChunkStore = vectorChunkStore;
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
        .sorted(java.util.Comparator.comparing(PipelineVersionProgress::libraryId))
        .toList();
  }

  /**
   * Re-indexes up to {@code batchSize} documents of {@code organizationId} that still hold chunks
   * from {@code pipelineId} below {@code belowVersion}. Call repeatedly until the result {@link
   * PipelineReindexResult#isEmpty() is empty}.
   *
   * <p>A document whose source file is locally readable ({@code FILESYSTEM}, {@code UPLOAD}) is
   * re-read, re-chunked and stored again <b>under its own document id</b>, so citations and deep
   * links into it survive the re-index. A document whose source is remote can only be re-read by
   * its own connector run and is marked for it instead (its checksum is cleared, which is exactly
   * what makes {@link FileProcessingService#processFile} stop treating it as unchanged); it is then
   * excluded from later batches, so the run drains rather than reselecting it forever.
   *
   * <p>Deliberately not {@code @Transactional}: one batch re-indexes several documents, each of
   * which embeds (a network round trip) and writes through {@link VectorChunkStore}'s own
   * transaction. Holding one transaction across the whole batch would keep a pooled connection open
   * for every embedding call in it, the very failure {@code VectorStoreWriter} exists to avoid. The
   * consequence is intended: an interrupted batch keeps whatever documents it already finished, and
   * the next call simply picks up the rest.
   */
  public PipelineReindexResult reindexBatch(
      UUID organizationId, String pipelineId, int belowVersion, int batchSize) {
    if (batchSize <= 0) {
      return PipelineReindexResult.NOTHING_TO_DO;
    }
    List<UUID> candidates =
        selectStaleDocuments(organizationId, pipelineId, belowVersion, batchSize);
    if (candidates.isEmpty()) {
      return PipelineReindexResult.NOTHING_TO_DO;
    }

    int reindexed = 0;
    int marked = 0;
    int orphans = 0;
    for (UUID documentId : candidates) {
      Optional<Document> found = documentRepository.findById(documentId);
      if (found.isEmpty()) {
        // Chunks outliving their document row: nothing left to re-read, so the only correct
        // treatment is removing them - they are unreachable bestand either way.
        log.warn("Removing chunks of document {}, whose row no longer exists", documentId);
        vectorChunkStore.deleteByDocumentId(documentId);
        orphans++;
        continue;
      }
      Document document = found.get();
      Path localFile = localSourceFile(document);
      if (localFile == null) {
        documentRepository.clearChecksum(documentId);
        marked++;
        continue;
      }
      fileProcessingService.reindexStoredDocument(documentId, localFile);
      reindexed++;
    }
    return new PipelineReindexResult(reindexed, marked, orphans);
  }

  /**
   * The document's own file on this machine, or {@code null} when it has none that can be read
   * again - a remote source, or a local file that has since vanished (a document whose file is gone
   * is marked for its next run rather than re-indexed from nothing).
   */
  private Path localSourceFile(Document document) {
    DocumentSourceType sourceType = document.getSourceType();
    if (sourceType != DocumentSourceType.FILESYSTEM && sourceType != DocumentSourceType.UPLOAD) {
      return null;
    }
    try {
      Path file = Path.of(document.getFilePath());
      return Files.isReadable(file) ? file : null;
    } catch (InvalidPathException e) {
      log.warn("Document {} has a file path that is not a local path", document.getId(), e);
      return null;
    }
  }

  private List<UUID> selectStaleDocuments(
      UUID organizationId, String pipelineId, int belowVersion, int batchSize) {
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
            // Already marked for its next connector run (checksum cleared): keeping it selected
            // would make every further batch return the same documents and never drain.
            + "  AND (d.id IS NULL OR d.checksum IS NOT NULL) "
            + "LIMIT ?";
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
