package io.opaa.indexing.maintenance;

import io.opaa.api.types.DocumentStatus;
import io.opaa.common.Feature;
import io.opaa.common.FeatureFlags;
import io.opaa.common.NotFoundException;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentBatchLoop;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.metadata.ChunkContextPrefix;
import io.opaa.metadata.ContextPrefixBacklog;
import io.opaa.metadata.DocumentChunkMetadata;
import io.opaa.metadata.DocumentMetadataService;
import io.opaa.observability.IndexingMetrics;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The Nachlauf that brings a library's chunks onto their current Kontextpräfix (metadata-schema.md,
 * "Nachlauf im Betrieb"). Its selection is the document-level prefix stamp: a document whose {@code
 * context_prefix_stamp} is cleared waits for the run, and it is cleared by exactly the changes that
 * alter that document's prefix - so the number the Folgekosten preview shows and the number this
 * run processes are the same set.
 *
 * <p>The processing unit is one document, and it keeps its chunks: they are re-embedded in place
 * under their own ids with the new prefix, vector row and {@code chunk_full_text} together, without
 * re-chunking and without touching the stored chunk text. Nothing is destroyed before its
 * replacement exists - a document that cannot be advanced keeps everything it had and stays
 * selected. Resumable by construction, idempotent, and only ever driven by an explicit admin call.
 */
@Service
public class ContextPrefixRerunService implements ContextPrefixBacklog {

  private static final Logger log = LoggerFactory.getLogger(ContextPrefixRerunService.class);

  private final JdbcTemplate jdbcTemplate;
  private final DocumentRepository documentRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final DocumentMetadataService documentMetadataService;
  private final VectorChunkStore vectorChunkStore;
  private final ObjectMapper objectMapper;
  private final String vectorStoreTable;
  private final FeatureFlags featureFlags;
  private final IndexingMetrics metrics;
  private final TransactionTemplate transactionTemplate;

  /** Skipped count of the most recent call per library; process lifetime only (ADR-0021). */
  private final Map<UUID, Integer> lastSkippedByLibrary = new ConcurrentHashMap<>();

