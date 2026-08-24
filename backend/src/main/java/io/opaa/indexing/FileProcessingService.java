package io.opaa.indexing;

import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.observability.IndexingMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.ContentFormatter;
import org.springframework.ai.document.DefaultContentFormatter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.scheduling.annotation.Async;

public class FileProcessingService {

  private static final Logger log = LoggerFactory.getLogger(FileProcessingService.class);

  /**
   * Excludes every bookkeeping key {@link #storeChunks} attaches to a chunk's {@code metadata}
   * (permission-filter/citation plumbing, never semantic content) from {@link
   * org.springframework.ai.document.MetadataMode#EMBED} formatting.
   *
   * <p>{@link org.springframework.ai.document.Document#getFormattedContent(
   * org.springframework.ai.document.MetadataMode)} is what {@code
   * EmbeddingModel#getEmbeddingContent(Document)} feeds to the embedding call for every document in
   * a {@code VectorStore#add} batch, and {@link org.springframework.ai.openai.OpenAiEmbeddingModel}
   * defaults its {@code metadataMode} to {@code EMBED} (unlike Ollama's embedding model, which
   * always uses {@code getText()}). Without this override, every chunk indexed through the
   * OpenAI-compatible embedding path embeds the metadata block ahead of the actual text, which
   * corrupts the embedding vector - query-time embedding never goes through {@code Document}, so
   * queries stayed clean while indexed vectors did not.
   *
   * <p>Every value in {@code storeChunks}'s metadata map is one of these five keys - excluding them
   * all is equivalent to {@link org.springframework.ai.document.MetadataMode#NONE} for indexing
   * specifically; this formatter is scoped to the chunks {@link #storeChunks} constructs, not
   * applied globally.
   *
   * <p>{@code withTextTemplate("{content}")}: with every metadata key excluded, {@link
   * DefaultContentFormatter}'s default template ({@code "{metadata_string}\n\n{content}"}) would
   * still leave two leading newlines ahead of the chunk text.
   */
  private static final ContentFormatter CHUNK_EMBED_CONTENT_FORMATTER =
      DefaultContentFormatter.builder()
          .withExcludedEmbedMetadataKeys(
              VectorChunkStore.DOCUMENT_ID_METADATA_KEY,
              "chunk_index",
              "file_name",
              VectorChunkStore.LIBRARY_ID_METADATA_KEY,
              "organization_id",
              ChunkingService.LOCATION_METADATA_KEY)
          .withTextTemplate("{content}")
          .build();

  private final DocumentService documentService;
  private final ChunkingService chunkingService;
  private final DocumentRepository documentRepository;
  private final VectorStore vectorStore;
  private final VectorChunkStore vectorChunkStore;
  private final ChecksumService checksumService;
  private final IndexingMetrics metrics;
  private final LibraryStorageQuotaService storageQuotaService;
  private final int embeddingBatchSize;
  private final int embeddingConcurrency;
  private final Executor embeddingExecutor;

  public FileProcessingService(
      DocumentService documentService,
      ChunkingService chunkingService,
      DocumentRepository documentRepository,
      VectorStore vectorStore,
      VectorChunkStore vectorChunkStore,
      ChecksumService checksumService,
      IndexingMetrics metrics,
      LibraryStorageQuotaService storageQuotaService,
      IndexingProperties indexingProperties,
      Executor embeddingExecutor) {
    this.documentService = documentService;
    this.chunkingService = chunkingService;
    this.documentRepository = documentRepository;
    this.vectorStore = vectorStore;
    this.vectorChunkStore = vectorChunkStore;
    this.checksumService = checksumService;
    this.metrics = metrics;
    this.storageQuotaService = storageQuotaService;
    this.embeddingBatchSize = indexingProperties.batchSize();
    this.embeddingConcurrency = indexingProperties.embeddingConcurrency();
    this.embeddingExecutor = embeddingExecutor;
  }

  public FileProcessingResult processFile(Path file, KnowledgeLibrary targetLibrary)
      throws IOException {
    return processFile(file, targetLibrary, null);
  }

