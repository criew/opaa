package io.opaa.indexing;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.indexing.metadata.CoreMetadataChunkKeys;
import io.opaa.indexing.metadata.DocumentChunkMetadata;
import io.opaa.indexing.metadata.DocumentMetadataService;
import io.opaa.indexing.pipeline.ChunkPipelineMetadata;
import io.opaa.indexing.pipeline.DiscoveredAttachment;
import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineRegistry;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineRunner;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.indexing.pipeline.confluence.ConfluenceDocumentPipeline;
import io.opaa.indexing.source.attachment.AttachmentAccess;
import io.opaa.indexing.source.attachment.AttachmentDownloadLimits;
import io.opaa.indexing.source.attachment.AttachmentIndexer;
import io.opaa.indexing.source.attachment.AttachmentSource;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.ContentFormatter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;

public class FileProcessingService {

  private static final Logger log = LoggerFactory.getLogger(FileProcessingService.class);

  /**
   * Makes {@code getFormattedContent(EMBED)} byte-identical to {@code getText()}: no metadata ever
   * reaches the embedding input. Applied to a document {@link #storeChunks} found to be one chunk.
   */
  private static final ContentFormatter CHUNK_EMBED_CONTENT_FORMATTER_NO_PREFIX =
      (document, mode) -> document.getText();

  /**
   * The {@code EMBED}-only formatter carrying one chunk's Kontextpraefix (ingestion-pipelines.md,
   * Querschnittsregel (b); metadata-schema.md, Wirkstelle 2): the prefix in brackets, a blank line,
   * then the chunk text. Ignores every metadata key rather than excluding a known list, so a key
   * added later cannot re-enter the embedding input. Stored chunk text and citations are unaffected
   * - the quoted excerpt in a Beleg stays the original wording.
   */
  private static ContentFormatter chunkEmbedFormatterWithPrefix(String prefix) {
    return (document, mode) -> ChunkContextPrefix.format(prefix, document.getText());
  }

  private final DocumentPipelineRegistry pipelineRegistry;
  private final DocumentRepository documentRepository;
  private final VectorChunkStore vectorChunkStore;
  private final ChecksumService checksumService;
  private final IndexingMetrics metrics;
  private final LibraryStorageQuotaService storageQuotaService;
  private final int embeddingBatchSize;
  private final int embeddingConcurrency;
  private final Executor embeddingExecutor;

  /**
   * Lazily resolved: {@link AttachmentIndexer} itself depends on this class to store an attachment,
   * so an eager dependency in both directions would deadlock Spring's bean graph. Resolved once, at
   * the point a pipeline actually reports a {@link DiscoveredAttachment}.
   */
  private final ObjectProvider<AttachmentIndexer> attachmentIndexerProvider;

  /** The generalized attachment path's limits for a Mail attachment - see its own Javadoc. */
  private final AttachmentDownloadLimits mailAttachmentLimits;

  /**
   * Only read by {@link #processUploadedFileAsync} to build the upload path's own {@link
   * StandaloneAttachmentAccess} - an upload has no run whose library entity is already in hand.
   */
  private final KnowledgeLibraryRepository libraryRepository;

  /**
   * Runs the deterministic core-field extraction between parsing and {@link #storeChunks} on every
   * path that writes chunks (ADR-0024) - a system process of the ingest, no rights context.
   */
  private final DocumentMetadataService documentMetadataService;

  public FileProcessingService(
      DocumentPipelineRegistry pipelineRegistry,
      DocumentRepository documentRepository,
      VectorChunkStore vectorChunkStore,
      ChecksumService checksumService,
      IndexingMetrics metrics,
      LibraryStorageQuotaService storageQuotaService,
      IndexingProperties indexingProperties,
      Executor embeddingExecutor,
      ObjectProvider<AttachmentIndexer> attachmentIndexerProvider,
      AttachmentDownloadLimits mailAttachmentLimits,
      KnowledgeLibraryRepository libraryRepository,
      DocumentMetadataService documentMetadataService) {
    this.pipelineRegistry = pipelineRegistry;
    this.documentRepository = documentRepository;
    this.vectorChunkStore = vectorChunkStore;
    this.checksumService = checksumService;
    this.metrics = metrics;
    this.storageQuotaService = storageQuotaService;
    this.embeddingBatchSize = indexingProperties.batchSize();
    this.embeddingConcurrency = indexingProperties.embeddingConcurrency();
    this.embeddingExecutor = embeddingExecutor;
    this.attachmentIndexerProvider = attachmentIndexerProvider;
    this.mailAttachmentLimits = mailAttachmentLimits;
    this.libraryRepository = libraryRepository;
    this.documentMetadataService = documentMetadataService;
  }

  public FileProcessingResult processFile(Path file, KnowledgeLibrary targetLibrary)
      throws IOException {
    return processFile(file, targetLibrary, null, null);
  }

  /**
   * The FILESYSTEM-folder-aware counterpart of {@link #processFile(Path, KnowledgeLibrary)}
   * (ADR-0020), carrying the {@code io.opaa.library.LibraryFolder} of {@code file}'s directory.
   *
   * @param folderId the folder {@code file} belongs to, or {@code null} for the library's root -
   *     also written onto an unchanged, already-{@code INDEXED} document, so a moved document picks
   *     up its new folder.
   *     <p>Deliberately not {@code @Transactional}: {@code uk_documents_library_path} requires the
   *     old row's delete to be visible before the new row's insert, while Hibernate orders inserts
   *     before deletes within a flush.
   */
  public FileProcessingResult processFile(Path file, KnowledgeLibrary targetLibrary, UUID folderId)
      throws IOException {
    return processFile(file, targetLibrary, folderId, null);
  }

