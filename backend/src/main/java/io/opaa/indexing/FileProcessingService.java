package io.opaa.indexing;

import io.opaa.library.KnowledgeLibrary;
import io.opaa.observability.IndexingMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.scheduling.annotation.Async;

public class FileProcessingService {

  private static final Logger log = LoggerFactory.getLogger(FileProcessingService.class);

  private final DocumentService documentService;
  private final ChunkingService chunkingService;
  private final DocumentRepository documentRepository;
  private final VectorStore vectorStore;
  private final ChecksumService checksumService;
  private final IndexingMetrics metrics;

  public FileProcessingService(
      DocumentService documentService,
      ChunkingService chunkingService,
      DocumentRepository documentRepository,
      VectorStore vectorStore,
      ChecksumService checksumService,
      IndexingMetrics metrics) {
    this.documentService = documentService;
    this.chunkingService = chunkingService;
    this.documentRepository = documentRepository;
    this.vectorStore = vectorStore;
    this.checksumService = checksumService;
    this.metrics = metrics;
  }

  public FileProcessingResult processFile(Path file, KnowledgeLibrary targetLibrary)
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
        log.info("Skipping unchanged document: {}", fileName);
        metrics.recordSkipped();
        return FileProcessingResult.SKIPPED;
      }
      logLibraryChange(existingDoc, targetLibrary);
      // Document changed, its target library changed, or it was not successfully indexed —
      // delete old data. Deleting by document_id removes every chunk regardless of which
      // library it used to carry, so no chunk with the old library_id survives a move (#419
      // acceptance criteria).
      vectorStore.delete("document_id == '" + existingDoc.getId().toString() + "'");
      documentRepository.delete(existingDoc);
    }

    String contentType = Files.probeContentType(file);
    long fileSize = Files.size(file);

    var doc = new Document(fileName, filePath, contentType, fileSize);
    doc.setLibraryId(targetLibrary.getId());
    doc.setOrganizationId(targetLibrary.getOrganizationId());
    doc = documentRepository.save(doc);

    try {
      // Parse document using Tika
      List<org.springframework.ai.document.Document> parsed = documentService.parseDocument(file);
      if (parsed.isEmpty()) {
        log.warn("No content extracted from: {}", file);
        doc.setStatus(DocumentStatus.FAILED);
        documentRepository.save(doc);
        return FileProcessingResult.PROCESSED;
      }

      // Chunk the parsed content
      List<org.springframework.ai.document.Document> chunks =
          chunkingService.chunkDocuments(fileName, parsed);
      log.debug("File {} produced {} chunks", fileName, chunks.size());

      // Enrich chunks with metadata and store via VectorStore
      storeChunks(doc, chunks);

      doc.setChunkCount(chunks.size());
      doc.setIndexedAt(Instant.now());
      doc.setChecksum(checksum);
      doc.setStatus(DocumentStatus.INDEXED);
      documentRepository.save(doc);
    } catch (Exception e) {
      doc.setStatus(DocumentStatus.FAILED);
      documentRepository.save(doc);
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
      vectorStore.delete("document_id == '" + existingDoc.getId().toString() + "'");
      documentRepository.delete(existingDoc);
    }

    String contentType = Files.probeContentType(localFile);

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
        doc.setStatus(DocumentStatus.FAILED);
        documentRepository.save(doc);
        return FileProcessingResult.PROCESSED;
      }

      List<org.springframework.ai.document.Document> chunks =
          chunkingService.chunkDocuments(fileName, parsed);
      log.debug("URL file {} produced {} chunks", fileName, chunks.size());

      storeChunks(doc, chunks);

      doc.setChunkCount(chunks.size());
      doc.setIndexedAt(Instant.now());
      doc.setChecksum(checksum);
      doc.setLastModifiedRemote(lastModified);
      doc.setStatus(DocumentStatus.INDEXED);
      documentRepository.save(doc);
    } catch (Exception e) {
      doc.setStatus(DocumentStatus.FAILED);
      documentRepository.save(doc);
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
      vectorStore.delete("document_id == '" + existingDoc.getId().toString() + "'");
      documentRepository.delete(existingDoc);
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

      doc.setChunkCount(chunks.size());
      doc.setIndexedAt(Instant.now());
      doc.setChecksum(checksum);
      doc.setLastModifiedRemote(publishedAt);
      doc.setStatus(DocumentStatus.INDEXED);
      documentRepository.save(doc);
    } catch (Exception e) {
      doc.setStatus(DocumentStatus.FAILED);
      documentRepository.save(doc);
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
   * indexingTaskExecutor} - the same pool the directory/URL indexing executors use ({@link
   * IndexingConfiguration}) - so the upload request itself returns as soon as the file is stored
   * and the row created, without blocking a request thread for the duration of Tika parsing and
   * embedding (#434, superseding the synchronous design #420 originally shipped with, see the git
   * history of this method for that version).
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
   * #589 review, finding 2).</b> {@code indexingTaskExecutor} discards a task outright when its
   * queue is full ({@code ThreadPoolExecutor.DiscardPolicy}) - acceptable for a directory/URL
   * indexing run, which is retried on its own schedule, but fatal here: a discarded upload task
   * would leave its row stuck at {@code PENDING} forever with nothing to explain why, and the
   * frontend would poll it indefinitely. {@code uploadTaskExecutor} keeps {@code
   * ThreadPoolTaskExecutor}'s own default ({@code AbortPolicy}) instead, so a full queue throws
   * {@link org.springframework.core.task.TaskRejectedException} synchronously in {@code
   * LibraryDocumentService#uploadDocument}'s own thread, which catches it and marks the row {@code
   * FAILED} immediately.
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
        vectorStore.delete("document_id == '" + doc.getId() + "'");
        return;
      }
      metrics.recordProcessed();
    } catch (Exception e) {
      log.error("Failed to process uploaded document {}", doc.getFileName(), e);
      // Whatever failed, storeChunks may already have written chunks for doc.getId() into the
      // vector store - deleting them here mirrors processFile/processUrlFile's own re-index
      // cleanup, so a FAILED row never leaves orphaned chunks still returned by /api/v1/query.
      vectorStore.delete("document_id == '" + doc.getId() + "'");
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
    // QueryService#lookupIndexedAt - resolving indexedAt via a DocumentRepository lookup by that
    // id rather than carrying it on each chunk. sourceEntryUrl only needs the document_id filter
    // axis (library_id/organization_id) for permission enforcement, not for its own value, so it
    // is meant to follow the same lookup-by-document_id pattern instead of a second copy per
    // chunk that would (a) need a re-index to backfill onto chunks written before this decision
    // and (b) could drift from the document row if either copy is ever updated independently.
    // That lookup is not implemented yet - QueryService#mapSources does not populate a
    // sourceEntryUrl on SourceReference, so a citation still cannot point back to the feed entry
    // an RSS attachment came from. Tracked as its own follow-up: #639.

    List<org.springframework.ai.document.Document> enriched =
        chunks.stream()
            .map(
                chunk -> {
                  int index = chunks.indexOf(chunk);
                  return new org.springframework.ai.document.Document(
                      chunk.getText(),
                      Map.of(
                          "document_id", document.getId().toString(),
                          "chunk_index", index,
                          "file_name", document.getFileName(),
                          "library_id", document.getLibraryId().toString(),
                          "organization_id", document.getOrganizationId().toString()));
                })
            .toList();

    vectorStore.add(enriched);
  }
}