  /**
   * The FILESYSTEM-folder-aware counterpart of {@link #processFile(Path, KnowledgeLibrary)}
   * (ADR-0020) - identical otherwise, but carries the {@code io.opaa.library.LibraryFolder} {@code
   * AsyncIndexingExecutor} already materialized for {@code file}'s directory under the library's
   * {@code sourcePath}.
   *
   * @param folderId the folder {@code file} belongs to, or {@code null} for the library's root;
   *     also backfilled onto an already-{@code INDEXED} document whose content is unchanged (the
   *     {@code SKIPPED} branch below), so a document whose folder identity changed still picks up
   *     its folder assignment even when its content did not.
   */
  public FileProcessingResult processFile(Path file, KnowledgeLibrary targetLibrary, UUID folderId)
      throws IOException {
    String filePath = file.toAbsolutePath().toString();
    String fileName = file.getFileName().toString();

    // Compute checksum before any processing
    String checksum = checksumService.computeSha256(file);

    // Check if document already exists
    Optional<Document> existing = documentRepository.findByFilePath(filePath);
    if (existing.isPresent()) {
      Document existingDoc = existing.get();
      if (checksum.equals(existingDoc.getChecksum())
          && existingDoc.getStatus() == DocumentStatus.INDEXED
          && targetLibrary.getId().equals(existingDoc.getLibraryId())) {
        if (!Objects.equals(existingDoc.getFolderId(), folderId)) {
          existingDoc.setFolderId(folderId);
          documentRepository.save(existingDoc);
        }
        log.info("Skipping unchanged document: {}", fileName);
        metrics.recordSkipped();
        return FileProcessingResult.SKIPPED;
      }
      logLibraryChange(existingDoc, targetLibrary);
      // Document changed, its target library changed, or it was not successfully indexed - delete
      // old data. Deleting by document_id removes every chunk regardless of which library it used
      // to carry, so no chunk with the old library_id survives a move.
      vectorChunkStore.deleteByDocumentId(existingDoc.getId());
      documentRepository.delete(existingDoc);
    }

    String contentType = Files.probeContentType(file);
    long fileSize = Files.size(file);

    // Checked after the deletion above (if this file replaces an existing document), so
    // usedBytes already excludes the content being superseded and this measures the true delta.
    if (storageQuotaService.wouldExceedQuota(targetLibrary.getId(), fileSize)) {
      log.warn(
          "Skipping {}: library {} storage quota would be exceeded",
          fileName,
          targetLibrary.getId());
      metrics.recordSkipped();
      return FileProcessingResult.QUOTA_EXCEEDED;
    }

    var doc = new Document(fileName, filePath, contentType, fileSize);
    doc.setLibraryId(targetLibrary.getId());
    doc.setOrganizationId(targetLibrary.getOrganizationId());
    doc.setFolderId(folderId);
    doc = documentRepository.save(doc);

    try {
      // Parse document using Tika
      List<org.springframework.ai.document.Document> parsed = documentService.parseDocument(file);
      if (parsed.isEmpty()) {
        log.warn("No content extracted from: {}", file);
        return markConnectorFailed(doc.getId());
      }

      // Chunk the parsed content
      List<org.springframework.ai.document.Document> chunks =
          chunkingService.chunkDocuments(fileName, parsed);
      log.debug("File {} produced {} chunks", fileName, chunks.size());

      // Enrich chunks with metadata and store via VectorStore
      storeChunks(doc, chunks);

      FileProcessingResult result =
          markConnectorIndexed(doc.getId(), chunks.size(), checksum, null);
      if (result == FileProcessingResult.SKIPPED) {
        return result;
      }
    } catch (Exception e) {
      markConnectorFailedAfterException(doc.getId());
      metrics.recordFailed();
      throw e;
    }

    metrics.recordProcessed();
    return FileProcessingResult.PROCESSED;
  }

  /**
   * Processes a file downloaded from a remote URL. Uses SHA-256 checksum on the downloaded file for
   * content-based change detection and deduplication. The lastModified date from the directory
   * listing is used upstream (in UrlIndexingExecutor) to skip downloads entirely when unchanged.
   */
  public FileProcessingResult processUrlFile(
      Path localFile,
      String originalFileName,
      String remoteUrl,
      String lastModified,
      long remoteFileSize,
      KnowledgeLibrary targetLibrary)
      throws IOException {
    return processUrlFile(
        localFile,
        originalFileName,
        remoteUrl,
        lastModified,
        remoteFileSize,
        targetLibrary,
        DocumentSourceType.HTTP_DIRECTORY,
        null);
  }