  public ContextPrefixRerunService(
      JdbcTemplate jdbcTemplate,
      DocumentRepository documentRepository,
      KnowledgeLibraryRepository libraryRepository,
      DocumentMetadataService documentMetadataService,
      VectorChunkStore vectorChunkStore,
      ObjectMapper objectMapper,
      @Value("${opaa.database.schema}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName,
      FeatureFlags featureFlags,
      PlatformTransactionManager transactionManager,
      IndexingMetrics metrics) {
    this.featureFlags = featureFlags;
    this.metrics = metrics;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
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
   * over a whole batch would pin a connection for its duration. With the metadata feature off
   * (ADR-0044) nothing is re-embedded; the documents stay pending.
   */
  public ContextPrefixRerunResult rerunBatch(UUID organizationId, UUID libraryId, int batchSize) {
    if (!featureFlags.isEnabled(Feature.METADATA)) {
      return ContextPrefixRerunResult.NOTHING_TO_DO;
    }
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .filter(candidate -> organizationId.equals(candidate.getOrganizationId()))
            // Only the administration triggers this run, and it does not know a private library.
            .filter(candidate -> !candidate.isOwnerOnly())
            .orElseThrow(() -> new NotFoundException("Bibliothek nicht gefunden"));
    if (batchSize <= 0) {
      return ContextPrefixRerunResult.NOTHING_TO_DO;
    }
    Map<Advance, Integer> counts =
        DocumentBatchLoop.run(
            batchSize,
            Advance.class,
            Advance.SKIPPED,
            (limit, offset) -> selectPendingDocuments(library.getId(), limit, offset),
            this::advance);
    int skipped = counts.get(Advance.SKIPPED);
    if (skipped == 0) {
      lastSkippedByLibrary.remove(library.getId());
    } else {
      lastSkippedByLibrary.put(library.getId(), skipped);
    }
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
  private Advance advance(UUID documentId) {
    Document document = documentRepository.findById(documentId).orElse(null);
    if (document == null) {
      return Advance.SKIPPED;
    }
    try {
      reembedInPlace(document);
      return Advance.PROCESSED;
    } catch (RuntimeException e) {
      log.warn(
          "Skipping document {} in the context prefix rerun: re-embedding failed", documentId, e);
      return Advance.SKIPPED;
    }
  }

  /**
   * Re-embeds every chunk of {@code document} in place: same ids, same stored text, the input
   * rebuilt with the document's current Kontextpräfix exactly as the ingest builds it, vector row
   * and full-text row together. Records the prefix stamp afterwards, but only when every stored
   * chunk was rewritten. Throws when embedding or writing fails; the document then keeps what it
   * had. Holds no lock over the embedding: a chunk that a concurrent ingest replaced or a deletion
   * removed after it was read is not written back, and a prefix changed in the meantime is not
   * recorded, so the document stays pending.
   */
  public void reembedInPlace(Document document) {
    UUID documentId = document.getId();
    DocumentChunkMetadata chunkMetadata = documentMetadataService.chunkMetadataFor(document);
    List<StoredChunk> stored = storedChunksOf(documentId);
    // The ingest's own "does this document type get a prefix at all" decision, read back rather
    // than guessed. A document written before it was recorded counts as eligible: that is what
    // every ingest path but the headline-less RSS entry was, and that one has no title either.
    boolean eligible = !Boolean.FALSE.equals(document.getContextPrefixEligible());
    String title =
        ChunkContextPrefix.titleAtRest(
            eligible, chunkMetadata.contextTitle(), document.getContextPrefixTitle());
    boolean documentWasSplit = ChunkContextPrefix.documentWasSplit(stored.size());
    List<org.springframework.ai.document.Document> rebuilt =
        stored.stream()
            .map(chunk -> chunk.rebuild(eligible, documentWasSplit, title, chunkMetadata))
            .toList();
    VectorChunkStore.Reembedded outcome = vectorChunkStore.reembedChunks(rebuilt);
    metrics.recordEmbeddingInputsUnverified(outcome.unverified());
    if (outcome.cut() > 0) {
      metrics.recordEmbeddingInputsCut(outcome.cut());
      log.warn(
          "{} chunk(s) of document {} reached the embedding input limit of {} tokens and were"
              + " written cut short; the document is marked for the pipeline re-index, which"
              + " splits them",
          outcome.cut(),
          documentId,
          vectorChunkStore.maxInputTokens());
    }
    if (outcome.rewritten() != stored.size()) {
      // A concurrent ingest or deletion took chunks away after they were read; the document row
      // belongs to that writer now and keeps the prefix it recorded for its own chunks.
      return;
    }
    // Only after the replacement exists: a failed embedding call must leave the document pending
    // rather than mark it done with its old chunks.
    String stamp = chunkMetadata.contextPrefixStamp(title);
    transactionTemplate.executeWithoutResult(
        status ->
            recordIfPrefixUnchanged(documentId, stamp, eligible, document.getContextPrefixTitle()));
  }

  /**
   * Records the prefix the chunks were just embedded with, unless it is no longer the document's
   * prefix: the document row is locked first, so a metadata correction either committed before -
   * and its values are read here and differ - or clears the stamp after this commit. Locks only the
   * document row, never a chunk row (see {@code VectorStoreWriter#rewriteEmbeddedChunks}).
   */
  private void recordIfPrefixUnchanged(
      UUID documentId, String stamp, boolean eligible, String recordedTitle) {
    if (jdbcTemplate
        .queryForList(
            "SELECT id FROM documents WHERE id = ? FOR NO KEY UPDATE", UUID.class, documentId)
        .isEmpty()) {
      return;
    }
    Document current = documentRepository.findById(documentId).orElse(null);
    if (current == null) {
      return;
    }
    boolean currentEligible = !Boolean.FALSE.equals(current.getContextPrefixEligible());
    DocumentChunkMetadata currentMetadata = documentMetadataService.chunkMetadataFor(current);
    String currentStamp =
        currentMetadata.contextPrefixStamp(
            ChunkContextPrefix.titleAtRest(
                currentEligible, currentMetadata.contextTitle(), current.getContextPrefixTitle()));
    if (currentEligible != eligible
        || !Objects.equals(current.getContextPrefixTitle(), recordedTitle)
        || !Objects.equals(currentStamp, stamp)) {
      return;
    }
    documentRepository.recordContextPrefix(documentId, stamp, eligible, recordedTitle);
  }

  /**
   * One chunk as it sits in the store: its id, its stored text and its metadata. The text is what
   * gets re-embedded behind the new prefix - the prefix is never written into it.
   */
  private record StoredChunk(String id, String text, Map<String, Object> metadata) {

    org.springframework.ai.document.Document rebuild(
        boolean eligible,
        boolean documentWasSplit,
        String title,
        DocumentChunkMetadata chunkMetadata) {
      org.springframework.ai.document.Document document =
          new org.springframework.ai.document.Document(id, text, metadata);
      ChunkContextPrefix.applyTo(
          document, eligible, documentWasSplit, title, chunkMetadata.contextPrefixValues());
      return document;
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
   * unadvanceable. A document without a stamp either never carried one - the bestand from before
   * #1072 - or was handed back by a change to its own prefix.
   */
  private List<UUID> selectPendingDocuments(UUID libraryId, int limit, int offset) {
    return jdbcTemplate.query(
        "SELECT id FROM documents WHERE library_id = ? AND status = ? "
            + "AND context_prefix_stamp IS NULL ORDER BY id OFFSET ? LIMIT ?",
        (rs, index) -> (UUID) rs.getObject("id"),
        libraryId,
        DocumentStatus.INDEXED.name(),
        offset,
        limit);
  }

  /**
   * The Kontextpräfix state of every library in {@code libraryIds}: one grouped query over the
   * indexed documents. A library without indexed documents is absent from the result.
   */
  public Map<UUID, ContextPrefixRerunProgress> progressForLibraries(Collection<UUID> libraryIds) {
    if (libraryIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, ContextPrefixRerunProgress> byLibrary = new HashMap<>();
    List<Object> parameters = new ArrayList<>();
    parameters.add(DocumentStatus.INDEXED.name());
    parameters.addAll(libraryIds);
    jdbcTemplate.query(
        "SELECT library_id, count(*) AS total, "
            + "count(*) FILTER (WHERE context_prefix_stamp IS NULL) AS pending "
            + "FROM documents WHERE status = ? AND library_id IN ("
            + libraryIds.stream().map(id -> "?").collect(Collectors.joining(", "))
            + ") GROUP BY library_id",
        rs -> {
          UUID libraryId = (UUID) rs.getObject("library_id");
          long total = rs.getLong("total");
          long pending = rs.getLong("pending");
          byLibrary.put(
              libraryId,
              new ContextPrefixRerunProgress(
                  libraryId,
                  total,
                  total - pending,
                  pending,
                  lastSkippedByLibrary.getOrDefault(libraryId, 0)));
        },
        parameters.toArray());
    return byLibrary;
  }

  /** Indexed documents of {@code libraryId} waiting for the Nachlauf - the settings-page hint. */
  @Override
  public long pendingDocuments(UUID libraryId) {
    Long pending =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM documents WHERE library_id = ? AND status = ? "
                + "AND context_prefix_stamp IS NULL",
            Long.class,
            libraryId,
            DocumentStatus.INDEXED.name());
    return pending == null ? 0 : pending;
  }
}
