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
   * (permission-filter/citation plumbing, never semantic content - see {@link #storeChunks}'s own
   * Javadoc) from {@link org.springframework.ai.document.MetadataMode#EMBED} formatting (issue
   * #773).
   *
   * <p><b>Why this matters:</b> {@link
   * org.springframework.ai.document.Document#getFormattedContent(
   * org.springframework.ai.document.MetadataMode)} is exactly what {@code
   * EmbeddingModel#getEmbeddingContent(Document)} feeds to the embedding call for every document in
   * a {@code VectorStore#add} batch (see {@code EmbeddingModel}'s own default {@code embed(List,
   * EmbeddingOptions, BatchingStrategy)}) - and {@link
   * org.springframework.ai.openai.OpenAiEmbeddingModel} defaults its {@code metadataMode} to {@code
   * EMBED}, unlike {@link org.springframework.ai.ollama.OllamaEmbeddingModel} (whose {@code
   * embed(Document)} always uses {@code getText()} outright, metadata or not - and whose {@code
   * getEmbeddingContent(Document)} was never overridden, so it inherits that same {@code getText()}
   * default from the {@code EmbeddingModel} interface). {@link Document}'s own {@code
   * DEFAULT_CONTENT_FORMATTER} excludes nothing by default, so without this override every chunk
   * indexed through the OpenAI-compatible embedding path (the only path since #762) embedded {@code
   * "chunk_index: 0\nlibrary_id: <uuid>\nfile_name: ...\norganization_id: <uuid>\n document_id:
   * <uuid>\n\n<actual chunk text>"} instead of the chunk text alone - five lines of
   * random-UUID/index noise ahead of the real content. Query-time embedding
   * (VectorStore#similaritySearch -&gt; EmbeddingModel#embed(String)) never goes through {@code
   * Document}/{@code MetadataMode} at all, so queries stayed clean while indexed vectors did not -
   * an index-vs-query vector space mismatch, not a difference in what Ollama computes for identical
   * text (verified directly: a query against the corrupted vector for "Altaïr Ibn-La'Ahad" scored
   * 0.357 cosine against its own document, vs. 0.698 for the same pair embedded cleanly - see issue
   * #773 for the full diagnostic and PR #774 for the abandoned alternative of reverting to the
   * native Ollama API instead of fixing this).
   *
   * <p>Every value in {@code storeChunks}'s metadata map is one of these five keys - excluding them
   * all is equivalent to feeding {@link org.springframework.ai.document.MetadataMode#NONE} to
   * indexing specifically (queries and, if a future embedding call ever legitimately wants richer
   * metadata mixed in, other {@code Document} instances are unaffected: this formatter is scoped to
   * the chunks {@link #storeChunks} itself constructs, not applied globally).
   *
   * <p><b>{@code withTextTemplate("{content}")} matters too</b> (PR #779 review): {@link
   * DefaultContentFormatter}'s own default text template is {@code
   * "{metadata_string}\n\n{content}"} - with every metadata key excluded, {@code metadata_string}
   * is empty, but the template still leaves two leading newlines ahead of the actual chunk text.
   * Overriding the template avoids embedding {@code "\n\n" + text} instead of {@code text} itself,
   * restoring exactly the pre-#766 (native {@code OllamaEmbeddingModel}) behaviour rather than a
   * close approximation of it - and turns the exclusion list above into a true whitelist of what
   * this formatter ever embeds (nothing but the chunk text, regardless of which or how many keys a
   * future change adds to the metadata map).
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
   * The FILESYSTEM-folder-aware counterpart of {@link #processFile(Path, KnowledgeLibrary)} (#824,
   * Epic #520 Phase 4, ADR-0020) - identical otherwise, but carries the {@code
   * io.opaa.library.LibraryFolder} {@code AsyncIndexingExecutor} already materialized for {@code
   * file}'s directory under the library's {@code sourcePath}.
   *
   * @param folderId the folder {@code file} belongs to, or {@code null} for the library's root;
   *     also backfilled onto an already-{@code INDEXED} document whose content is unchanged (the
   *     {@code SKIPPED} branch below) - a document indexed before #824, or whose folder identity
   *     changed because its previous folder row was pruned and recreated, would otherwise never
   *     pick up its folder assignment until its content itself changes.
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
      // Document changed, its target library changed, or it was not successfully indexed —
      // delete old data. Deleting by document_id removes every chunk regardless of which
      // library it used to carry, so no chunk with the old library_id survives a move (#419
      // acceptance criteria).
      vectorChunkStore.deleteByDocumentId(existingDoc.getId());
      documentRepository.delete(existingDoc);
    }

    String contentType = Files.probeContentType(file);
    long fileSize = Files.size(file);

    // #119: checked after the deletion above (if this file replaces an existing document), so
    // usedBytes already excludes the content being superseded and this measures the true delta -
    // see LibraryStorageQuotaService's own Javadoc.
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
   * origin (#468). Used by both {@link UrlIndexingExecutor} ({@code HTTP_DIRECTORY}, no origin
   * entry - see the six-argument overload above) and {@link RssFeedIndexingExecutor} for an RSS
   * entry's attachments ({@code RSS_FEED}, {@code sourceEntryUrl} set to the entry's detail page
   * URL) - the same processing chain either way, only the recorded provenance differs.
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
      // Document changed, its target library changed, or it was not successfully indexed —
      // delete old data. Deleting by document_id removes every chunk regardless of which
      // library it used to carry, so no chunk with the old library_id survives a move (#419
      // acceptance criteria).
      vectorChunkStore.deleteByDocumentId(existingDoc.getId());
      documentRepository.delete(existingDoc);
    }

    String contentType = Files.probeContentType(localFile);

    // #119: see processFile's own comment on why this runs after the existing-document deletion
    // above.
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
   * Processes a single RSS feed entry's already-extracted main text (#467, ADR-0017 decision 2):
   * unlike {@link #processFile}/{@link #processUrlFile}, there is no file to open or download here
   * - {@link RssFeedIndexingExecutor} has already fetched the entry's detail page and reduced it to
   * its main content before calling this method, precisely so that {@code .html} never has to be
   * added to {@link SupportedDocumentFormats} (see the class Javadoc there). Content-based
   * deduplication/change detection otherwise mirrors {@link #processUrlFile} exactly: identity by
   * {@code entryUrl} in {@code file_path}, SHA-256 checksum comparison, and {@code
   * publishedAt}/{@code last_modified_remote} recorded for the executor's own change check on the
   * next run.
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

    // #119: see processFile's own comment on why this runs after the existing-document deletion
    // above.
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
   * PENDING} row by the REST upload endpoint (#434, {@code
   * io.opaa.library.LibraryDocumentService#uploadDocument}). Runs asynchronously on {@code
   * uploadTaskExecutor} - its own pool, separate from {@code indexingTaskExecutor} (see the third
   * paragraph below and {@link IndexingConfiguration}) - so the upload request itself returns as
   * soon as the file is stored and the row created, without blocking a request thread for the
   * duration of Tika parsing and embedding (#434, superseding the synchronous design #420
   * originally shipped with, see the git history of this method for that version).
   *
   * <p>Takes the document's id, not the entity itself: by the time this runs on a worker thread,
   * the caller's own transaction (creating the {@code PENDING} row) has long committed, and a
   * detached entity passed across threads would risk stale-write surprises on save. Re-reads the
   * row fresh instead - if it is gone (the caller deleted it before this ran), there is nothing
   * left to update and this method quietly returns.
   *
   * <p><b>Every outcome leaves a row behind, unlike the synchronous design #420 shipped with.</b> A
   * row that never leaves {@code PENDING} would look identical to one still queued behind other
   * uploads, and the frontend's polling (#434, {@code documentStore.ts}) has nothing else to key an
   * error state off of - so both "no extractable content" and any unexpected exception during
   * chunking/embedding land as {@code FAILED} with a German, user-facing {@link
   * Document#getErrorMessage()}, not a deleted row.
   *
   * <p><b>Runs on {@code uploadTaskExecutor}, a separate pool from {@code indexingTaskExecutor} (PR
   * #589 review, finding 2)</b> - so a burst of uploads can never itself exhaust the pool a
   * directory/URL/RSS indexing run depends on, or vice versa. Both executors reject a full queue
   * the same way since #501: {@code ThreadPoolTaskExecutor}'s default {@code AbortPolicy} throws
   * {@link org.springframework.core.task.TaskRejectedException} synchronously back to the caller,
   * so a full queue never leaves a row stuck mid-flight. Here that means {@code
   * LibraryDocumentService#uploadDocument}'s own thread catches it and marks the row {@code FAILED}
   * immediately - the frontend's polling (#434, {@code documentStore.ts}) has an explicit terminal
   * state to key off of instead of {@code PENDING} forever with nothing to explain why.
   *
   * <p><b>The status transition is a conditional {@code UPDATE}, not an entity save (PR #589
   * review, finding 1).</b> {@link DocumentRepository#markIndexed} / {@link
   * DocumentRepository#markFailed} affect the row only if it still exists - if {@code
   * LibraryDocumentService#deleteDocument} removed it while this method was still parsing/embedding
   * (after the {@link DocumentRepository#findById} above, before either of those runs), a plain
   * {@code save} would silently re-insert it as a zombie ({@link Document} assigns its own id and
   * carries no {@code @Version}). Zero rows updated means exactly that happened - any chunks {@link
   * #storeChunks} already wrote are removed again, and there is nothing left to mark.
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
      // cleanup, so a FAILED row never leaves orphaned chunks still returned by /api/v1/query.
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
   * {@code INDEXED} in {@link #processFile}/{@link #processUrlFile}/{@link #processRssEntry} (#632,
   * generalizing PR #589's upload-path pattern). Uses {@link
   * DocumentRepository#markIndexedFromSource} - a conditional {@code UPDATE} - instead of a plain
   * {@code documentRepository.save}, because the row can be deleted (e.g. by a concurrent {@code
   * LibraryDocumentService#deleteDocument} or a connector library delete) between this method's
   * caller creating/re-reading the row and {@link #storeChunks} finishing. A plain {@code save}
   * would not notice: {@link Document} assigns its own id and carries no {@code @Version}, so
   * Hibernate would silently re-{@code INSERT} it as a zombie row.
   *
   * @return {@link FileProcessingResult#SKIPPED} if the row was gone (its chunks, just written by
   *     {@link #storeChunks}, are removed again here and nothing is marked failed - the document
   *     was deliberately deleted, not a processing failure - {@link IndexingMetrics#recordSkipped}
   *     accounts for it the same way an unchanged-content skip is), otherwise {@link
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
   * be extracted (#632). Called before {@link #storeChunks} ever runs on this code path, so unlike
   * {@link #markConnectorIndexed} there are no chunks to clean up on a zero-rows result - the row
   * was simply deleted concurrently, and this quietly reports {@link FileProcessingResult#SKIPPED}
   * (counted via {@link IndexingMetrics#recordSkipped}) instead of the usual {@link
   * FileProcessingResult#PROCESSED}.
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
   * embedding throws instead of returning empty (#636 review, item 2). Unlike {@link
   * #markConnectorFailed}, {@link #storeChunks} may already have written chunks for {@code
   * documentId} into the vector store by the time this runs - {@code processFile}/{@code
   * processUrlFile}/{@code processRssEntry} each throw from inside the same {@code try} block
   * {@link #storeChunks} is called in, so anything after it (chunking succeeding but the final
   * {@code markConnectorIndexed} update itself throwing, for instance) can leave written chunks
   * behind a row that ends up {@code FAILED}. Deletes unconditionally, the same way {@link
   * #processUploadedFileAsync}'s own catch block does (its Javadoc has the fuller reasoning) -
   * cheaper than tracking whether {@link #storeChunks} was actually reached on this particular
   * call, and a no-op if it was not.
   *
   * <p>The chunk delete is wrapped in its own {@code try/catch} (#636 review round 2, item 1): this
   * runs from inside the outer {@code catch (Exception e)} block of {@code processFile}/{@code
   * processUrlFile}/{@code processRssEntry}, which rethrows the <em>original</em> failure once this
   * method returns. A pgvector outage on this cleanup delete must not swallow that original cause,
   * nor skip {@link DocumentRepository#markFailed} below it - a caller that never learns the row is
   * {@code FAILED} (still {@code PENDING}, no {@link IndexingMetrics#recordFailed} either) would be
   * strictly worse than the orphaned chunks this is trying to avoid.
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
   * deleted below - the only remaining trace of the move once {@code existingDoc} is gone (#419
   * acceptance criteria: a move must be observable after the fact).
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
    // (#202) filters on - carried on every chunk, not just the document row, so that search can
    // apply the filter directly in the VectorStore query without a join back to the relational
    // model (see docs/features/spaces-and-assets.md#durchsetzung-zur-abfragezeit). Both are the
    // library and organization chosen for this indexing run (#419) - see
    // DocumentIndexingService#requireEditableLibrary for where that choice is validated.
    //
    // #493 decision: Document#getSourceEntryUrl is deliberately NOT duplicated into chunk
    // metadata here. document_id already rides on every chunk and is used exactly this way in
    // QueryService#lookupSourceDocuments - resolving both indexedAt and sourceEntryUrl via a
    // single DocumentRepository lookup by that id rather than carrying either on each chunk.
    // sourceEntryUrl only needs the document_id filter axis (library_id/organization_id) for
    // permission enforcement, not for its own value, so it follows the same lookup-by-document_id
    // pattern instead of a second copy per chunk that would (a) need a re-index to backfill onto
    // chunks written before this decision and (b) could drift from the document row if either
    // copy is ever updated independently. #639 wired that lookup into QueryService#mapSources, so
    // a citation for an RSS-sourced attachment now carries sourceEntryUrl on SourceReference.

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
                  // #667: the chunk's Fundort, when ChunkingService could derive one.
                  Object location = chunk.getMetadata().get(ChunkingService.LOCATION_METADATA_KEY);
                  if (location != null) {
                    metadata.put(ChunkingService.LOCATION_METADATA_KEY, location);
                  }
                  org.springframework.ai.document.Document enrichedChunk =
                      new org.springframework.ai.document.Document(chunk.getText(), metadata);
                  // #773: keep this bookkeeping metadata (the #667 location included) out of what
                  // actually gets embedded - see CHUNK_EMBED_CONTENT_FORMATTER's own Javadoc.
                  enrichedChunk.setContentFormatter(CHUNK_EMBED_CONTENT_FORMATTER);
                  return enrichedChunk;
                })
            .toList();

    addToVectorStore(enriched);
  }

  /**
   * Embeds and persists {@code enriched} - one call to {@link VectorStore#add} at {@code
   * embeddingConcurrency == 1} (#734), reproducing the exact behaviour every caller of {@link
   * #storeChunks} had before this issue: a single Ollama/embedding-provider call (or as many as
   * {@code VectorStore}'s own default {@code TokenCountBatchingStrategy} needs) covering every
   * chunk of this one document, on the calling thread, in document order.
   *
   * <p><b>At {@code embeddingConcurrency > 1}</b>, {@code enriched} is sliced into sub-batches
   * sized by {@link #subBatchSize} - deliberately <em>not</em> {@code opaa.indexing.batchSize}
   * directly (#735 review, finding 1): with the defaults (batchSize 50), almost no real document
   * carries more chunks than that, so a fixed batchSize-sized slice would leave this whole
   * concurrent path dead code for every ordinary document - exactly the gap #735's review caught.
   * {@link #subBatchSize} instead spreads a document's chunks evenly across up to {@code
   * embeddingConcurrency} workers (capped by {@code batchSize} as the per-call upper bound the
   * property was always documented as), so any document with more than one chunk actually exercises
   * concurrency the moment {@code embeddingConcurrency > 1} - {@code batchSize} only still matters
   * for a document large enough that even chunkCount / embeddingConcurrency chunks would exceed it.
   *
   * <p>Every sub-batch is embedded and persisted via its own {@code vectorStore.add} call,
   * submitted to the shared, bounded {@code embeddingExecutor} (see {@link
   * IndexingConfiguration#embeddingTaskExecutor}) and awaited before this method returns - so from
   * every caller's perspective {@link #storeChunks} is still fully synchronous, only the embedding
   * calls themselves now overlap. A document with only a single sub-batch (fewer than two chunks,
   * or {@code embeddingConcurrency <= 1}) takes the same direct, un-pooled path - nothing is gained
   * by round-tripping through the executor for a single call, and it keeps that common case's
   * behaviour identical to before #734.
   *
   * <p><b>Chunk order and {@code chunk_index} metadata are unaffected</b> by which sub-batch a
   * chunk ends up in: every sub-batch is a contiguous slice of the already-enriched,
   * already-indexed list built in {@link #storeChunks} above, so concurrent embedding never changes
   * which {@code chunk_index} a chunk's text carries. <b>The order in which sub-batches themselves
   * reach the vector store is a different matter and does matter</b> (#735 review, finding 3,
   * correcting an earlier, wrong claim here that nothing downstream depends on insertion order):
   * pgvector's HNSW index build is itself insertion-order-sensitive, which is exactly why {@code
   * RetrievalEvaluationHarnessTest}/{@code CityLandmarksRetrievalEvaluationHarnessTest} pin {@code
   * embedding-concurrency} to {@code 1} via a {@code @DynamicPropertySource} override (see that
   * override's own Javadoc) - a baseline whose HNSW graph depends on non-deterministic sub-batch
   * completion order would never reproduce the same retrieval metrics twice. Production ranking
   * itself does not depend on insertion order (similarity search, not insertion-order iteration),
   * but the *baseline comparison* this evaluation harness performs does, at the HNSW-graph-shape
   * level - so the harness cannot use concurrency and the CI runtime this issue was originally
   * reported against remains, deliberately, unaffected by this change (see the PR description's
   * "Zuschnitt" section).
   *
   * <p><b>Failure propagation</b> mirrors the single-call path: {@link CompletableFuture#allOf} on
   * every sub-batch's future, unwrapped from {@link CompletionException} to the same {@link
   * RuntimeException} {@code vectorStore.add} itself would have thrown, so every existing catch
   * block in {@code processFile}/{@code processUrlFile}/{@code processRssEntry}/{@code
   * processUploadedFileAsync} - all of which already assume {@code storeChunks} may throw and clean
   * up written chunks by {@code document_id} - needs no change. An {@link Error} (not a {@link
   * RuntimeException}) from a sub-batch is rethrown as-is rather than wrapped, matching what a
   * direct {@code vectorStore.add} call would have let propagate unwrapped. {@link
   * CompletableFuture#join} (not {@code get}) is used deliberately: {@code join} throws unchecked,
   * matching {@code vectorStore.add}'s own unchecked-only contract, but does not clear this
   * thread's interrupt status on cancellation the way {@code get} would surface via {@link
   * InterruptedException} - not a concern here in practice (nothing external ever interrupts an
   * indexing worker thread mid-{@code storeChunks}), but worth naming rather than leaving implicit.
   * A failing sub-batch does not cancel sibling sub-batches already in flight; whatever they
   * already wrote is cleaned up the same way a partially-written single {@code vectorStore.add}
   * call already could leave chunks behind before #734 (see e.g. {@link
   * #markConnectorFailedAfterException}).
   *
   * <p><b>Fairness (#735 review, nit 5).</b> {@code embeddingTaskExecutor} is one pool shared by
   * every document currently splitting its chunks across sub-batches, process-wide. A document with
   * many sub-batches can occupy every pool thread for the duration of its own embedding calls,
   * head-of-line-blocking a smaller document's sub-batches queued behind it - there is no
   * per-document fairness or priority scheme. Acceptable for the moderate concurrency levels this
   * property targets (1-32, see its own Javadoc) - a starved document still completes once the pool
   * drains, it is only delayed, never starved indefinitely (the queue is FIFO, not
   * priority-inverted).
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
   * chunks into (#735 review, finding 1): {@code chunkCount} spread as evenly as possible across up
   * to {@code embeddingConcurrency} workers, capped at {@code opaa.indexing.batchSize} as the upper
   * bound on chunks per {@code vectorStore.add}/embedding call the property was always documented
   * as. Deliberately decoupled from using {@code batchSize} directly as the slice size - see {@link
   * #addToVectorStore}'s own Javadoc for why that would have left the concurrent path dead for
   * ordinary documents under the defaults.
   *
   * <p>{@code Math.max(1, ...)} guards the degenerate {@code chunkCount == 0} case (never actually
   * reached - {@link #storeChunks}'s only caller already returns before this when parsing produced
   * no content - but division by a positive {@code embeddingConcurrency} of a count of 0 would
   * otherwise yield a sub-batch size of 0, an infinite loop in {@link #addToVectorStore}'s slicing
   * loop).
   */
  private int subBatchSize(int chunkCount) {
    int perWorker = (int) Math.ceil((double) chunkCount / embeddingConcurrency);
    return Math.max(1, Math.min(embeddingBatchSize, perWorker));
  }
}