  /**
   * Processes a file downloaded from a remote URL, with an explicit {@link DocumentSourceType} and
   * origin. Used by both {@link UrlIndexingExecutor} ({@code HTTP_DIRECTORY}, no origin entry - see
   * the six-argument overload above) and {@link RssFeedIndexingExecutor} for an RSS entry's
   * attachments ({@code RSS_FEED}, {@code sourceEntryUrl} set to the entry's detail page URL) - the
   * same processing chain either way, only the recorded provenance differs.
   *
   * @param sourceEntryUrl the detail page URL an attachment was found on ({@link
   *     Document#getSourceEntryUrl}), or {@code null} when this document was not found through
   *     another document (every {@code HTTP_DIRECTORY} file, and the RSS entry's own document row)
   */
  public FileProcessingResult processUrlFile(
      Path localFile,
      String originalFileName,
      String remoteUrl,
      String lastModified,
      long remoteFileSize,
      KnowledgeLibrary targetLibrary,
      DocumentSourceType sourceType,
      String sourceEntryUrl)
      throws IOException {

    String fileName = originalFileName;

    // Compute SHA-256 on the downloaded file for content-based deduplication
    String checksum = checksumService.computeSha256(localFile);

    // Check if document already exists by remote URL
    Optional<Document> existing = documentRepository.findByFilePath(remoteUrl);
    if (existing.isPresent()) {
      Document existingDoc = existing.get();
      if (checksum.equals(existingDoc.getChecksum())
          && existingDoc.getStatus() == DocumentStatus.INDEXED
          && targetLibrary.getId().equals(existingDoc.getLibraryId())) {
        log.info("Skipping unchanged URL document (same checksum): {}", fileName);
        metrics.recordSkipped();
        return FileProcessingResult.SKIPPED;
      }
      logLibraryChange(existingDoc, targetLibrary);
      // Document changed, its target library changed, or it was not successfully indexed - delete
      // old data. Deleting by document_id removes every chunk regardless of which library it used
      // to carry, so no chunk with the old library_id survives a move.
      vectorChunkStore.deleteByDocumentId(existingDoc.getId());
      documentRepository.delete(existingDoc);
    }

    String contentType = Files.probeContentType(localFile);

    // See processFile's own comment on why this runs after the existing-document deletion above.
    if (storageQuotaService.wouldExceedQuota(targetLibrary.getId(), remoteFileSize)) {
      log.warn(
          "Skipping {}: library {} storage quota would be exceeded",
          fileName,
          targetLibrary.getId());
      metrics.recordSkipped();
      return FileProcessingResult.QUOTA_EXCEEDED;
    }

    var doc = new Document(fileName, remoteUrl, contentType, remoteFileSize, sourceType);
    doc.setLibraryId(targetLibrary.getId());
    doc.setOrganizationId(targetLibrary.getOrganizationId());
    doc.setSourceEntryUrl(sourceEntryUrl);
    doc = documentRepository.save(doc);

    try {
      List<org.springframework.ai.document.Document> parsed =
          documentService.parseDocument(localFile);
      if (parsed.isEmpty()) {
        log.warn("No content extracted from URL document: {}", remoteUrl);
        return markConnectorFailed(doc.getId());
      }

      List<org.springframework.ai.document.Document> chunks =
          chunkingService.chunkDocuments(fileName, parsed);
      log.debug("URL file {} produced {} chunks", fileName, chunks.size());

      storeChunks(doc, chunks);

      FileProcessingResult result =
          markConnectorIndexed(doc.getId(), chunks.size(), checksum, lastModified);
      if (result == FileProcessingResult.SKIPPED) {
        return result;
      }
    } catch (Exception e) {
      markConnectorFailedAfterException(doc.getId());
      metrics.recordFailed();
      throw e;
    }

    metrics.recordProcessed();
    return FileProcessingResult.PROCESSED;
  }

