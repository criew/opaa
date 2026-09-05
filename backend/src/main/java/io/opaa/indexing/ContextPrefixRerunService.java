package io.opaa.indexing;

import io.opaa.api.types.DocumentStatus;
import io.opaa.common.NotFoundException;
import io.opaa.indexing.metadata.DocumentChunkMetadata;
import io.opaa.indexing.metadata.DocumentMetadataService;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The Nachlauf that brings a library's chunks onto its current Kontextpraefix (metadata-schema.md,
 * "Nachlauf im Betrieb"). Selects by the same version pair the pipeline re-index selects by - a
 * document whose {@code context_prefix_version} is missing or below its library's - so no second
 * mechanism exists.
 *
 * <p>The processing unit is one document, and it keeps its chunks: they are re-embedded in place
 * under their own ids with the new prefix, vector row and {@code chunk_full_text} together, without
 * re-chunking and without touching the stored chunk text. Nothing is destroyed before its
 * replacement exists - a document that cannot be advanced keeps everything it had and stays
 * selected. Resumable by construction, idempotent, and only ever driven by an explicit admin call.
 */
@Service
public class ContextPrefixRerunService {

  private static final Logger log = LoggerFactory.getLogger(ContextPrefixRerunService.class);

  private final JdbcTemplate jdbcTemplate;
  private final DocumentRepository documentRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final DocumentMetadataService documentMetadataService;
  private final VectorChunkStore vectorChunkStore;
  private final ObjectMapper objectMapper;
  private final String vectorStoreTable;

  /** Skipped count of the most recent call per library; process lifetime only (ADR-0021). */
  private final Map<UUID, Integer> lastSkippedByLibrary =
      new java.util.concurrent.ConcurrentHashMap<>();

  public ContextPrefixRerunService(
      JdbcTemplate jdbcTemplate,
      DocumentRepository documentRepository,
      KnowledgeLibraryRepository libraryRepository,
      DocumentMetadataService documentMetadataService,
      VectorChunkStore vectorChunkStore,
      ObjectMapper objectMapper,
      @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
    this.jdbcTemplate = jdbcTemplate;
    this.documentRepository = documentRepository;
    this.libraryRepository = libraryRepository;
    this.documentMetadataService = documentMetadataService;
    this.vectorChunkStore = vectorChunkStore;
    this.objectMapper = objectMapper;
    this.vectorStoreTable = schemaName + "." + tableName;
  }

  /**
   * Advances up to {@code batchSize} pending documents of {@code libraryId}, which must belong to
   * {@code organizationId} (a foreign library is absent, not forbidden: 404). Call repeatedly until
   * the result {@link ContextPrefixRerunResult#isEmpty() is empty}; pausing is not calling again.
   * Deliberately not {@code @Transactional}: embedding is an HTTP round trip, and one transaction
   * over a whole batch would pin a connection for its duration.
   */
  public ContextPrefixRerunResult rerunBatch(UUID organizationId, UUID libraryId, int batchSize) {
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .filter(candidate -> organizationId.equals(candidate.getOrganizationId()))
            .orElseThrow(() -> new NotFoundException("Bibliothek nicht gefunden"));
    if (batchSize <= 0) {
      return ContextPrefixRerunResult.NOTHING_TO_DO;
    }
    int targetVersion = library.getContextPrefixVersion();
    Map<Advance, Integer> counts =
        DocumentBatchLoop.run(
            batchSize,
            Advance.class,
            Advance.SKIPPED,
            (limit, offset) -> selectPendingDocuments(libraryId, targetVersion, limit, offset),
            documentId -> advance(documentId, targetVersion));
    int skipped = counts.get(Advance.SKIPPED);
    lastSkippedByLibrary.put(libraryId, skipped);
    return new ContextPrefixRerunResult(counts.get(Advance.PROCESSED), skipped);
  }

  private enum Advance {
    PROCESSED,
    SKIPPED
  }

  /**
   * One document. Every failure costs only this candidate: it is logged, counted as skipped and
   * left exactly as it was, with its old chunks still searchable.
   */
  private Advance advance(UUID documentId, int targetVersion) {
    Document document = documentRepository.findById(documentId).orElse(null);
    if (document == null) {
      return Advance.SKIPPED;
    }
    try {
      DocumentChunkMetadata chunkMetadata = documentMetadataService.chunkMetadataFor(document);
      List<StoredChunk> stored = storedChunksOf(documentId);
      // Mirrors FileProcessingService#storeChunks: a single-chunk document carries its whole text
      // and gets no title in front of it, but a prefix-effective value is not in that text.
      boolean documentWasSplit = stored.size() >= 2;
      List<org.springframework.ai.document.Document> rebuilt =
          stored.stream()
              .map(chunk -> chunk.toRebuiltDocument(chunkMetadata, documentWasSplit))
              .toList();
      if (!rebuilt.isEmpty()) {
        vectorChunkStore.addChunks(rebuilt);
      }
      // Only after the replacement exists: a failed embedding call must leave the document pending
      // rather than mark it done with its old chunks.
      documentRepository.updateContextPrefixVersion(documentId, targetVersion);
      return Advance.PROCESSED;
    } catch (RuntimeException e) {
      log.warn(
          "Skipping document {} in the context prefix rerun: re-embedding failed", documentId, e);
      return Advance.SKIPPED;
    }
  }

  /**
   * One chunk as it sits in the store: its id, its stored text and its metadata. The text is what
   * gets re-embedded behind the new prefix - the prefix is never written into it.
   */
  private record StoredChunk(String id, String text, Map<String, Object> metadata) {

