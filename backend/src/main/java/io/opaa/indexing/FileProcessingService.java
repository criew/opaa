package io.opaa.indexing;

import io.opaa.library.KnowledgeLibrary;
import io.opaa.observability.IndexingMetrics;
import io.opaa.organization.Organization;
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

  public FileProcessingResult processFile(Path file) throws IOException {
    String filePath = file.toAbsolutePath().toString();
    String fileName = file.getFileName().toString();

    // Compute checksum before any processing
    String checksum = checksumService.computeSha256(file);

    // Check if document already exists
    Optional<Document> existing = documentRepository.findByFilePath(filePath);
    if (existing.isPresent()) {
      Document existingDoc = existing.get();
      if (checksum.equals(existingDoc.getChecksum())
          && existingDoc.getStatus() == DocumentStatus.INDEXED) {
        log.info("Skipping unchanged document: {}", fileName);
        metrics.recordSkipped();
        return FileProcessingResult.SKIPPED;
      }
      // Document changed or was not successfully indexed — delete old data
      vectorStore.delete("document_id == '" + existingDoc.getId().toString() + "'");
      documentRepository.delete(existingDoc);
    }

    String contentType = Files.probeContentType(file);
    long fileSize = Files.size(file);

    var doc = new Document(fileName, filePath, contentType, fileSize);
    doc.setLibraryId(KnowledgeLibrary.SYSTEM_LIBRARY_ID);
    doc.setOrganizationId(Organization.DEFAULT_ID);
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
      long remoteFileSize)
      throws IOException {

    String fileName = originalFileName;

    // Compute SHA-256 on the downloaded file for content-based deduplication
    String checksum = checksumService.computeSha256(localFile);

    // Check if document already exists by remote URL
    Optional<Document> existing = documentRepository.findByFilePath(remoteUrl);
    if (existing.isPresent()) {
      Document existingDoc = existing.get();
      if (checksum.equals(existingDoc.getChecksum())
          && existingDoc.getStatus() == DocumentStatus.INDEXED) {
        log.info("Skipping unchanged URL document (same checksum): {}", fileName);
        metrics.recordSkipped();
        return FileProcessingResult.SKIPPED;
      }
      // Document changed — delete old data
      vectorStore.delete("document_id == '" + existingDoc.getId().toString() + "'");
      documentRepository.delete(existingDoc);
    }

    String contentType = Files.probeContentType(localFile);

    var doc =
        new Document(
            fileName, remoteUrl, contentType, remoteFileSize, DocumentSourceType.HTTP_DIRECTORY);
    doc.setLibraryId(KnowledgeLibrary.SYSTEM_LIBRARY_ID);
    doc.setOrganizationId(Organization.DEFAULT_ID);
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
   * Processes a file uploaded through the REST upload endpoint (#420, {@code
   * io.opaa.library.LibraryDocumentService}). Unlike {@link #processFile} and {@link
   * #processUrlFile}, the caller has already decided the target library and organization and
   * already checked - via {@link ChecksumService#computeSha256} and {@code
   * DocumentRepository#findByLibraryIdAndChecksum} - that no document with this content exists in
   * that library yet, so this method does not repeat the existing-document lookup or dedup those
   * two do (dedup here is scoped per library, not per file path, which is why it lives one layer up
   * rather than here). {@code checksum} is passed in rather than recomputed for the same reason:
   * the caller already hashed the file to make that decision, and hashing a large upload twice
   * would be wasted work.
   *
   * <p>Returns the persisted {@link Document} itself, not a {@link FileProcessingResult} - unlike
   * {@link #processFile}/{@link #processUrlFile}, whose callers (the async job executors) only need
   * the processed/skipped/failed distinction for job counters, the upload endpoint's caller needs
   * the row itself to build its {@code 201} response.
   *
   * <p><b>No document row survives a failed upload (#420 code review, nit 6).</b> Unlike {@link
   * #processFile}/{@link #processUrlFile}, which persist a row up front and mark it {@code FAILED}
   * on any problem - appropriate for their job-based reporting model, where a batch run's summary
   * has a place for "processed but failed" - this method parses the file <em>before</em> creating
   * any row at all, and deletes the row again if chunking/embedding fails afterwards. An
   * interactively uploaded document has no such use for a listed {@code FAILED} row pointing at a
   * file the caller is about to delete: the caller gets a thrown exception instead, translates it
   * into the appropriate {@code 4xx}, and there is nothing left over to clean up later.
   *
   * @throws EmptyDocumentContentException if Tika extracts no text at all - deliberately thrown
   *     rather than returning a {@code FAILED} row, since there is nothing indexed and nothing to
   *     list.
   */
  public Document processUploadedFile(
      Path storedFile,
      String fileName,
      String checksum,
      UUID libraryId,
      UUID organizationId,
      UUID uploadedByUserId)
      throws IOException {
    List<org.springframework.ai.document.Document> parsed =
        documentService.parseDocument(storedFile);
    if (parsed.isEmpty()) {
      log.warn("No content extracted from uploaded document: {}", fileName);
      throw new EmptyDocumentContentException(fileName);
    }

    String filePath = storedFile.toAbsolutePath().toString();
    String contentType = Files.probeContentType(storedFile);
    long fileSize = Files.size(storedFile);

    var doc = new Document(fileName, filePath, contentType, fileSize, DocumentSourceType.UPLOAD);
    doc.setLibraryId(libraryId);
    doc.setOrganizationId(organizationId);
    doc.setUploadedByUserId(uploadedByUserId);
    doc = documentRepository.save(doc);

    try {
      List<org.springframework.ai.document.Document> chunks =
          chunkingService.chunkDocuments(fileName, parsed);
      log.debug("Uploaded file {} produced {} chunks", fileName, chunks.size());

      storeChunks(doc, chunks);

      doc.setChunkCount(chunks.size());
      doc.setIndexedAt(Instant.now());
      doc.setChecksum(checksum);
      doc.setStatus(DocumentStatus.INDEXED);
      doc = documentRepository.save(doc);
    } catch (Exception e) {
      documentRepository.delete(doc);
      metrics.recordFailed();
      throw e;
    }

    metrics.recordProcessed();
    return doc;
  }

  private void storeChunks(
      Document document, List<org.springframework.ai.document.Document> chunks) {
    // library_id and organization_id are the filter axis the permission-aware vector search
    // (#202) filters on - carried on every chunk, not just the document row, so that search can
    // apply the filter directly in the VectorStore query without a join back to the relational
    // model (see docs/features/spaces-and-assets.md#durchsetzung-zur-abfragezeit). Both are
    // currently always the single system library / the single seeded organization - see the
    // Javadoc on Document#libraryId.
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