  /**
   * Processes a single RSS feed entry's already-extracted main text (ADR-0017, decision 2): unlike
   * {@link #processFile}/{@link #processUrlFile}, there is no file to open or download here -
   * {@link RssFeedIndexingExecutor} has already fetched the entry's detail page and reduced it to
   * its main content before calling this method, so {@code .html} never has to be added to {@link
   * SupportedDocumentFormats}. Content-based deduplication/change detection otherwise mirrors
   * {@link #processUrlFile} exactly: identity by {@code entryUrl} in {@code file_path}, SHA-256
   * checksum comparison, and {@code publishedAt}/{@code last_modified_remote} recorded for the
   * executor's own change check on the next run.
   */
  public FileProcessingResult processRssEntry(
      String mainText,
      String entryTitle,
      String entryUrl,
      String publishedAt,
      KnowledgeLibrary targetLibrary) {

    String fileName = (entryTitle != null && !entryTitle.isBlank()) ? entryTitle : entryUrl;
    byte[] contentBytes = mainText.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String checksum = checksumService.computeSha256(contentBytes);

    Optional<Document> existing = documentRepository.findByFilePath(entryUrl);
    if (existing.isPresent()) {
      Document existingDoc = existing.get();
      if (checksum.equals(existingDoc.getChecksum())
          && existingDoc.getStatus() == DocumentStatus.INDEXED
          && targetLibrary.getId().equals(existingDoc.getLibraryId())) {
        log.info("Skipping unchanged RSS entry (same checksum): {}", entryUrl);
        metrics.recordSkipped();
        return FileProcessingResult.SKIPPED;
      }
      logLibraryChange(existingDoc, targetLibrary);
      vectorChunkStore.deleteByDocumentId(existingDoc.getId());
      documentRepository.delete(existingDoc);
    }

    // See processFile's own comment on why this runs after the existing-document deletion above.
    if (storageQuotaService.wouldExceedQuota(targetLibrary.getId(), contentBytes.length)) {
      log.warn(
          "Skipping RSS entry {}: library {} storage quota would be exceeded",
          entryUrl,
          targetLibrary.getId());
      metrics.recordSkipped();
      return FileProcessingResult.QUOTA_EXCEEDED;
    }

    var doc =
        new Document(
            fileName,
            entryUrl,
            "text/html",
            (long) contentBytes.length,
            DocumentSourceType.RSS_FEED);
    doc.setLibraryId(targetLibrary.getId());
    doc.setOrganizationId(targetLibrary.getOrganizationId());
    doc = documentRepository.save(doc);

    try {
      List<org.springframework.ai.document.Document> parsed =
          List.of(new org.springframework.ai.document.Document(mainText));

      List<org.springframework.ai.document.Document> chunks =
          chunkingService.chunkDocuments(fileName, parsed);
      log.debug("RSS entry {} produced {} chunks", entryUrl, chunks.size());

      storeChunks(doc, chunks);

      FileProcessingResult result =
          markConnectorIndexed(doc.getId(), chunks.size(), checksum, publishedAt);
      if (result == FileProcessingResult.SKIPPED) {
        return result;
      }
    } catch (Exception e) {
      markConnectorFailedAfterException(doc.getId());
      metrics.recordFailed();
      throw e;
    }

    metrics.recordProcessed();
    return FileProcessingResult.PROCESSED;
  }