    org.springframework.ai.document.Document toRebuiltDocument(
        DocumentChunkMetadata chunkMetadata, boolean documentWasSplit) {
      org.springframework.ai.document.Document document =
          new org.springframework.ai.document.Document(id, text, metadata);
      String prefix =
          documentWasSplit || !chunkMetadata.contextPrefixValues().isEmpty()
              ? ChunkContextPrefix.build(
                  titleOf(chunkMetadata),
                  chunkMetadata.contextPrefixValues(),
                  ChunkContextPrefix.structureContextFrom(
                      metadata.get(ChunkingService.LOCATION_METADATA_KEY)))
              : null;
      document.setContentFormatter(
          prefix == null
              ? (candidate, mode) -> candidate.getText()
              : (candidate, mode) -> ChunkContextPrefix.format(prefix, candidate.getText()));
      return document;
    }

    /**
     * The Kernfeld Titel, or the humanised file name the prefix used before it existed. The
     * ingest's own "this document type never gets a prefix" decision is not stored at the chunk; it
     * is approximated here by both sources being empty.
     */
    private String titleOf(DocumentChunkMetadata chunkMetadata) {
      String coreTitle = chunkMetadata.contextTitle();
      if (coreTitle != null && !coreTitle.isBlank()) {
        return coreTitle;
      }
      Object fileName = metadata.get("file_name");
      return fileName instanceof String name ? ChunkContextTitle.deriveTitle(name) : null;
    }
  }

  private List<StoredChunk> storedChunksOf(UUID documentId) {
    return jdbcTemplate.query(
        "SELECT id, content, metadata FROM "
            + vectorStoreTable
            + " WHERE metadata->>'document_id' = ? ORDER BY id",
        (rs, index) ->
            new StoredChunk(
                rs.getString("id"),
                rs.getString("content"),
                readMetadata(rs.getString("metadata"))),
        documentId.toString());
  }

  private Map<String, Object> readMetadata(String json) {
    if (json == null || json.isBlank()) {
      return new HashMap<>();
    }
    return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
  }

  /**
   * Pending documents in stable id order, so the offset scans past what this call already found
   * unadvanceable. A document without a prefix version has never been embedded under one - the
   * bestand from before #1072 - and is pending exactly like one stamped with an older version.
   */
  private List<UUID> selectPendingDocuments(
      UUID libraryId, int targetVersion, int limit, int offset) {
    return jdbcTemplate.query(
        "SELECT id FROM documents WHERE library_id = ? AND status = ? "
            + "AND (context_prefix_version IS NULL OR context_prefix_version < ?) "
            + "ORDER BY id OFFSET ? LIMIT ?",
        (rs, index) -> (UUID) rs.getObject("id"),
        libraryId,
        DocumentStatus.INDEXED.name(),
        targetVersion,
        offset,
        limit);
  }

  /**
   * The Kontextpraefix state of every library in {@code libraryIds}: one grouped query over the
   * indexed documents, compared per library against its own current prefix version. A library
   * without indexed documents is absent from the result.
   */
  public Map<UUID, ContextPrefixRerunProgress> progressForLibraries(Collection<UUID> libraryIds) {
    if (libraryIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Integer> versions = new HashMap<>();
    libraryRepository
        .findAllById(libraryIds)
        .forEach(library -> versions.put(library.getId(), library.getContextPrefixVersion()));

    Map<UUID, long[]> counters = new HashMap<>();
    List<Object> parameters = new ArrayList<>();
    parameters.add(DocumentStatus.INDEXED.name());
    parameters.addAll(libraryIds);
    jdbcTemplate.query(
        "SELECT library_id, context_prefix_version, count(*) AS chunk_documents FROM documents "
            + "WHERE status = ? AND library_id IN ("
            + libraryIds.stream().map(id -> "?").collect(Collectors.joining(", "))
            + ") GROUP BY library_id, context_prefix_version",
        rs -> {
          UUID libraryId = (UUID) rs.getObject("library_id");
          int stamped = rs.getInt("context_prefix_version");
          boolean unstamped = rs.wasNull();
          long count = rs.getLong("chunk_documents");
          long[] totals = counters.computeIfAbsent(libraryId, key -> new long[2]);
          totals[0] += count;
          if (!unstamped && stamped >= versions.getOrDefault(libraryId, 1)) {
            totals[1] += count;
          }
        },
        parameters.toArray());

    Map<UUID, ContextPrefixRerunProgress> byLibrary = new HashMap<>();
    counters.forEach(
        (libraryId, totals) ->
            byLibrary.put(
                libraryId,
                new ContextPrefixRerunProgress(
                    libraryId,
                    versions.getOrDefault(libraryId, 1),
                    totals[0],
                    totals[1],
                    totals[0] - totals[1],
                    lastSkippedByLibrary.getOrDefault(libraryId, 0))));
    return byLibrary;
  }

  /** Indexed documents of {@code libraryId} waiting for the Nachlauf - the settings-page hint. */
  public long pendingDocuments(UUID libraryId) {
    KnowledgeLibrary library = libraryRepository.findById(libraryId).orElse(null);
    if (library == null) {
      return 0;
    }
    Long pending =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM documents WHERE library_id = ? AND status = ? "
                + "AND (context_prefix_version IS NULL OR context_prefix_version < ?)",
            Long.class,
            libraryId,
            DocumentStatus.INDEXED.name(),
            library.getContextPrefixVersion());
    return pending == null ? 0 : pending;
  }
}