  /**
   * Like {@link #processFile(Path, KnowledgeLibrary, UUID)}, plus {@code attachmentAccess}: a
   * non-{@code null} access turns every discovered attachment into a child {@code Document}
   * (ADR-0022), {@code null} discards it. A changed document is updated in place, never deleted and
   * recreated, and its chunks are exchanged in the order ingestion-pipelines.md, "Übergabepunkt"
   * prescribes.
   */
  public FileProcessingResult processFile(
      Path file, KnowledgeLibrary targetLibrary, UUID folderId, AttachmentAccess attachmentAccess)
      throws IOException {
    String filePath = file.toAbsolutePath().toString();
    String fileName = file.getFileName().toString();

    // Compute checksum before any processing
    String checksum = checksumService.computeSha256(file);
    String contentType = Files.probeContentType(file);
    long fileSize = Files.size(file);

    // Identity is (library_id, file_path), never file_path alone - the same path in a different
    // library is an independent document.
    Optional<Document> existing =
        documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), filePath);
    Document doc = null;
    boolean replacingExistingChunks = false;
    if (existing.isPresent()) {
      Document existingDoc = existing.get();
      if (checksum.equals(existingDoc.getChecksum())
          && existingDoc.getStatus() == DocumentStatus.INDEXED) {
        if (!Objects.equals(existingDoc.getFolderId(), folderId)) {
          existingDoc.setFolderId(folderId);
          documentRepository.save(existingDoc);
        }
        log.info("Skipping unchanged document: {}", fileName);
        metrics.recordSkipped();
        return FileProcessingResult.SKIPPED;
      }
      // Updated in place under the same id - see this method's own Javadoc.
      long previousSize = existingDoc.getFileSize() == null ? 0L : existingDoc.getFileSize();
      long delta = fileSize - previousSize;
      if (storageQuotaService.wouldExceedQuota(targetLibrary.getId(), delta)) {
        log.warn(
            "Skipping {}: library {} storage quota would be exceeded",
            fileName,
            targetLibrary.getId());
        metrics.recordSkipped();
        return FileProcessingResult.QUOTA_EXCEEDED;
      }
      replacingExistingChunks = true;
      existingDoc.setContentType(contentType);
      existingDoc.setFileSize(fileSize);
      existingDoc.setFolderId(folderId);
      doc = existingDoc;
    } else {
      // See this method's own comment on why this runs after the existing-document handling above.
      if (storageQuotaService.wouldExceedQuota(targetLibrary.getId(), fileSize)) {
        log.warn(
            "Skipping {}: library {} storage quota would be exceeded",
            fileName,
            targetLibrary.getId());
        metrics.recordSkipped();
        return FileProcessingResult.QUOTA_EXCEEDED;
      }
    }

    if (doc == null) {
      doc = new Document(fileName, filePath, contentType, fileSize);
      doc.setLibraryId(targetLibrary.getId());
      doc.setOrganizationId(targetLibrary.getOrganizationId());
      doc.setFolderId(folderId);
    }
    doc = documentRepository.save(doc);

    Document savedDoc = doc;
    UUID documentId = doc.getId();
    boolean preservingPreviousChunks = replacingExistingChunks;
    try {
      DocumentPipelineRegistry.Routed routed = pipelineRegistry.routedPipelineFor(file, fileName);
      DocumentPipeline pipeline = routed.pipeline();
      DocumentPipelineResult parsed =
          DocumentPipelineRunner.run(
              pipeline,
              DocumentPipelineSource.ofFile(file, fileName, routed.detectedExtension()),
              result -> {
                // Before the attachments: their own quota checks must already see the parent's
                // corrected (attachment-free) fileSize, or the attachment bytes count twice.
                applyContentByteSizeOverride(savedDoc, result);
                processDiscoveredAttachments(
                    result.discoveredAttachments(),
                    documentId,
                    filePath,
                    DocumentSourceType.FILESYSTEM,
                    attachmentAccess);
              });
      switch (parsed.outcome()) {
        case NO_EXTRACTABLE_TEXT -> {
          log.warn("No usable text extracted from {} by pipeline {}", file, pipeline.id());
          deletePreviousChunks(replacingExistingChunks, documentId);
          return markConnectorRejected(doc.getId());
        }
        case NO_CONTENT -> {
          log.warn("No content extracted from: {}", file);
          deletePreviousChunks(replacingExistingChunks, documentId);
          return markConnectorFailed(doc.getId(), true);
        }
        case PARSE_FAILED -> {
          log.warn("Could not parse {} with pipeline {}", file, pipeline.id());
          return markConnectorFailed(doc.getId(), false);
        }
        case CHUNKED ->
            log.debug(
                "File {} produced {} chunks via pipeline {}",
                fileName,
                parsed.chunks().size(),
                pipeline.id());
      }
      List<org.springframework.ai.document.Document> chunks = parsed.chunks();

      if (replacingExistingChunks) {
        // Only now, with the new chunks in hand - see this method's own Javadoc.
        vectorChunkStore.deleteByDocumentId(documentId);
        preservingPreviousChunks = false;
      }
      // Enrich chunks with metadata and store via VectorStore
      storeChunks(
          doc,
          chunks,
          ChunkContextTitle.deriveTitle(fileName),
          pipeline,
          routingExtensionFor(routed),
          extractCoreMetadata(doc, fileName, parsed));

      FileProcessingResult result =
          markConnectorIndexed(doc.getId(), chunks.size(), checksum, null);
      if (result == FileProcessingResult.SKIPPED) {
        return result;
      }
    } catch (Exception e) {
      markConnectorFailedAfterException(doc.getId(), preservingPreviousChunks);
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
   * origin - the same processing chain for an {@code HTTP_DIRECTORY} file and an {@code RSS_FEED}
   * entry's attachment, only the recorded provenance differs.
   *
   * @param sourceEntryUrl the detail page URL an attachment was found on ({@link
   *     Document#getSourceEntryUrl}), or {@code null} when this document was not found through
   *     another document
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
    return processUrlFile(
        localFile,
        originalFileName,
        remoteUrl,
        lastModified,
        remoteFileSize,
        targetLibrary,
        sourceType,
        sourceEntryUrl,
        (UUID) null);
  }

  /**
   * The attachment-aware counterpart of the eight-argument overload above: sets {@link
   * Document#getParentDocumentId()} (ADR-0022, Entscheidung 4) so the document is a queryable child
   * of {@code parentDocumentId}. Discards any {@link DiscoveredAttachment} the routed pipeline
   * reports - the ten-argument overload below is the variant that indexes them.
   *
   * @param parentDocumentId the row this document is an attachment of, or {@code null} for a
   *     document that is not an attachment
   */
  public FileProcessingResult processUrlFile(
      Path localFile,
      String originalFileName,
      String remoteUrl,
      String lastModified,
      long remoteFileSize,
      KnowledgeLibrary targetLibrary,
      DocumentSourceType sourceType,
      String sourceEntryUrl,
      UUID parentDocumentId)
      throws IOException {
    return processUrlFile(
        localFile,
        originalFileName,
        remoteUrl,
        lastModified,
        remoteFileSize,
        targetLibrary,
        sourceType,
        sourceEntryUrl,
        parentDocumentId,
        null);
  }

  /**
   * Like the nine-argument overload above, plus {@code attachmentAccess}: a discovered attachment
   * becomes a child of <em>this</em> document, chaining {@code parent_document_id} through any
   * nesting depth, and the row records where inside its source it sits ({@link
   * AttachmentAccess#sourceContext()}, ADR-0023). {@code null} discards it and records {@link
   * SourceDocumentContext#NONE}. Updated in place like {@link #processFile}.
   */
  public FileProcessingResult processUrlFile(
      Path localFile,
      String originalFileName,
      String remoteUrl,
      String lastModified,
      long remoteFileSize,
      KnowledgeLibrary targetLibrary,
      DocumentSourceType sourceType,
      String sourceEntryUrl,
      UUID parentDocumentId,
      AttachmentAccess attachmentAccess)
      throws IOException {

    String fileName = originalFileName;
    SourceDocumentContext context =
        attachmentAccess == null ? SourceDocumentContext.NONE : attachmentAccess.sourceContext();

    // Compute SHA-256 on the downloaded file for content-based deduplication
    String checksum = checksumService.computeSha256(localFile);

    // Identity is (library_id, file_path) - see processFile above.
    Optional<Document> existing =
        documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), remoteUrl);
    Document doc = null;
    boolean replacingExistingChunks = false;
    if (existing.isPresent()) {
      Document existingDoc = existing.get();
      if (checksum.equals(existingDoc.getChecksum())
          && existingDoc.getStatus() == DocumentStatus.INDEXED) {
        log.info("Skipping unchanged URL document (same checksum): {}", fileName);
        metrics.recordSkipped();
        return FileProcessingResult.SKIPPED;
      }
      // Updated in place under the same id - see this method's own Javadoc.
      long previousSize = existingDoc.getFileSize() == null ? 0L : existingDoc.getFileSize();
      long delta = remoteFileSize - previousSize;
      if (storageQuotaService.wouldExceedQuota(targetLibrary.getId(), delta)) {
        log.warn(
            "Skipping {}: library {} storage quota would be exceeded",
            fileName,
            targetLibrary.getId());
        metrics.recordSkipped();
        return FileProcessingResult.QUOTA_EXCEEDED;
      }
      replacingExistingChunks = true;
      existingDoc.setFileName(fileName);
      existingDoc.setContentType(Files.probeContentType(localFile));
      existingDoc.setFileSize(remoteFileSize);
      existingDoc.setSourceEntryUrl(sourceEntryUrl);
      existingDoc.setParentDocumentId(parentDocumentId);
      existingDoc.applySourceContext(context);
      doc = existingDoc;
    } else {
      // See processFile's own comment on why this runs after the existing-document handling above.
      if (storageQuotaService.wouldExceedQuota(targetLibrary.getId(), remoteFileSize)) {
        log.warn(
            "Skipping {}: library {} storage quota would be exceeded",
            fileName,
            targetLibrary.getId());
        metrics.recordSkipped();
        return FileProcessingResult.QUOTA_EXCEEDED;
      }
    }

    if (doc == null) {
      String contentType = Files.probeContentType(localFile);
      doc = new Document(fileName, remoteUrl, contentType, remoteFileSize, sourceType);
      doc.setLibraryId(targetLibrary.getId());
      doc.setOrganizationId(targetLibrary.getOrganizationId());
      doc.setSourceEntryUrl(sourceEntryUrl);
      doc.setParentDocumentId(parentDocumentId);
      doc.applySourceContext(context);
    }
    doc = documentRepository.save(doc);

    Document savedDoc = doc;
    UUID documentId = doc.getId();
    boolean preservingPreviousChunks = replacingExistingChunks;
    try {
      DocumentPipelineRegistry.Routed routed =
          pipelineRegistry.routedPipelineFor(localFile, fileName);
      DocumentPipeline pipeline = routed.pipeline();
      DocumentPipelineResult parsed =
          DocumentPipelineRunner.run(
              pipeline,
              DocumentPipelineSource.ofFile(localFile, fileName, routed.detectedExtension()),
              result -> {
                // Before the attachments - see processFile's handler on the quota ordering.
                applyContentByteSizeOverride(savedDoc, result);
                processDiscoveredAttachments(
                    result.discoveredAttachments(),
                    documentId,
                    remoteUrl,
                    sourceType,
                    attachmentAccess);
              });
      switch (parsed.outcome()) {
        case NO_EXTRACTABLE_TEXT -> {
          log.warn(
              "No usable text extracted from URL document {} by pipeline {}",
              remoteUrl,
              pipeline.id());
          deletePreviousChunks(replacingExistingChunks, documentId);
          return markConnectorRejected(doc.getId());
        }
        case NO_CONTENT -> {
          log.warn("No content extracted from URL document: {}", remoteUrl);
          deletePreviousChunks(replacingExistingChunks, documentId);
          return markConnectorFailed(doc.getId(), true);
        }
        case PARSE_FAILED -> {
          log.warn("Could not parse URL document {} with pipeline {}", remoteUrl, pipeline.id());
          return markConnectorFailed(doc.getId(), false);
        }
        case CHUNKED ->
            log.debug(
                "URL file {} produced {} chunks via pipeline {}",
                fileName,
                parsed.chunks().size(),
                pipeline.id());
      }
      List<org.springframework.ai.document.Document> chunks = parsed.chunks();

      // fileName is always a real file name on this path, so ChunkContextTitle's
      // filesystem-style-name assumption applies; only processRssEntry's own entry-body document
      // uses a headline instead - see storeChunks's Javadoc.
      if (replacingExistingChunks) {
        // Only now, with the new chunks in hand - see this method's own Javadoc.
        vectorChunkStore.deleteByDocumentId(documentId);
        preservingPreviousChunks = false;
      }
      storeChunks(
          doc,
          chunks,
          ChunkContextTitle.deriveTitle(fileName),
          pipeline,
          routingExtensionFor(routed),
          extractCoreMetadata(doc, fileName, parsed));

      FileProcessingResult result =
          markConnectorIndexed(doc.getId(), chunks.size(), checksum, lastModified);
      if (result == FileProcessingResult.SKIPPED) {
        return result;
      }
    } catch (Exception e) {
      markConnectorFailedAfterException(doc.getId(), preservingPreviousChunks);
      metrics.recordFailed();
      throw e;
    }

    metrics.recordProcessed();
    return FileProcessingResult.PROCESSED;
  }

  /**
   * Processes a single RSS feed entry's already-extracted main text (ADR-0017, decision 2): with no
   * file to detect a format from, the text bypasses admission and routing and goes straight to
   * {@link DocumentPipelineRegistry#fallbackPipeline()}. Identity is the {@code entryUrl} in {@code
   * file_path}; change detection, chunk replacement order and quota handling mirror {@link
   * #processUrlFile}.
   */
  public FileProcessingResult processRssEntry(
      String mainText,
      String entryTitle,
      String entryUrl,
      String publishedAt,
      KnowledgeLibrary targetLibrary) {

    boolean hasTitle = entryTitle != null && !entryTitle.isBlank();
    String fileName = hasTitle ? entryTitle : entryUrl;
    // The entry's body document has no filesystem-style file_name to derive a title from: a
    // headline is free text (used verbatim), and a URL fallback shares a domain/path prefix across
    // every entry of the same feed, so it gets no prefix at all - see storeChunks's Javadoc.
    String contextTitle = hasTitle ? entryTitle : null;
    byte[] contentBytes = mainText.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String checksum = checksumService.computeSha256(contentBytes);

    // Identity is (library_id, file_path) - see processFile above.
    Optional<Document> existing =
        documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), entryUrl);
    Document doc = null;
    boolean replacingExistingChunks = false;
    if (existing.isPresent()) {
      Document existingDoc = existing.get();
      if (checksum.equals(existingDoc.getChecksum())
          && existingDoc.getStatus() == DocumentStatus.INDEXED) {
        log.info("Skipping unchanged RSS entry (same checksum): {}", entryUrl);
        metrics.recordSkipped();
        return FileProcessingResult.SKIPPED;
      }
      // Updated in place under the same id, never deleted-and-recreated (ADR-0022, Entscheidung
      // 4): an attachment's parent_document_id points at this row, so deleting it would fail
      // fk_documents_parent. Only the chunks are exchanged.
      //
      // The row is therefore still present when the quota is checked, so the check measures the
      // size delta explicitly: the full new size against a usedBytes that still includes the old
      // size would double-count the entry being replaced.
      long previousSize = existingDoc.getFileSize() == null ? 0L : existingDoc.getFileSize();
      long delta = contentBytes.length - previousSize;
      if (storageQuotaService.wouldExceedQuota(targetLibrary.getId(), delta)) {
        log.warn(
            "Skipping RSS entry {}: library {} storage quota would be exceeded",
            entryUrl,
            targetLibrary.getId());
        metrics.recordSkipped();
        return FileProcessingResult.QUOTA_EXCEEDED;
      }
      replacingExistingChunks = true;
      existingDoc.setFileName(fileName);
      existingDoc.setContentType("text/html");
      existingDoc.setFileSize((long) contentBytes.length);
      doc = existingDoc;
    } else {
      // Runs before the row is created - there is no existing row on this branch.
      if (storageQuotaService.wouldExceedQuota(targetLibrary.getId(), contentBytes.length)) {
        log.warn(
            "Skipping RSS entry {}: library {} storage quota would be exceeded",
            entryUrl,
            targetLibrary.getId());
        metrics.recordSkipped();
        return FileProcessingResult.QUOTA_EXCEEDED;
      }
    }

    if (doc == null) {
      doc =
          new Document(
              fileName,
              entryUrl,
              "text/html",
              (long) contentBytes.length,
              DocumentSourceType.RSS_FEED);
      doc.setLibraryId(targetLibrary.getId());
      doc.setOrganizationId(targetLibrary.getOrganizationId());
    }
    doc = documentRepository.save(doc);

    UUID documentId = doc.getId();
    boolean preservingPreviousChunks = replacingExistingChunks;
    try {
      // The entry body never was a file, so there is no content to detect a format from - it is
      // already extracted text and goes to the fallback pipeline directly (ADR-0017, decision 2).
      DocumentPipeline pipeline = pipelineRegistry.fallbackPipeline();
      DocumentPipelineResult parsed =
          DocumentPipelineRunner.run(
              pipeline, DocumentPipelineSource.ofExtractedText(mainText, fileName));
      switch (parsed.outcome()) {
        // An entry whose text chunks down to nothing must not stay INDEXED with zero chunks -
        // the same guard the file paths have, reached through a feed instead of a file.
        case NO_EXTRACTABLE_TEXT -> {
          log.warn("No usable text in RSS entry {}", entryUrl);
          deletePreviousChunks(replacingExistingChunks, documentId);
          return markConnectorRejected(doc.getId());
        }
        case NO_CONTENT -> {
          log.warn("No content extracted from RSS entry: {}", entryUrl);
          deletePreviousChunks(replacingExistingChunks, documentId);
          return markConnectorFailed(doc.getId(), true);
        }
        case PARSE_FAILED -> {
          log.warn("Could not parse RSS entry {}", entryUrl);
          return markConnectorFailed(doc.getId(), false);
        }
        case CHUNKED ->
            log.debug("RSS entry {} produced {} chunks", entryUrl, parsed.chunks().size());
      }
      List<org.springframework.ai.document.Document> chunks = parsed.chunks();

      // No routing decision was ever made for this text (see the pipeline selection above), so no
      // routing key is written - same as a failed detection (#routingExtensionFor). The entry's
      // headline and its feed-declared publication instant are its declared properties (ADR-0024);
      // the name is a headline, marked as such so no naming convention is read out of it.
      DocumentPipelineResult withHeadline =
          parsed.withProperties(
              parsed
                  .properties()
                  .withTitle(contextTitle)
                  .withSyntheticName(true)
                  .withDocumentDate(DocumentProperties.instantToLocalDate(publishedAt)));
      if (replacingExistingChunks) {
        // Only now, with the new chunks in hand - see this method's own Javadoc.
        vectorChunkStore.deleteByDocumentId(documentId);
        preservingPreviousChunks = false;
      }
      storeChunks(
          doc,
          chunks,
          contextTitle,
          pipeline,
          Optional.empty(),
          extractCoreMetadata(doc, fileName, withHeadline));

      FileProcessingResult result =
          markConnectorIndexed(doc.getId(), chunks.size(), checksum, publishedAt);
      if (result == FileProcessingResult.SKIPPED) {
        return result;
      }
    } catch (Exception e) {
      markConnectorFailedAfterException(doc.getId(), preservingPreviousChunks);
      metrics.recordFailed();
      throw e;
    }

    metrics.recordProcessed();
    return FileProcessingResult.PROCESSED;
  }

  /**
   * Parses, chunks and embeds a document already stored on disk and already {@code PENDING}. Runs
   * on {@code uploadTaskExecutor}, a pool of its own, so uploads cannot exhaust an indexing run's
   * pool, and takes the document's id rather than a detached entity. Every outcome leaves a
   * terminal status behind, written as a conditional {@code UPDATE} - a plain save would re-insert
   * a concurrently deleted row as a zombie, since {@link Document} carries no {@code @Version}.
   */
  @Async("uploadTaskExecutor")
  public void processUploadedFileAsync(UUID documentId, Path storedFile) {
    processStoredFile(documentId, storedFile, false, uploadAttachmentAccessFor(documentId));
  }

  /**
   * The upload path's {@link AttachmentAccess}: an attachment of an uploaded {@code .eml}/{@code
   * .msg} runs through the generalized attachment path like any connector's, only without a job -
   * events are logged, progress is a no-op, the quota runs over the library. {@code null} only when
   * the document or its library is already gone.
   */
  private AttachmentAccess uploadAttachmentAccessFor(UUID documentId) {
    return documentRepository
        .findById(documentId)
        .map(Document::getLibraryId)
        .flatMap(libraryRepository::findById)
        .<AttachmentAccess>map(library -> new StandaloneAttachmentAccess(library, "Upload"))
        .orElse(null);
  }

  /**
   * Re-runs the current pipeline over a document whose source file is still on this machine and
   * replaces its chunks under the same row id, so citations survive. A document that cannot be
   * re-chunked keeps its chunks and its {@code INDEXED} row (ingestion-pipelines.md,
   * "Übergabepunkt"). A non-{@code null} {@code attachmentAccess} lets attachments survive.
   *
   * @return whether the document was actually re-indexed
   */
  boolean reindexStoredDocument(
      UUID documentId, Path storedFile, AttachmentAccess attachmentAccess) {
    return processStoredFile(documentId, storedFile, true, attachmentAccess);
  }

  /**
   * The synchronous body shared by {@link #processUploadedFileAsync} and {@link
   * #reindexStoredDocument}: parse, chunk, store and transition a document whose row already exists
   * and whose file is already on disk.
   *
   * @param replacingExistingChunks {@code true} for a re-index, which deletes the previous chunks
   *     just before the new ones are written and leaves the document untouched on every non-{@code
   *     CHUNKED} outcome; {@code false} for a first upload, where every outcome must reach a
   *     terminal status
   * @return whether chunks were written and the document transitioned to {@code INDEXED}
   */
  private boolean processStoredFile(
      UUID documentId,
      Path storedFile,
      boolean replacingExistingChunks,
      AttachmentAccess attachmentAccess) {
    Document doc = documentRepository.findById(documentId).orElse(null);
    if (doc == null) {
      log.warn(
          "Uploaded document {} no longer exists, skipping asynchronous processing", documentId);
      return false;
    }

    // Whether the previous chunks have already been removed - the point from which this method can
    // no longer leave the document untouched, and therefore the only case in which the catch block
    // below may clean up on the re-index path.
    boolean previousChunksDeleted = false;
    try {
      DocumentPipelineRegistry.Routed routed =
          pipelineRegistry.routedPipelineFor(storedFile, doc.getFileName());
      DocumentPipeline pipeline = routed.pipeline();
      Document storedDoc = doc;
      DocumentPipelineResult parsed =
          DocumentPipelineRunner.run(
              pipeline,
              DocumentPipelineSource.ofFile(
                  storedFile, doc.getFileName(), routed.detectedExtension()),
              result -> {
                if (attachmentAccess == null) {
                  // No attachment path for this caller (see reindexStoredDocument's contract):
                  // discovered attachments are discarded and the parent keeps its raw fileSize -
                  // reducing it without indexing the attachments would under-count the quota.
                  return;
                }
                // Before the attachments - see processFile's handler on the quota ordering.
                applyContentByteSizeOverride(storedDoc, result);
                processDiscoveredAttachments(
                    result.discoveredAttachments(),
                    storedDoc.getId(),
                    storedDoc.getFilePath(),
                    storedDoc.getSourceType(),
                    attachmentAccess);
              });
      switch (parsed.outcome()) {
        case NO_EXTRACTABLE_TEXT -> {
          log.warn(
              "No usable text extracted from stored document {} by pipeline {}",
              doc.getFileName(),
              pipeline.id());
          if (replacingExistingChunks) {
            return false;
          }
          // recordFailed, not recordSkipped: a single, deliberate upload has no "skipped" concept
          // the way a connector run's item count does.
          markUploadFailed(doc.getId(), DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE);
          metrics.recordFailed();
          return false;
        }
        case NO_CONTENT -> {
          log.warn("No content extracted from uploaded document: {}", doc.getFileName());
          if (replacingExistingChunks) {
            return false;
          }
          markUploadFailed(doc.getId(), "Aus der Datei konnte kein Text extrahiert werden");
          metrics.recordFailed();
          return false;
        }
        case PARSE_FAILED -> {
          log.warn("Could not parse stored document {}", doc.getFileName());
          if (replacingExistingChunks) {
            return false;
          }
          markUploadFailed(doc.getId(), "Die Datei konnte nicht verarbeitet werden");
          metrics.recordFailed();
          return false;
        }
        case CHUNKED ->
            log.debug(
                "Stored file {} produced {} chunks via pipeline {}",
                doc.getFileName(),
                parsed.chunks().size(),
                pipeline.id());
      }
      List<org.springframework.ai.document.Document> chunks = parsed.chunks();

      DocumentChunkMetadata coreMetadata = extractCoreMetadata(doc, doc.getFileName(), parsed);
      if (replacingExistingChunks) {
        vectorChunkStore.deleteByDocumentId(doc.getId());
        previousChunksDeleted = true;
      }
      storeChunks(
          doc,
          chunks,
          ChunkContextTitle.deriveTitle(doc.getFileName()),
          pipeline,
          routingExtensionFor(routed),
          coreMetadata);

      int updated = documentRepository.markIndexed(doc.getId(), chunks.size(), Instant.now());
      if (updated == 0) {
        log.warn(
            "Uploaded document {} was deleted while its chunks were being written; removing them"
                + " again",
            doc.getId());
        vectorChunkStore.deleteByDocumentId(doc.getId());
        return false;
      }
      metrics.recordProcessed();
      return true;
    } catch (Exception e) {
      log.error("Failed to process stored document {}", doc.getFileName(), e);
      if (replacingExistingChunks && !previousChunksDeleted) {
        // The failure happened before anything was destroyed. The document still has its working
        // chunks and its INDEXED row; keeping them is the point of the re-index contract - the
        // caller reports this as skipped and the document can be tried again.
        metrics.recordFailed();
        return false;
      }
      // Past the delete, or on the upload path where there is no working previous state:
      // storeChunks may already have written chunks for this document, so a FAILED row never
      // leaves orphaned chunks behind that search would still return.
      vectorChunkStore.deleteByDocumentId(doc.getId());
      markUploadFailed(doc.getId(), "Die Datei konnte nicht verarbeitet werden", true);
      metrics.recordFailed();
      return false;
    }
  }

  /**
   * Removes the chunks of a document being replaced on the outcomes where the new version is
   * legitimately without content: "parsed and empty" ({@code NO_CONTENT}/{@code
   * NO_EXTRACTABLE_TEXT}) is a statement about the new content, so the old chunks must not survive
   * it (ingestion-pipelines.md, "Übergabepunkt"). A no-op when there were no chunks.
   */
  private void deletePreviousChunks(boolean replacingExistingChunks, UUID documentId) {
    if (replacingExistingChunks) {
      vectorChunkStore.deleteByDocumentId(documentId);
    }
  }

  private void markUploadFailed(UUID documentId, String errorMessage) {
    markUploadFailed(documentId, errorMessage, false);
  }

  /**
   * @param chunksRemoved whether this document's chunks were just deleted - {@code chunk_count}
   *     then has to become {@code 0} to match. {@code false} on every outcome that reached this
   *     before {@link #storeChunks} ever ran, where the column is already correct.
   */
  private void markUploadFailed(UUID documentId, String errorMessage, boolean chunksRemoved) {
    int updated =
        chunksRemoved
            ? documentRepository.markFailedWithoutChunks(documentId, errorMessage)
            : documentRepository.markFailed(documentId, errorMessage);
    if (updated == 0) {
      log.warn("Uploaded document {} was deleted before it could be marked FAILED", documentId);
    }
  }

  /**
   * Processes one Confluence page's storage-format body (ADR-0023) the way {@link #processRssEntry}
   * processes an RSS entry's text: identity by the title-free page URL, SHA-256 over the body
   * behind the executor's version check, space key and ancestor titles as passthrough metadata. It
   * goes to {@code ConfluenceDocumentPipeline} directly - a registry without it is a wiring error.
   *
   * @param storageBody the page body in Confluence storage format (XHTML with macro elements)
   * @param version the page's Confluence version number, the executor's pre-fetch change marker
   * @param lastModified when the page's current version was created - its Stand; {@code null} when
   *     the instance did not say
   */
  public FileProcessingResult processConfluencePage(
      String storageBody,
      String title,
      String pageUrl,
      String version,
      Instant lastModified,
      SourceDocumentContext context,
      KnowledgeLibrary targetLibrary) {
    boolean hasTitle = title != null && !title.isBlank();
    String fileName = hasTitle ? title : pageUrl;
    String contextTitle = confluenceContextTitle(title, context);
    byte[] contentBytes = storageBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String checksum = checksumService.computeSha256(contentBytes);
    Optional<Document> existing =
        documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), pageUrl);
    Document doc = null;
    boolean replacingExistingChunks = false;
    if (existing.isPresent()) {
      Document existingDoc = existing.get();
      if (checksum.equals(existingDoc.getChecksum())
          && existingDoc.getStatus() == DocumentStatus.INDEXED) {
        // Same content under a new version (a title-only edit, a move, a renamed ancestor): the
        // chunks stay, but title, context and the version marker move - the next run's pre-fetch
        // check skips the page again, and the document list, the citation and the run protocol
        // show the current title and place.
        documentRepository.markIndexedFromSource(
            existingDoc.getId(),
            existingDoc.getChunkCount(),
            existingDoc.getIndexedAt(),
            checksum,
            version);
        documentRepository.refreshConnectorTitleAndContext(
            existingDoc.getId(),
            fileName,
            context == null ? null : context.containerKey(),
            context == null ? null : context.hierarchyPath());
        log.info("Skipping unchanged Confluence page (same checksum): {}", pageUrl);
        metrics.recordSkipped();
        return FileProcessingResult.SKIPPED;
      }
      // Updated in place under the same id, never deleted-and-recreated (ADR-0022, Entscheidung
      // 4): the page's attachments point at this row via parent_document_id. Only the chunks are
      // exchanged; the quota check measures the size delta, as in processRssEntry.
      long previousSize = existingDoc.getFileSize() == null ? 0L : existingDoc.getFileSize();
      long delta = contentBytes.length - previousSize;
      if (storageQuotaService.wouldExceedQuota(targetLibrary.getId(), delta)) {
        log.warn(
            "Skipping Confluence page {}: library {} storage quota would be exceeded",
            pageUrl,
            targetLibrary.getId());
        metrics.recordSkipped();
        return FileProcessingResult.QUOTA_EXCEEDED;
      }
      replacingExistingChunks = true;
      existingDoc.setFileName(fileName);
      existingDoc.setContentType("text/html");
      existingDoc.setFileSize((long) contentBytes.length);
      existingDoc.applySourceContext(context);
      doc = existingDoc;
    } else if (storageQuotaService.wouldExceedQuota(targetLibrary.getId(), contentBytes.length)) {
      log.warn(
          "Skipping Confluence page {}: library {} storage quota would be exceeded",
          pageUrl,
          targetLibrary.getId());
      metrics.recordSkipped();
      return FileProcessingResult.QUOTA_EXCEEDED;
    }
    if (doc == null) {
      doc =
          new Document(
              fileName,
              pageUrl,
              "text/html",
              (long) contentBytes.length,
              DocumentSourceType.CONFLUENCE);
      doc.setLibraryId(targetLibrary.getId());
      doc.setOrganizationId(targetLibrary.getOrganizationId());
      doc.applySourceContext(context);
    }
    doc = documentRepository.save(doc);
    boolean preservingPreviousChunks = replacingExistingChunks;
    try {
      DocumentPipeline pipeline =
          pipelineRegistry
              .pipelineById(ConfluenceDocumentPipeline.ID)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Document pipeline "
                              + ConfluenceDocumentPipeline.ID
                              + " is not registered"));
      DocumentPipelineResult parsed =
          DocumentPipelineRunner.run(
              pipeline, DocumentPipelineSource.ofExtractedText(storageBody, fileName));
      switch (parsed.outcome()) {
        case NO_EXTRACTABLE_TEXT -> {
          log.warn("No usable text in Confluence page {}", pageUrl);
          deletePreviousChunks(replacingExistingChunks, doc.getId());
          return markConnectorRejected(doc.getId());
        }
        case NO_CONTENT -> {
          log.warn("No content extracted from Confluence page: {}", pageUrl);
          deletePreviousChunks(replacingExistingChunks, doc.getId());
          return markConnectorFailed(doc.getId(), true);
        }
        case PARSE_FAILED -> {
          log.warn("Could not parse Confluence page {}", pageUrl);
          return markConnectorFailed(doc.getId(), false);
        }
        case CHUNKED ->
            log.debug("Confluence page {} produced {} chunks", pageUrl, parsed.chunks().size());
      }
      List<org.springframework.ai.document.Document> chunks = parsed.chunks();
      // The space and the hierarchy path are not in the body; the Confluence pipeline declares
      // them as passthrough keys, so storeChunks keeps them on every chunk.
      if (context != null) {
        for (org.springframework.ai.document.Document chunk : chunks) {
          if (context.containerKey() != null) {
            chunk
                .getMetadata()
                .put(ConfluenceDocumentPipeline.SPACE_METADATA_KEY, context.containerKey());
          }
          if (context.hierarchyPath() != null) {
            chunk
                .getMetadata()
                .put(ConfluenceDocumentPipeline.HIERARCHY_METADATA_KEY, context.hierarchyPath());
          }
        }
      }
      // The page title is the document's declared title, the creation of its current version the
      // Stand (ADR-0024) - the version number itself is no date. The name of this document is that
      // title (or its URL), never a file name - marked as such so no naming convention is read
      // out of it.
      DocumentProperties properties =
          parsed
              .properties()
              .withSyntheticName(true)
              .withModifiedAt(DocumentProperties.instantToLocalDate(lastModified));
      DocumentPipelineResult withTitle =
          parsed.withProperties(hasTitle ? properties.withTitle(title) : properties);
      if (replacingExistingChunks) {
        // Only now, with the new chunks in hand - see storeChunks' callers.
        vectorChunkStore.deleteByDocumentId(doc.getId());
        preservingPreviousChunks = false;
      }
      storeChunks(
          doc,
          chunks,
          contextTitle,
          pipeline,
          Optional.empty(),
          extractCoreMetadata(doc, fileName, withTitle));
      FileProcessingResult result =
          markConnectorIndexed(doc.getId(), chunks.size(), checksum, version);
      if (result == FileProcessingResult.SKIPPED) {
        return result;
      }
    } catch (Exception e) {
      markConnectorFailedAfterException(doc.getId(), preservingPreviousChunks);
      metrics.recordFailed();
      throw e;
    }
    metrics.recordProcessed();
    return FileProcessingResult.PROCESSED;
  }

  /**
   * The chunk-context prefix of a Confluence page: ancestor titles root first, then the page title,
   * so a chunk embeds and full-text-indexes with the outline it sits in (ingestion-pipelines.md,
   * Querschnittsregel (b)). {@code null} without a title.
   */
  static String confluenceContextTitle(String title, SourceDocumentContext context) {
    if (title == null || title.isBlank()) {
      return null;
    }
    if (context == null || context.hierarchyPath() == null || context.hierarchyPath().isBlank()) {
      return title;
    }
    return context.hierarchyPath() + SourceDocumentContext.HIERARCHY_SEPARATOR + title;
  }

  /**
   * Backs the successful transition to {@code INDEXED} on the connector paths. Uses {@link
   * DocumentRepository#markIndexedFromSource}, a conditional {@code UPDATE}: the row can be deleted
   * while {@link #storeChunks} runs, and a plain save would silently re-insert it as a zombie
   * ({@link Document} assigns its own id and carries no {@code @Version}).
   *
   * @return {@link FileProcessingResult#SKIPPED} if the row was gone - its just-written chunks are
   *     removed again here - otherwise {@link FileProcessingResult#PROCESSED}
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
   * Backs the {@code FAILED} transition on the connector paths when no content could be extracted.
   * Runs before {@link #storeChunks}, so unlike {@link #markConnectorIndexed} there are no chunks
   * to clean up. Counts the document as failed exactly as {@link
   * #markConnectorFailedAfterException} does.
   *
   * @param chunksRemoved {@code true} for {@code NO_CONTENT}, whose previous chunks were just
   *     deleted, so {@code chunk_count} has to become {@code 0}; {@code false} for {@code
   *     PARSE_FAILED}, where the previous chunks and count both stand
   */
  private FileProcessingResult markConnectorFailed(UUID documentId, boolean chunksRemoved) {
    int updated =
        chunksRemoved
            ? documentRepository.markFailedWithoutChunks(documentId, null)
            : documentRepository.markFailed(documentId, null);
    if (updated == 0) {
      log.warn("Document {} was deleted before it could be marked FAILED", documentId);
      metrics.recordSkipped();
      return FileProcessingResult.SKIPPED;
    }
    metrics.recordFailed();
    return FileProcessingResult.FAILED;
  }

  /**
   * The {@code FAILED} transition for a document that never yields a usable chunk. Marks it with
   * {@link DocumentService#NO_EXTRACTABLE_TEXT_MESSAGE} and reports {@link
   * FileProcessingResult#NO_EXTRACTABLE_TEXT}, always with {@code chunk_count = 0} - this outcome
   * removes whatever chunks the document had.
   */
  private FileProcessingResult markConnectorRejected(UUID documentId) {
    int updated =
        documentRepository.markFailedWithoutChunks(
            documentId, DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE);
    if (updated == 0) {
      log.warn("Document {} was deleted before it could be marked as rejected", documentId);
      metrics.recordSkipped();
      return FileProcessingResult.SKIPPED;
    }
    metrics.recordSkipped();
    return FileProcessingResult.NO_EXTRACTABLE_TEXT;
  }

  /**
   * The catch-block counterpart to {@link #markConnectorFailed}, used when parsing, chunking or
   * embedding throws instead of returning empty. Deletes this document's chunks rather than
   * tracking whether {@link #storeChunks} was reached - cheap, and a no-op if it was not.
   *
   * @param preservingPreviousChunks {@code true} while a document being replaced still has its
   *     previous, working chunks and nothing new has been written - deleting here would destroy
   *     exactly the state the replacement order protects. Also decides {@code chunk_count}:
   *     preserved chunks keep the count describing them, removed ones leave the row at {@code 0}.
   *     <p>The chunk delete has its own {@code try/catch}: the caller rethrows the original failure
   *     once this returns, and a pgvector outage on this cleanup must neither swallow that cause
   *     nor skip {@link DocumentRepository#markFailed}.
   */
  private void markConnectorFailedAfterException(
      UUID documentId, boolean preservingPreviousChunks) {
    if (!preservingPreviousChunks) {
      try {
        vectorChunkStore.deleteByDocumentId(documentId);
      } catch (RuntimeException e) {
        log.error(
            "Failed to remove vector store chunks for document {} after a processing error -"
                + " orphaned chunks may remain",
            documentId,
            e);
      }
    }
    int updated =
        preservingPreviousChunks
            ? documentRepository.markFailed(documentId, null)
            : documentRepository.markFailedWithoutChunks(documentId, null);
    if (updated == 0) {
      log.warn("Document {} was deleted before it could be marked FAILED", documentId);
    }
  }

  /**
   * Turns every {@code discovered} attachment into its own {@code Document} row via the generalized
   * attachment path (ADR-0022) - a no-op when {@code attachmentAccess} is {@code null} (a caller
   * with no run context of its own) or when {@code discovered} is empty.
   *
   * @param parentDocumentId the row every indexed attachment becomes a child of
   * @param parentFilePath the {@code file_path} of the document {@code discovered} was found on -
   *     every attachment's own {@code file_path} embeds it (see {@link #attachmentFilePath})
   */
  private void processDiscoveredAttachments(
      List<DiscoveredAttachment> discovered,
      UUID parentDocumentId,
      String parentFilePath,
      DocumentSourceType sourceType,
      AttachmentAccess attachmentAccess) {
    if (discovered.isEmpty() || attachmentAccess == null) {
      return;
    }
    List<AttachmentSource> sources = new ArrayList<>(discovered.size());
    for (int i = 0; i < discovered.size(); i++) {
      DiscoveredAttachment attachment = discovered.get(i);
      sources.add(
          new AttachmentSource.LocalFile(
              attachment.tempFile(),
              attachment.fileName(),
              attachmentFilePath(parentFilePath, i, attachment.fileName())));
    }
    attachmentIndexerProvider
        .getObject()
        .indexAll(
            attachmentAccess,
            sources,
            parentDocumentId,
            parentFilePath,
            sourceType,
            mailAttachmentLimits);
  }

  /**
   * The {@code file_path} identity of the {@code index}-th (0-based, extraction order) attachment
   * of the document at {@code parentFilePath} (ADR-0022, Entscheidung 2), shaped {@code
   * <parentFilePath>/<index>/<fileName>}. Parent path and index keep identically-named attachments
   * apart, and no real file can carry this shape because {@code parentFilePath} names a file.
   * Nesting chains naturally: an inner message's own path becomes its attachments' parent path.
   */
  static String attachmentFilePath(String parentFilePath, int index, String fileName) {
    return parentFilePath + "/" + index + "/" + fileName;
  }

  /**
   * The inverse of {@link #attachmentFilePath}: the 0-based extraction-order index encoded in
   * {@code attachmentPath}, or {@code -1} when it does not have that shape (a parent path that
   * changed since, a malformed row).
   */
  public static int attachmentIndexIn(String parentFilePath, String attachmentPath) {
    String prefix = parentFilePath + "/";
    if (attachmentPath == null || !attachmentPath.startsWith(prefix)) {
      return -1;
    }
    String remainder = attachmentPath.substring(prefix.length());
    int slash = remainder.indexOf('/');
    if (slash <= 0) {
      return -1;
    }
    try {
      return Integer.parseInt(remainder.substring(0, slash));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * Overrides {@code document}'s {@code fileSize} with {@link
   * DocumentPipelineResult#contentByteSizeOverride()} when the pipeline reported one (ADR-0022,
   * Entscheidung 6): the raw file size may include bytes - a Mail attachment's base64 payload -
   * that must not count toward this document's quota footprint once the attachment is its own row
   * with its own {@code fileSize}. A no-op, and therefore {@code save}-free, otherwise.
   */
  private void applyContentByteSizeOverride(Document document, DocumentPipelineResult parsed) {
    parsed
        .contentByteSizeOverride()
        .ifPresent(
            override -> {
              document.setFileSize(override);
              documentRepository.save(document);
            });
  }

  /**
   * The value to persist as {@link ChunkPipelineMetadata#ROUTING_EXTENSION_METADATA_KEY} for {@code
   * routed}, or {@link Optional#empty()} to omit the key - {@link
   * DocumentPipelineRegistry.Routed#formatDetectionFailed()} means the bytes could not be read at
   * all, which is a transient read failure rather than a routing verdict.
   */
  private static Optional<String> routingExtensionFor(DocumentPipelineRegistry.Routed routed) {
    if (routed.formatDetectionFailed()) {
      return Optional.empty();
    }
    return Optional.of(
        routed.detectedExtension() != null
            ? routed.detectedExtension()
            : ChunkPipelineMetadata.NO_ROUTING_EXTENSION);
  }

  /**
   * Enriches {@code chunks} with permission-filter and citation metadata and puts each chunk's
   * Kontextpraefix onto the embedding input only - built by {@link ChunkContextPrefix#forChunk}
   * from the Kernfeld Titel, the document's prefix-effective values and the chunk's Strukturkontext
   * (metadata-schema.md, Wirkstelle 2). A chunk without a prefix gets {@link
   * #CHUNK_EMBED_CONTENT_FORMATTER_NO_PREFIX}. Records afterwards which prefix the chunks were
   * written with, so the Nachlauf knows what is current.
   *
   * @param contextTitle the fallback title, or {@code null} if this document type never gets a
   *     prefix at all. Computed by each caller rather than derived from {@code document}: {@link
   *     DocumentSourceType#RSS_FEED} covers both an entry's free-text-headline body document and
   *     its filesystem-style-named attachments, and the derivation rule differs between them. The
   *     Kernfeld Titel takes precedence over it where one was extracted.
   * @param pipeline the pipeline that produced {@code chunks}; its id and version go onto every
   *     chunk. Which further keys ride along is decided by {@link
   *     DocumentPipelineRegistry#allPassthroughMetadataKeys()}, not by {@code pipeline} alone -
   *     {@code pipeline} is the outer pipeline a nested attachment's chunks are attributed to, so
   *     filtering by its own declaration would drop a key only the inner pipeline declares
   * @param routingExtension see {@link #routingExtensionFor}: written onto every chunk when
   *     present, omitted entirely when empty
   * @param chunkMetadata the document's filterable schema values (ADR-0024, {@link
   *     CoreMetadataChunkKeys} and its library's own filterable fields), written before any
   *     pipeline passthrough - they hang on the document, so no pipeline may set them
   */
  private void storeChunks(
      Document document,
      List<org.springframework.ai.document.Document> chunks,
      String contextTitle,
      DocumentPipeline pipeline,
      Optional<String> routingExtension,
      DocumentChunkMetadata chunkMetadata) {
    boolean documentWasSplit = chunks.size() >= 2;
    // The Kernfeld Titel replaces the file-name humanisation the prefix used before; the caller's
    // own candidate stays the fallback and still decides whether this document type gets a prefix
    // at all - an RSS entry without a headline never does. That decision is recorded below, so the
    // Nachlauf honours it instead of guessing it.
    boolean prefixEligible = contextTitle != null;
    String prefixTitle = prefixEligible ? effectiveContextTitle(chunkMetadata, contextTitle) : null;
    Set<String> passthroughKeys = pipelineRegistry.allPassthroughMetadataKeys();

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
                  // The verfahren that produced this chunk (ingestion-pipelines.md,
                  // Querschnittsregel (d)) - what makes a mixed bestand after a pipeline change
                  // feststellbar and selectively re-indexable at all.
                  metadata.put(ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY, pipeline.id());
                  metadata.put(
                      ChunkPipelineMetadata.PIPELINE_VERSION_METADATA_KEY,
                      (int) pipeline.version());
                  // The routing key actually used (ingestion-pipelines.md, Querschnittsregel
                  // (d)) - what lets a later pipeline-version check compare exactly instead of
                  // re-guessing a document's format from its file name.
                  routingExtension.ifPresent(
                      extension ->
                          metadata.put(
                              ChunkPipelineMetadata.ROUTING_EXTENSION_METADATA_KEY, extension));
                  // The document's filterable core fields (ADR-0024): inherited by every chunk,
                  // written here so both search paths can carry the same condition.
                  metadata.putAll(chunkMetadata.values());
                  // The registry-wide declared passthrough keys - e.g. the chunk's Fundort, or a
                  // message's Kopfdaten (ingestion-pipelines.md, Teil 3, Punkt 5) - copied only
                  // when this chunk actually carries them, and never for a key already written
                  // above: those are this class's own bookkeeping, not a pipeline's to set.
                  for (String passthroughKey : passthroughKeys) {
                    if (metadata.containsKey(passthroughKey)) {
                      continue;
                    }
                    copyIfPresent(chunk, metadata, passthroughKey);
                  }
                  org.springframework.ai.document.Document enrichedChunk =
                      new org.springframework.ai.document.Document(chunk.getText(), metadata);
                  String prefix =
                      ChunkContextPrefix.forChunk(
                          prefixEligible,
                          documentWasSplit,
                          prefixTitle,
                          chunkMetadata.contextPrefixValues(),
                          metadata.get(ChunkingService.LOCATION_METADATA_KEY),
                          chunk.getText());
                  enrichedChunk.setContentFormatter(
                      prefix == null
                          ? CHUNK_EMBED_CONTENT_FORMATTER_NO_PREFIX
                          : chunkEmbedFormatterWithPrefix(prefix));
                  return enrichedChunk;
                })
            .toList();

    addToVectorStore(enriched);
    // Recorded only after the chunks exist: the stamp says "these chunks carry this prefix", which
    // is exactly what the Nachlauf selects against.
    documentRepository.recordContextPrefix(
        document.getId(), chunkMetadata.contextPrefixStamp(prefixTitle), prefixEligible);
  }

  /** The Kernfeld Titel if the document has one, otherwise the caller's own candidate. */
  private static String effectiveContextTitle(
      DocumentChunkMetadata chunkMetadata, String fallback) {
    String coreTitle = chunkMetadata.contextTitle();
    return coreTitle != null && !coreTitle.isBlank() ? coreTitle : fallback;
  }

  /**
   * The deterministic core-field extraction (ADR-0024) for a document that is about to get chunks:
   * runs over {@code fileName} and what {@code parsed} declares, stores the values at the document
   * and returns the effective fields for {@link #storeChunks}. Never fails the ingest - a failure
   * here is logged and the chunks are written without core fields, exactly as an empty result.
   */
  private DocumentChunkMetadata extractCoreMetadata(
      Document document, String fileName, DocumentPipelineResult parsed) {
    try {
      return documentMetadataService.applyDeterministicExtraction(
          document, fileName, parsed.properties());
    } catch (RuntimeException e) {
      log.warn("Core metadata extraction failed for {}; indexing without it", fileName, e);
      return DocumentChunkMetadata.EMPTY;
    }
  }

  /** Copies {@code key} from {@code chunk}'s own metadata into {@code target}, if present. */
  private static void copyIfPresent(
      org.springframework.ai.document.Document chunk, Map<String, Object> target, String key) {
    Object value = chunk.getMetadata().get(key);
    if (value != null) {
      target.put(key, value);
    }
  }

  /**
   * Embeds and persists {@code enriched}: one {@link VectorChunkStore#addChunks} call on the
   * calling thread at {@code embeddingConcurrency == 1}, otherwise contiguous sub-batches on the
   * shared {@code embeddingTaskExecutor}, all awaited before returning. Chunk order is unaffected;
   * the order sub-batches reach the store is not, which is why the evaluation harnesses pin {@code
   * embedding-concurrency} to {@code 1}.
   */
  private void addToVectorStore(List<org.springframework.ai.document.Document> enriched) {
    if (embeddingConcurrency <= 1) {
      vectorChunkStore.addChunks(enriched);
      return;
    }

    int subBatchSize = subBatchSize(enriched.size());
    List<List<org.springframework.ai.document.Document>> subBatches = new ArrayList<>();
    for (int i = 0; i < enriched.size(); i += subBatchSize) {
      subBatches.add(enriched.subList(i, Math.min(i + subBatchSize, enriched.size())));
    }
    if (subBatches.size() <= 1) {
      vectorChunkStore.addChunks(enriched);
      return;
    }

    List<CompletableFuture<Void>> futures =
        subBatches.stream()
            .map(
                subBatch ->
                    CompletableFuture.runAsync(
                        () -> vectorChunkStore.addChunks(subBatch), embeddingExecutor))
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
   * capped at {@code opaa.indexing.batchSize}. {@code Math.max(1, ...)} guards the degenerate
   * {@code chunkCount == 0}, which would otherwise yield 0 and an endless slicing loop.
   */
  private int subBatchSize(int chunkCount) {
    int perWorker = (int) Math.ceil((double) chunkCount / embeddingConcurrency);
    return Math.max(1, Math.min(embeddingBatchSize, perWorker));
  }
}