  /**
   * Parses, chunks and embeds a document already stored on disk and already persisted as a {@code
   * PENDING} row by the REST upload endpoint ({@code
   * io.opaa.library.LibraryDocumentService#uploadDocument}). Runs asynchronously on {@code
   * uploadTaskExecutor} - its own pool, separate from {@code indexingTaskExecutor} (see {@link
   * IndexingConfiguration}), so a burst of uploads can never exhaust the pool a directory/URL/RSS
   * indexing run depends on, or vice versa - so the upload request itself returns as soon as the
   * file is stored and the row created, without blocking a request thread for Tika parsing and
   * embedding.
   *
   * <p>Takes the document's id, not the entity itself: by the time this runs on a worker thread,
   * the caller's own transaction (creating the {@code PENDING} row) has long committed, and a
   * detached entity passed across threads would risk stale-write surprises on save. Re-reads the
   * row fresh instead - if it is gone (the caller deleted it before this ran), there is nothing
   * left to update and this method quietly returns.
   *
   * <p>Every outcome leaves a row behind: "no extractable content" and any unexpected exception
   * during chunking/embedding both land as {@code FAILED} with a German, user-facing {@link
   * Document#getErrorMessage()}, so the frontend's polling always has a terminal state to key off
   * of instead of {@code PENDING} forever.
   *
   * <p>The status transition is a conditional {@code UPDATE}, not an entity save: {@link
   * DocumentRepository#markIndexed} / {@link DocumentRepository#markFailed} affect the row only if
   * it still exists - if {@code LibraryDocumentService#deleteDocument} removed it while this method
   * was still parsing/embedding, a plain {@code save} would silently re-insert it as a zombie
   * ({@link Document} assigns its own id and carries no {@code @Version}). Zero rows updated means
   * exactly that happened - any chunks {@link #storeChunks} already wrote are removed again, and
   * there is nothing left to mark.
   */
  @Async("uploadTaskExecutor")
  public void processUploadedFileAsync(UUID documentId, Path storedFile) {
    Document doc = documentRepository.findById(documentId).orElse(null);
    if (doc == null) {
      log.warn(
          "Uploaded document {} no longer exists, skipping asynchronous processing", documentId);
      return;
    }

    try {
      List<org.springframework.ai.document.Document> parsed =
          documentService.parseDocument(storedFile);
      if (parsed.isEmpty()) {
        log.warn("No content extracted from uploaded document: {}", doc.getFileName());
        markUploadFailed(doc.getId(), "Aus der Datei konnte kein Text extrahiert werden");
        metrics.recordFailed();
        return;
      }

      List<org.springframework.ai.document.Document> chunks =
          chunkingService.chunkDocuments(doc.getFileName(), parsed);
      log.debug("Uploaded file {} produced {} chunks", doc.getFileName(), chunks.size());

      storeChunks(doc, chunks);

      int updated = documentRepository.markIndexed(doc.getId(), chunks.size(), Instant.now());
      if (updated == 0) {
        log.warn(
            "Uploaded document {} was deleted while its chunks were being written; removing them"
                + " again",
            doc.getId());
        vectorChunkStore.deleteByDocumentId(doc.getId());
        return;
      }
      metrics.recordProcessed();
    } catch (Exception e) {
      log.error("Failed to process uploaded document {}", doc.getFileName(), e);
      // Whatever failed, storeChunks may already have written chunks for doc.getId() into the
      // vector store - deleting them here mirrors processFile/processUrlFile's own re-index
      // cleanup, so a FAILED row never leaves orphaned chunks still returned by search.
      vectorChunkStore.deleteByDocumentId(doc.getId());
      markUploadFailed(doc.getId(), "Die Datei konnte nicht verarbeitet werden");
      metrics.recordFailed();
    }
  }

  private void markUploadFailed(UUID documentId, String errorMessage) {
    int updated = documentRepository.markFailed(documentId, errorMessage);
    if (updated == 0) {
      log.warn("Uploaded document {} was deleted before it could be marked FAILED", documentId);
    }
  }

  /**
   * The connector counterpart to {@link #markConnectorFailed}, backing the successful transition to
   * {@code INDEXED} in {@link #processFile}/{@link #processUrlFile}/{@link #processRssEntry}. Uses
   * {@link DocumentRepository#markIndexedFromSource} - a conditional {@code UPDATE} - instead of a
   * plain save, because the row can be deleted concurrently between this method's caller
   * creating/re-reading it and {@link #storeChunks} finishing; a plain save would not notice
   * ({@link Document} assigns its own id and carries no {@code @Version}) and would silently
   * re-insert it as a zombie row.
   *
   * @return {@link FileProcessingResult#SKIPPED} if the row was gone (its chunks, just written by
   *     {@link #storeChunks}, are removed again here), otherwise {@link
   *     FileProcessingResult#PROCESSED}
   */
  private FileProcessingResult markConnectorIndexed(
      UUID documentId, int chunkCount, String checksum, String lastModifiedRemote) {
    int updated =
        documentRepository.markIndexedFromSource(
            documentId, chunkCount, Instant.now(), checksum, lastModifiedRemote);
    if (updated == 0) {
      log.warn(
          "Document {} was deleted while its chunks were being written; removing them again",
          documentId);
      vectorChunkStore.deleteByDocumentId(documentId);
      metrics.recordSkipped();
      return FileProcessingResult.SKIPPED;
    }
    return FileProcessingResult.PROCESSED;
  }

  /**
   * The connector counterpart to {@link #markUploadFailed}, backing the {@code FAILED} transition
   * in {@link #processFile}/{@link #processUrlFile}/{@link #processRssEntry} when no content could
   * be extracted. Called before {@link #storeChunks} ever runs on this code path, so unlike {@link
   * #markConnectorIndexed} there are no chunks to clean up on a zero-rows result.
   */
  private FileProcessingResult markConnectorFailed(UUID documentId) {
    int updated = documentRepository.markFailed(documentId, null);
    if (updated == 0) {
      log.warn("Document {} was deleted before it could be marked FAILED", documentId);
      metrics.recordSkipped();
      return FileProcessingResult.SKIPPED;
    }
    return FileProcessingResult.PROCESSED;
  }

  /**
   * The catch-block counterpart to {@link #markConnectorFailed}, used when parsing, chunking or
   * embedding throws instead of returning empty. Unlike {@link #markConnectorFailed}, {@link
   * #storeChunks} may already have written chunks for {@code documentId} into the vector store by
   * the time this runs, so this deletes unconditionally rather than tracking whether {@link
   * #storeChunks} was actually reached - cheap, and a no-op if it was not.
   *
   * <p>The chunk delete is wrapped in its own {@code try/catch}: this runs from inside the outer
   * catch block of {@code processFile}/{@code processUrlFile}/{@code processRssEntry}, which
   * rethrows the original failure once this method returns. A pgvector outage on this cleanup
   * delete must not swallow that original cause, nor skip {@link DocumentRepository#markFailed}
   * below it.
   */
  private void markConnectorFailedAfterException(UUID documentId) {
    try {
      vectorChunkStore.deleteByDocumentId(documentId);
    } catch (RuntimeException e) {
      log.error(
          "Failed to remove vector store chunks for document {} after a processing error -"
              + " orphaned chunks may remain",
          documentId,
          e);
    }
    int updated = documentRepository.markFailed(documentId, null);
    if (updated == 0) {
      log.warn("Document {} was deleted before it could be marked FAILED", documentId);
    }
  }

  /**
   * Logs an existing document's move to a new target library, before the old row and its chunks are
   * deleted below - the only remaining trace of the move once {@code existingDoc} is gone.
   */
  private void logLibraryChange(Document existingDoc, KnowledgeLibrary targetLibrary) {
    if (existingDoc.getLibraryId() != null
        && !existingDoc.getLibraryId().equals(targetLibrary.getId())) {
      log.info(
          "Moving document {} from library {} to library {}",
          existingDoc.getFilePath(),
          existingDoc.getLibraryId(),
          targetLibrary.getId());
    }
  }

  private void storeChunks(
      Document document, List<org.springframework.ai.document.Document> chunks) {
    // library_id and organization_id are the filter axis the permission-aware vector search
    // filters on - carried on every chunk, not just the document row, so search can apply the
    // filter directly in the VectorStore query without a join back to the relational model (see
    // docs/features/spaces-and-assets.md#durchsetzung-zur-abfragezeit).
    //
    // Document#getSourceEntryUrl is deliberately NOT duplicated into chunk metadata here:
    // document_id already rides on every chunk and QueryService#lookupSourceDocuments resolves
    // both indexedAt and sourceEntryUrl via a single lookup by that id, avoiding a second copy per
    // chunk that could drift from the document row.

    List<org.springframework.ai.document.Document> enriched =
        chunks.stream()
            .map(
                chunk -> {
                  int index = chunks.indexOf(chunk);
                  Map<String, Object> metadata = new HashMap<>();
                  metadata.put(
                      VectorChunkStore.DOCUMENT_ID_METADATA_KEY, document.getId().toString());
                  metadata.put("chunk_index", index);
                  metadata.put("file_name", document.getFileName());
                  metadata.put(
                      VectorChunkStore.LIBRARY_ID_METADATA_KEY, document.getLibraryId().toString());
                  metadata.put("organization_id", document.getOrganizationId().toString());
                  // The chunk's Fundort, when ChunkingService could derive one.
                  Object location = chunk.getMetadata().get(ChunkingService.LOCATION_METADATA_KEY);
                  if (location != null) {
                    metadata.put(ChunkingService.LOCATION_METADATA_KEY, location);
                  }
                  org.springframework.ai.document.Document enrichedChunk =
                      new org.springframework.ai.document.Document(chunk.getText(), metadata);
                  // Keep this bookkeeping metadata out of what actually gets embedded - see
                  // CHUNK_EMBED_CONTENT_FORMATTER's own Javadoc.
                  enrichedChunk.setContentFormatter(CHUNK_EMBED_CONTENT_FORMATTER);
                  return enrichedChunk;
                })
            .toList();

    addToVectorStore(enriched);
  }

  /**
   * Embeds and persists {@code enriched}. At {@code embeddingConcurrency == 1}, a single {@link
   * VectorStore#add} call covers every chunk of this one document, on the calling thread, in
   * document order - the baseline behaviour.
   *
   * <p>At {@code embeddingConcurrency > 1}, {@code enriched} is sliced into sub-batches sized by
   * {@link #subBatchSize} - deliberately not {@code opaa.indexing.batchSize} directly, which would
   * leave the concurrent path dead code for ordinary documents under the defaults. {@link
   * #subBatchSize} instead spreads a document's chunks evenly across up to {@code
   * embeddingConcurrency} workers, capped by {@code batchSize} as the per-call upper bound.
   *
   * <p>Every sub-batch is embedded and persisted via its own {@code vectorStore.add} call,
   * submitted to the shared, bounded {@code embeddingExecutor} (see {@link
   * IndexingConfiguration#embeddingTaskExecutor}) and awaited before this method returns, so {@link
   * #storeChunks} stays fully synchronous from every caller's perspective. A document with only a
   * single sub-batch takes the same direct, un-pooled path.
   *
   * <p>Chunk order and {@code chunk_index} metadata are unaffected by which sub-batch a chunk ends
   * up in - every sub-batch is a contiguous slice of the already-enriched list. The order in which
   * sub-batches themselves reach the vector store does matter, however: pgvector's HNSW index build
   * is insertion-order-sensitive, which is why the retrieval evaluation harnesses pin {@code
   * embedding-concurrency} to {@code 1} for a reproducible baseline. Production ranking itself does
   * not depend on insertion order.
   *
   * <p>Failure propagation mirrors the single-call path: {@link CompletableFuture#allOf} on every
   * sub-batch's future, unwrapped from {@link CompletionException} to the same {@link
   * RuntimeException} {@code vectorStore.add} would have thrown, so every existing catch block that
   * assumes {@code storeChunks} may throw needs no change. A failing sub-batch does not cancel
   * sibling sub-batches already in flight; whatever they wrote is cleaned up the same way a
   * partially-written single call already could (see {@link #markConnectorFailedAfterException}).
   *
   * <p>{@code embeddingTaskExecutor} is one pool shared by every document currently splitting its
   * chunks across sub-batches - a document with many sub-batches can head-of-line-block a smaller
   * document's sub-batches, with no per-document fairness scheme. Acceptable at the moderate
   * concurrency levels this property targets: a starved document still completes once the pool
   * drains (FIFO queue), it is only delayed.
   */
  private void addToVectorStore(List<org.springframework.ai.document.Document> enriched) {
    if (embeddingConcurrency <= 1) {
      vectorStore.add(enriched);
      return;
    }

    int subBatchSize = subBatchSize(enriched.size());
    List<List<org.springframework.ai.document.Document>> subBatches = new ArrayList<>();
    for (int i = 0; i < enriched.size(); i += subBatchSize) {
      subBatches.add(enriched.subList(i, Math.min(i + subBatchSize, enriched.size())));
    }
    if (subBatches.size() <= 1) {
      vectorStore.add(enriched);
      return;
    }

    List<CompletableFuture<Void>> futures =
        subBatches.stream()
            .map(
                subBatch ->
                    CompletableFuture.runAsync(() -> vectorStore.add(subBatch), embeddingExecutor))
            .toList();
    try {
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    } catch (CompletionException e) {
      switch (e.getCause()) {
        case RuntimeException runtimeException -> throw runtimeException;
        case Error error -> throw error;
        case null, default -> throw e;
      }
    }
  }

  /**
   * The size of each sub-batch {@link #addToVectorStore} splits a document's {@code chunkCount}
   * chunks into: spread as evenly as possible across up to {@code embeddingConcurrency} workers,
   * capped at {@code opaa.indexing.batchSize} as the upper bound on chunks per {@code
   * vectorStore.add} call. See {@link #addToVectorStore}'s own Javadoc for why this is decoupled
   * from using {@code batchSize} directly as the slice size.
   *
   * <p>{@code Math.max(1, ...)} guards the degenerate {@code chunkCount == 0} case - never actually
   * reached, but would otherwise yield a sub-batch size of 0, an infinite loop in {@link
   * #addToVectorStore}'s slicing loop.
   */
  private int subBatchSize(int chunkCount) {
    int perWorker = (int) Math.ceil((double) chunkCount / embeddingConcurrency);
    return Math.max(1, Math.min(embeddingBatchSize, perWorker));
  }
}
