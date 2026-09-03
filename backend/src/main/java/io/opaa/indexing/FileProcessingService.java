package io.opaa.indexing;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.indexing.pipeline.ChunkPipelineMetadata;
import io.opaa.indexing.pipeline.DiscoveredAttachment;
import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineRegistry;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineRunner;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.source.attachment.AttachmentAccess;
import io.opaa.indexing.source.attachment.AttachmentDownloadLimits;
import io.opaa.indexing.source.attachment.AttachmentIndexer;
import io.opaa.indexing.source.attachment.AttachmentSource;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.ContentFormatter;
import org.springframework.ai.document.DefaultContentFormatter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;

public class FileProcessingService {

  private static final Logger log = LoggerFactory.getLogger(FileProcessingService.class);

  /**
   * {@code getFormattedContent(EMBED)} byte-identical to {@code getText()} - the #773 whitelist, a
   * true code-level whitelist rather than a metadata exclusion list (see {@link
   * #chunkEmbedFormatterWithPrefix}'s Javadoc for why that distinction matters). Applied to a chunk
   * of a document {@link #storeChunks} determined split into exactly one chunk.
   */
  private static final ContentFormatter CHUNK_EMBED_CONTENT_FORMATTER_NO_PREFIX =
      (document, mode) -> document.getText();

  /**
   * Builds the {@code EMBED}-only formatter for a chunk of a document that split into 2 or more
   * chunks (#933, "Contextual Chunking"): {@code "[<title>]\n\n<chunk text>"}, ignoring every
   * metadata key entirely rather than excluding a known list of them. {@link
   * DefaultContentFormatter}'s exclusion lists are a <em>blacklist</em> under the hood ({@code
   * metadataFilter} does {@code usableMetadataKeys.removeAll(excluded)}) - a metadata key added
   * later without also being added to the exclusion list would silently re-enter the embedding
   * text, exactly the #773 contamination this whitelist exists to prevent. A per-chunk lambda that
   * never reads {@code document.getMetadata()} at all cannot have that failure mode, and sidesteps
   * {@link DefaultContentFormatter}'s unspecified key order for more than one surviving metadata
   * entry.
   *
   * <p>{@code title} is a humanized title (see {@link #storeChunks} for how it is derived and why
   * only multi-chunk documents get one), not the raw {@code file_name}: the raw name regressed a
   * multi-chunk corpus with a generated, structurally-noisy naming scheme (repeated {@code
   * "city-NNNN_"} boilerplate across a document's several chunks pulled unrelated documents'
   * generic chunks together in embedding space) - see PR #940 for the measured before/after.
   *
   * <p>Stored chunk text, citations and the answer-generation prompt are unaffected: {@code
   * PgVectorStore#add} persists {@link org.springframework.ai.document.Document#getText()} verbatim
   * into the {@code content} column, never the formatted variant, and {@code
   * AnswerGenerationService} already carries the file name into its own prompt header through a
   * different channel.
   */
  private static ContentFormatter chunkEmbedFormatterWithPrefix(String title) {
    return (document, mode) -> "[" + title + "]\n\n" + document.getText();
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
   * Lazily resolved (#1183) - mirrors {@code MailDocumentPipeline}'s pre-#1183 {@code
   * ObjectProvider<DocumentPipelineRegistry>}: {@link AttachmentIndexer} itself depends on this
   * class to store an attachment ({@code processUrlFile}), so a direct, eager constructor
   * dependency in both directions would deadlock Spring's bean graph. Resolved once, at the point a
   * pipeline actually reports a {@link DiscoveredAttachment} - the common case (no attachment)
   * never touches it.
   */
  private final ObjectProvider<AttachmentIndexer> attachmentIndexerProvider;

  /** The generalized attachment path's limits for a Mail attachment - see its own Javadoc. */
  private final AttachmentDownloadLimits mailAttachmentLimits;

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
      AttachmentDownloadLimits mailAttachmentLimits) {
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
  }

  public FileProcessingResult processFile(Path file, KnowledgeLibrary targetLibrary)
      throws IOException {
    return processFile(file, targetLibrary, null, null);
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
   *     <p>Deliberately not {@code @Transactional}: {@code uk_documents_library_path} (migration
   *     067) requires the old row's delete to be visible before the new row's insert. Hibernate
   *     orders inserts before deletes within a flush regardless of call order, which would collide
   *     with the constraint the moment both target the same {@code (library_id, file_path)} pair.
   */
  public FileProcessingResult processFile(Path file, KnowledgeLibrary targetLibrary, UUID folderId)
      throws IOException {
    return processFile(file, targetLibrary, folderId, null);
  }

  /**
   * Like {@link #processFile(Path, KnowledgeLibrary, UUID)}, plus {@code attachmentAccess} (#1183):
   * when non-{@code null}, an attachment the routed pipeline discovers while parsing {@code file}
   * (e.g. a Mail attachment, ADR-0022) is indexed as its own {@code Document} row via the
   * generalized attachment path, with {@code parent_document_id} set to this document's own id -
   * {@code null} keeps today's behaviour of discarding any discovered attachment (every existing
   * caller but {@code AsyncIndexingExecutor}).
   *
   * <p><b>Update-in-place, not delete-and-recreate, for a changed existing document</b> (#1183
   * review of #1213: mirrors {@link #processRssEntry}'s own contract) - a delete-and-recreate would
   * fail {@code fk_documents_parent} the moment this row already has attachment children (a Mail
   * file whose attachments were indexed on an earlier run). Only the chunks are exchanged; the
   * row's own id, and therefore every attachment's {@code parent_document_id}, survives unchanged.
   * The quota check compares the size delta against the already-in-place row, not the full new size
   * against a {@code usedBytes} that still includes the old size - see {@link #processRssEntry}'s
   * own Javadoc for why a delete-first quota check does not apply here.
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

    // Check if document already exists in this library (#877: identity is (library_id, file_path),
    // never file_path alone - a document with the same path in a different library is independent).
    Optional<Document> existing =
        documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), filePath);
    Document doc = null;
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
      vectorChunkStore.deleteByDocumentId(existingDoc.getId());
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

    UUID documentId = doc.getId();
    try {
      DocumentPipelineRegistry.Routed routed = pipelineRegistry.routedPipelineFor(file, fileName);
      DocumentPipeline pipeline = routed.pipeline();
      DocumentPipelineResult parsed =
          DocumentPipelineRunner.run(
              pipeline,
              DocumentPipelineSource.ofFile(file, fileName, routed.detectedExtension()),
              discovered ->
                  processDiscoveredAttachments(
                      discovered,
                      documentId,
                      filePath,
                      DocumentSourceType.FILESYSTEM,
                      attachmentAccess));
      switch (parsed.outcome()) {
        case NO_EXTRACTABLE_TEXT -> {
          log.warn("No usable text extracted from {} by pipeline {}", file, pipeline.id());
          return markConnectorRejected(doc.getId());
        }
        case NO_CONTENT -> {
          log.warn("No content extracted from: {}", file);
          return markConnectorFailed(doc.getId());
        }
        case CHUNKED ->
            log.debug(
                "File {} produced {} chunks via pipeline {}",
                fileName,
                parsed.chunks().size(),
                pipeline.id());
      }
      List<org.springframework.ai.document.Document> chunks = parsed.chunks();
      applyContentByteSizeOverride(doc, parsed);

      // Enrich chunks with metadata and store via VectorStore
      storeChunks(
          doc,
          chunks,
          ChunkContextTitle.deriveTitle(fileName),
          pipeline,
          routingExtensionFor(routed));

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
   * origin. Used by both {@code UrlIndexingExecutor} ({@code HTTP_DIRECTORY}, no origin entry - see
   * the six-argument overload above) and {@code RssFeedIndexingExecutor} for an RSS entry's
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
    return processUrlFile(
        localFile,
        originalFileName,
        remoteUrl,
        lastModified,
        remoteFileSize,
        targetLibrary,
        sourceType,
        sourceEntryUrl,
        null);
  }

  /**
   * The attachment-aware counterpart of the eight-argument overload above, generalized (#1182) from
   * an RSS-only implementation: sets {@link Document#getParentDocumentId()} (ADR-0022, Entscheidung
   * 4) so the caller's attachment is a queryable child of {@code parentDocumentId}, used by {@code
   * io.opaa.indexing.source.attachment.AttachmentIndexer} for every source it serves. Discards any
   * {@link DiscoveredAttachment} the routed pipeline reports - see the ten-argument overload below
   * for the variant that indexes them.
   *
   * @param parentDocumentId the row this document is an attachment of, or {@code null} for a
   *     document that is not an attachment (every use of the eight-argument overload above)
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
   * Like the nine-argument overload above, plus {@code attachmentAccess} (#1183): when non-{@code
   * null}, an attachment the routed pipeline discovers while parsing {@code localFile} (e.g. a
   * nested Mail-in-Mail attachment - this document is itself already an attachment, reached via
   * {@link io.opaa.indexing.source.attachment.AttachmentIndexer}) is indexed as its own child of
   * <em>this</em> document, chaining {@code parent_document_id} naturally rather than as a special
   * case. {@code null} keeps the nine-argument overload's behaviour of discarding one.
   *
   * <p><b>Update-in-place, not delete-and-recreate, for a changed existing document</b> (mirrors
   * {@link #processRssEntry}'s own contract, needed the moment an attachment can itself have
   * children - a nested Mail-in-Mail attachment reprocessed here, or an RSS/Confluence attachment
   * once it also grows its own attachments): deleting this row and recreating it under a new id
   * would fail {@code fk_documents_parent} the moment a grandchild attachment still points at the
   * old id. Only the chunks are exchanged; the row's own id survives. The quota check compares the
   * size <em>delta</em> against the already-replaced-in-place row, exactly like {@link
   * #processRssEntry}'s own reasoning - see that method's Javadoc for why a delete-first quota
   * check does not apply here.
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

    // Compute SHA-256 on the downloaded file for content-based deduplication
    String checksum = checksumService.computeSha256(localFile);

    // Check if document already exists in this library by remote URL (#877, see processFile above)
    Optional<Document> existing =
        documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), remoteUrl);
    Document doc = null;
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
      vectorChunkStore.deleteByDocumentId(existingDoc.getId());
      existingDoc.setFileName(fileName);
      existingDoc.setContentType(Files.probeContentType(localFile));
      existingDoc.setFileSize(remoteFileSize);
      existingDoc.setSourceEntryUrl(sourceEntryUrl);
      existingDoc.setParentDocumentId(parentDocumentId);
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
    }
    doc = documentRepository.save(doc);

    UUID documentId = doc.getId();
    try {
      DocumentPipelineRegistry.Routed routed =
          pipelineRegistry.routedPipelineFor(localFile, fileName);
      DocumentPipeline pipeline = routed.pipeline();
      DocumentPipelineResult parsed =
          DocumentPipelineRunner.run(
              pipeline,
              DocumentPipelineSource.ofFile(localFile, fileName, routed.detectedExtension()),
              discovered ->
                  processDiscoveredAttachments(
                      discovered, documentId, remoteUrl, sourceType, attachmentAccess));
      switch (parsed.outcome()) {
        case NO_EXTRACTABLE_TEXT -> {
          log.warn(
              "No usable text extracted from URL document {} by pipeline {}",
              remoteUrl,
              pipeline.id());
          return markConnectorRejected(doc.getId());
        }
        case NO_CONTENT -> {
          log.warn("No content extracted from URL document: {}", remoteUrl);
          return markConnectorFailed(doc.getId());
        }
        case CHUNKED ->
            log.debug(
                "URL file {} produced {} chunks via pipeline {}",
                fileName,
                parsed.chunks().size(),
                pipeline.id());
      }
      List<org.springframework.ai.document.Document> chunks = parsed.chunks();
      applyContentByteSizeOverride(doc, parsed);

      // fileName is always a real file name here regardless of sourceType - both HTTP_DIRECTORY
      // and an RSS_FEED entry's attachment (see this method's own Javadoc) go through this path,
      // so ChunkContextTitle's filesystem-style-name assumption always applies; only
      // processRssEntry's own entry-body document (never routed through processUrlFile) uses a
      // headline instead - see storeChunks's Javadoc for why that distinction is call-site-bound.
      storeChunks(
          doc,
          chunks,
          ChunkContextTitle.deriveTitle(fileName),
          pipeline,
          routingExtensionFor(routed));

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
   * {@code RssFeedIndexingExecutor} has already fetched the entry's detail page and reduced it to
   * its main content before calling this method. This text never goes through {@link
   * SupportedDocumentFormats}' content-based admission at all (there is no file to detect a format
   * from) and is handed straight to {@link DocumentPipelineRegistry#fallbackPipeline()} below,
   * bypassing routing entirely - {@code .html} being admitted since #1059 changes nothing here: an
   * RSS entry's own detail-page text still never reaches {@code HtmlDocumentPipeline}. Only a
   * genuine {@code .html} file - a directory crawl, the filesystem, or an RSS entry's own
   * attachment via {@link #processUrlFile} - is routed there. Content-based deduplication/change
   * detection otherwise mirrors {@link #processUrlFile} exactly: identity by {@code entryUrl} in
   * {@code file_path}, SHA-256 checksum comparison, and {@code publishedAt}/{@code
   * last_modified_remote} recorded for the executor's own change check on the next run.
   */
  public FileProcessingResult processRssEntry(
      String mainText,
      String entryTitle,
      String entryUrl,
      String publishedAt,
      KnowledgeLibrary targetLibrary) {

    boolean hasTitle = entryTitle != null && !entryTitle.isBlank();
    String fileName = hasTitle ? entryTitle : entryUrl;
    // The entry's own body document (unlike an attachment routed through processUrlFile) has no
    // filesystem-style file_name to derive a title from - a headline is free text (used verbatim,
    // never run through ChunkContextTitle's numbering-prefix heuristic), and a URL fallback shares
    // a domain/path prefix across every entry of the same feed, so it gets no prefix at all - see
    // storeChunks's Javadoc for why this is decided per call site, not per DocumentSourceType.
    String contextTitle = hasTitle ? entryTitle : null;
    byte[] contentBytes = mainText.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String checksum = checksumService.computeSha256(contentBytes);

    // #877, see processFile above.
    Optional<Document> existing =
        documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), entryUrl);
    Document doc = null;
    if (existing.isPresent()) {
      Document existingDoc = existing.get();
      if (checksum.equals(existingDoc.getChecksum())
          && existingDoc.getStatus() == DocumentStatus.INDEXED) {
        log.info("Skipping unchanged RSS entry (same checksum): {}", entryUrl);
        metrics.recordSkipped();
        return FileProcessingResult.SKIPPED;
      }
      // Updated in place under the same id, not deleted-and-recreated (ADR-0022, Entscheidung 4):
      // an attachment's parent_document_id points at this row, and deleting it here would fail
      // fk_documents_parent while its attachments still exist. Only the chunks are exchanged; the
      // row's own id, and therefore every attachment's parent link, survives unchanged.
      //
      // Unlike processFile/processUrlFile above, this row is never removed before the quota check
      // below - LibraryStorageQuotaService#wouldExceedQuota's own contract ("call after removing
      // the row being replaced") does not hold here by construction. The check therefore measures
      // the size delta explicitly instead: checking the full new size against a usedBytes that
      // still includes the old size would double-count the entry being replaced (a library near
      // quota could reject a same-size or shrinking update that nets out fine), and deleting the
      // chunks first would leave a chunkless INDEXED row with a stale checksum behind on a
      // QUOTA_EXCEEDED rejection - unlike every other rejection path, nothing here would ever clean
      // that row back up.
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
      vectorChunkStore.deleteByDocumentId(existingDoc.getId());
      existingDoc.setFileName(fileName);
      existingDoc.setContentType("text/html");
      existingDoc.setFileSize((long) contentBytes.length);
      doc = existingDoc;
    } else {
      // See processFile's own comment on why this runs after the existing-document deletion there -
      // there is no existing row to remove on this branch, so the check simply runs before creating
      // the new one.
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

    try {
      // The entry body never was a file, so there is no content to detect a format from - it is
      // already extracted text and goes to the fallback pipeline directly (ADR-0017, decision 2).
      DocumentPipeline pipeline = pipelineRegistry.fallbackPipeline();
      DocumentPipelineResult parsed =
          DocumentPipelineRunner.run(
              pipeline, DocumentPipelineSource.ofExtractedText(mainText, fileName));
      switch (parsed.outcome()) {
        // Before #1056 this path ignored the outcome entirely and left an entry whose text
        // chunked down to nothing as INDEXED with zero chunks - the same silent empty index the
        // file paths already guard against, only reached through a feed instead of a file.
        case NO_EXTRACTABLE_TEXT -> {
          log.warn("No usable text in RSS entry {}", entryUrl);
          return markConnectorRejected(doc.getId());
        }
        case NO_CONTENT -> {
          log.warn("No content extracted from RSS entry: {}", entryUrl);
          return markConnectorFailed(doc.getId());
        }
        case CHUNKED ->
            log.debug("RSS entry {} produced {} chunks", entryUrl, parsed.chunks().size());
      }
      List<org.springframework.ai.document.Document> chunks = parsed.chunks();

      // No routing decision was ever made for this text (see the pipeline selection above) - no
      // routing key is written at all, same as a failed detection (#routingExtensionFor).
      storeChunks(doc, chunks, contextTitle, pipeline, Optional.empty());

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
    processStoredFile(documentId, storedFile, false);
  }

  /**
   * Re-runs the current pipeline over a document whose source file is still on this machine and
   * replaces its chunks - the in-place half of {@code PipelineReindexService#reindexBatch}. The
   * document keeps its own id and row, so citations and deep links into it survive; only its chunks
   * are exchanged.
   *
   * <p><b>Nothing is destroyed before the replacement exists.</b> The old chunks are deleted only
   * once the pipeline has actually produced new ones, immediately before they are written (chunk
   * ids are generated per write, so the old ones would otherwise accumulate alongside the new
   * ones). A document that cannot be re-chunked this time keeps its working chunks and its {@code
   * INDEXED} row and is reported back as not re-indexed, rather than being left permanently
   * chunkless and {@code FAILED} with no path back: unlike a fresh upload, there is an existing,
   * working state here that is worth more than a consistent-looking failure. That holds for both
   * ways the attempt can fail - a pipeline that reports no usable text, and a pipeline that throws
   * (the likelier transient case: a damaged file the reader rejects, a momentarily unreadable
   * file). Only once the previous chunks have actually been deleted does a later failure fall back
   * to the upload path's cleanup, because from that point there is no untouched state left to
   * preserve.
   *
   * @return whether the document was actually re-indexed
   */
  boolean reindexStoredDocument(UUID documentId, Path storedFile) {
    return processStoredFile(documentId, storedFile, true);
  }

  /**
   * The synchronous body shared by {@link #processUploadedFileAsync} and {@link
   * #reindexStoredDocument}: parse, chunk, store and transition a document whose row already exists
   * and whose file is already on disk.
   *
   * @param replacingExistingChunks {@code true} for a re-index of an already-indexed document -
   *     deletes the previous chunks just before the new ones are written, and leaves the document
   *     untouched on every non-{@code CHUNKED} outcome (see {@link #reindexStoredDocument}). {@code
   *     false} for a first upload, where there is no previous state to preserve and every outcome
   *     must reach a terminal status the frontend's polling can key off.
   * @return whether chunks were written and the document transitioned to {@code INDEXED}
   */
  private boolean processStoredFile(
      UUID documentId, Path storedFile, boolean replacingExistingChunks) {
    Document doc = documentRepository.findById(documentId).orElse(null);
    if (doc == null) {
      log.warn(
          "Uploaded document {} no longer exists, skipping asynchronous processing", documentId);
      return false;
    }

    // Whether the previous chunks have already been removed - the point from which this method can
    // no longer leave the document untouched, and therefore the only case in which the catch block
    // below is allowed to clean up on the re-index path (see this method's own contract).
    boolean previousChunksDeleted = false;
    try {
      DocumentPipelineRegistry.Routed routed =
          pipelineRegistry.routedPipelineFor(storedFile, doc.getFileName());
      DocumentPipeline pipeline = routed.pipeline();
      DocumentPipelineResult parsed =
          DocumentPipelineRunner.run(
              pipeline,
              DocumentPipelineSource.ofFile(
                  storedFile, doc.getFileName(), routed.detectedExtension()));
      switch (parsed.outcome()) {
        case NO_EXTRACTABLE_TEXT -> {
          log.warn(
              "No usable text extracted from stored document {} by pipeline {}",
              doc.getFileName(),
              pipeline.id());
          if (replacingExistingChunks) {
            return false;
          }
          // metrics.recordFailed(), not recordSkipped(): every other outcome on this path is
          // INDEXED or FAILED - a single, deliberate upload has no "skipped" concept the way a
          // connector run's item count does.
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
        case CHUNKED ->
            log.debug(
                "Stored file {} produced {} chunks via pipeline {}",
                doc.getFileName(),
                parsed.chunks().size(),
                pipeline.id());
      }
      List<org.springframework.ai.document.Document> chunks = parsed.chunks();

      if (replacingExistingChunks) {
        vectorChunkStore.deleteByDocumentId(doc.getId());
        previousChunksDeleted = true;
      }
      storeChunks(
          doc,
          chunks,
          ChunkContextTitle.deriveTitle(doc.getFileName()),
          pipeline,
          routingExtensionFor(routed));

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
        // The failure happened before anything was destroyed - a damaged file the reader threw on,
        // a transient I/O error. The document still has its working chunks and its INDEXED row, and
        // keeping them is the whole point of the re-index contract: the caller reports this as
        // skipped and the document can be tried again, rather than being left empty and FAILED with
        // no way back.
        metrics.recordFailed();
        return false;
      }
      // Past the delete, or on the upload path where there is no working previous state: whatever
      // failed, storeChunks may already have written chunks for doc.getId() into the vector store -
      // deleting them here mirrors processFile/processUrlFile's own re-index cleanup, so a FAILED
      // row never leaves orphaned chunks still returned by search.
      vectorChunkStore.deleteByDocumentId(doc.getId());
      markUploadFailed(doc.getId(), "Die Datei konnte nicht verarbeitet werden");
      metrics.recordFailed();
      return false;
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
   * #markConnectorIndexed} there are no chunks to clean up on a zero-rows result. Reports {@link
   * FileProcessingResult#FAILED} and counts the document as failed, the same accounting {@link
   * #markConnectorFailedAfterException} gives an uncaught pipeline exception - a document row can
   * be marked {@code FAILED} without ever throwing here, and the caller-facing outcome must not
   * depend on which of the two paths reached it.
   */
  private FileProcessingResult markConnectorFailed(UUID documentId) {
    int updated = documentRepository.markFailed(documentId, null);
    if (updated == 0) {
      log.warn("Document {} was deleted before it could be marked FAILED", documentId);
      metrics.recordSkipped();
      return FileProcessingResult.SKIPPED;
    }
    metrics.recordFailed();
    return FileProcessingResult.FAILED;
  }

  /**
   * The connector counterpart to {@link #markConnectorFailed} for a document that never yields a
   * usable chunk - flagged by {@code io.opaa.indexing.pipeline.TikaFallbackPipeline#isTextlessPdf}
   * or by an empty {@code chunkDocuments} result. Marks {@code FAILED} with {@link
   * DocumentService#NO_EXTRACTABLE_TEXT_MESSAGE} and reports {@link
   * FileProcessingResult#NO_EXTRACTABLE_TEXT}, the same rejection contract {@link
   * FileProcessingResult#QUOTA_EXCEEDED} already has.
   */
  private FileProcessingResult markConnectorRejected(UUID documentId) {
    int updated =
        documentRepository.markFailed(documentId, DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE);
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
   * Turns every {@code discovered} attachment into its own {@code Document} row via the generalized
   * attachment path (ADR-0022) - a no-op when {@code attachmentAccess} is {@code null} (a caller
   * with no run/job context of its own for indexing attachments, e.g. every existing caller of
   * {@link #processFile(Path, KnowledgeLibrary)}/{@link #processUrlFile(Path, String, String,
   * String, long, KnowledgeLibrary)} that predates #1183) or when {@code discovered} is empty
   * (every pipeline but {@code MailDocumentPipeline} today).
   *
   * @param parentDocumentId the row every indexed attachment becomes a child of ({@code
   *     Document#getParentDocumentId()})
   * @param parentFilePath the {@code file_path} of the document {@code discovered} was found on -
   *     every attachment's own {@code file_path} embeds it (ADR-0022, Entscheidung 2, see {@link
   *     #attachmentFilePath})
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
   * The {@code file_path} identity for the {@code index}-th (0-based, extraction order) attachment
   * of the document at {@code parentFilePath} (ADR-0022, Entscheidung 2, decided by this ticket,
   * #1183): embeds the parent's own {@code file_path}, so two identically-named attachments of two
   * different parents never collide ({@code uk_documents_library_path} is scoped to {@code
   * (library_id, file_path)} only, not to the attachment's own name), and the positional index
   * disambiguates two identically-named attachments of the <em>same</em> parent (e.g. two {@code
   * "anlage.pdf"} in different MIME parts of the same mail). {@code "!"} mirrors the JAR-URL
   * convention for "a nested resource inside a file" (e.g. {@code jar:file:a.jar!/b.txt}) - a
   * character vanishingly unlikely to appear in a real filesystem path, unambiguous once read back,
   * and readable as-is in a citation. Recursion (Mail-in-Mail: an attachment that is itself a
   * parent with its own attachments, e.g. a forwarded {@code .eml}) is not a special case: that
   * inner message's own {@code file_path} (already of this same shape) becomes the {@code
   * parentFilePath} its own attachments are built from here, chaining naturally rather than needing
   * distinct handling per nesting level.
   */
  static String attachmentFilePath(String parentFilePath, int index, String fileName) {
    return parentFilePath + "!" + index + "/" + fileName;
  }

  /**
   * Overrides {@code document}'s {@code fileSize} with {@link
   * DocumentPipelineResult#contentByteSizeOverride()} when the pipeline reported one (ADR-0022,
   * Entscheidung 6 - {@code MailDocumentPipeline} only): the raw source file's own size (used for
   * the quota check earlier, before parsing) may include bytes - a Mail attachment's base64 payload
   * - that must not also count toward this document's own quota footprint once that attachment is
   * its own row with its own {@code fileSize}. A no-op, and therefore an extra {@code save}-free
   * call, for every other pipeline.
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
   * routed}, or {@link Optional#empty()} to omit the key entirely - {@link
   * DocumentPipelineRegistry.Routed#formatDetectionFailed()} means the bytes could not be read at
   * all (a transient read failure, not a routing verdict), so nothing is persisted and {@code
   * PipelineReindexService} keeps using the pre-#1126 file-name approximation for this chunk.
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
   * Enriches {@code chunks} with permission-filter/citation metadata and, for a document that split
   * into 2 or more chunks, {@code contextTitle} as a prefix on the embedding input only (#933,
   * "Contextual Chunking") - see {@link #chunkEmbedFormatterWithPrefix} for the prefix contract. A
   * document {@link ChunkingService#chunkDocuments} left as a single chunk gets {@link
   * #CHUNK_EMBED_CONTENT_FORMATTER_NO_PREFIX} instead (byte-identical to the pre-#933 embedding
   * input): a chunk that was never split still carries its full document as its own content, so a
   * prefix would only dilute it, not restore context lost to splitting.
   *
   * <p>The split-count gate below only reads {@code chunks.size()}, the already-final output of
   * {@link ChunkingService#chunkDocuments} - which has no notion of a contextual prefix and only
   * ever sees the parsed document text - so the decision is always made against prefix-free
   * content, never circularly against a token count the prefix itself would change.
   *
   * @param contextTitle the candidate prefix, or {@code null} if this document type should never
   *     get one (e.g. an RSS entry with no feed-supplied title - see {@code processRssEntry}).
   *     Deliberately computed by each caller, not derived here from {@code document}: {@link
   *     DocumentSourceType#RSS_FEED} covers both an RSS entry's own free-text-headline body
   *     document ({@code processRssEntry}) and its filesystem-style-named attachments ({@code
   *     processUrlFile}, routed there by {@code RssFeedIndexingExecutor}) - the title-derivation
   *     rule depends on which of those this chunk set came from, not on {@code document}'s source
   *     type alone (#940 review).
   * @param pipeline the pipeline that produced {@code chunks}; its id and version are written onto
   *     every chunk (see {@link ChunkPipelineMetadata}). Which further chunk metadata keys ride
   *     along is decided by {@link DocumentPipelineRegistry#allPassthroughMetadataKeys()}, not by
   *     {@code pipeline} alone - {@code pipeline} is the outer pipeline a nested attachment's
   *     chunks are attributed to (see {@code MailDocumentPipeline#processAttachment}), so filtering
   *     by its own declaration would silently drop a key only the inner, per-attachment pipeline
   *     declares
   * @param routingExtension see {@link #routingExtensionFor}: written onto every chunk as {@link
   *     ChunkPipelineMetadata#ROUTING_EXTENSION_METADATA_KEY} when present, omitted entirely when
   *     empty (#1126)
   */
  private void storeChunks(
      Document document,
      List<org.springframework.ai.document.Document> chunks,
      String contextTitle,
      DocumentPipeline pipeline,
      Optional<String> routingExtension) {
    boolean documentWasSplit = chunks.size() >= 2;
    ContentFormatter embedFormatter =
        documentWasSplit && contextTitle != null
            ? chunkEmbedFormatterWithPrefix(contextTitle)
            : CHUNK_EMBED_CONTENT_FORMATTER_NO_PREFIX;
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
                  // The routing key actually used (ingestion-pipelines.md, Querschnittsregel (d),
                  // #1126) - what lets a later pipeline-version check compare exactly instead of
                  // re-guessing a document's format from its file name.
                  routingExtension.ifPresent(
                      extension ->
                          metadata.put(
                              ChunkPipelineMetadata.ROUTING_EXTENSION_METADATA_KEY, extension));
                  // The registry-wide declared passthrough keys (DocumentPipeline#
                  // passthroughMetadataKeys) - e.g. the chunk's Fundort, or a message's Kopfdaten
                  // (docs/features/ingestion-pipelines.md, Teil 3, Punkt 5) - copied only when this
                  // particular chunk actually carries them, and never for a key already written
                  // above: those are FileProcessingService's own bookkeeping, not a pipeline's to
                  // set.
                  for (String passthroughKey : passthroughKeys) {
                    if (metadata.containsKey(passthroughKey)) {
                      continue;
                    }
                    copyIfPresent(chunk, metadata, passthroughKey);
                  }
                  org.springframework.ai.document.Document enrichedChunk =
                      new org.springframework.ai.document.Document(chunk.getText(), metadata);
                  enrichedChunk.setContentFormatter(embedFormatter);
                  return enrichedChunk;
                })
            .toList();

    addToVectorStore(enriched);
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
   * Embeds and persists {@code enriched}. At {@code embeddingConcurrency == 1}, a single {@link
   * VectorChunkStore#addChunks} call covers every chunk of this one document, on the calling
   * thread, in document order - the baseline behaviour. That call also writes each chunk's {@code
   * chunk_full_text} row, in the same transaction (#1047, see {@link VectorChunkStore#addChunks}'s
   * own Javadoc) - not a separate step this method has to orchestrate.
   *
   * <p>At {@code embeddingConcurrency > 1}, {@code enriched} is sliced into sub-batches sized by
   * {@link #subBatchSize} - deliberately not {@code opaa.indexing.batchSize} directly, which would
   * leave the concurrent path dead code for ordinary documents under the defaults. {@link
   * #subBatchSize} instead spreads a document's chunks evenly across up to {@code
   * embeddingConcurrency} workers, capped by {@code batchSize} as the per-call upper bound.
   *
   * <p>Every sub-batch is embedded and persisted via its own {@code vectorChunkStore.addChunks}
   * call, submitted to the shared, bounded {@code embeddingExecutor} (see {@link
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
   * RuntimeException} {@code vectorChunkStore.addChunks} would have thrown, so every existing catch
   * block that assumes {@code storeChunks} may throw needs no change. An {@link Error} is rethrown
   * unwrapped too, exactly as a direct {@code vectorChunkStore.addChunks} call would let it
   * propagate. A failing sub-batch does not cancel sibling sub-batches already in flight; whatever
   * they wrote is cleaned up the same way a partially-written single call already could (see {@link
   * #markConnectorFailedAfterException}).
   *
   * <p>{@code embeddingTaskExecutor} is one pool shared by every document currently splitting its
   * chunks across sub-batches - a document with many sub-batches can head-of-line-block a smaller
   * document's sub-batches, with no per-document fairness scheme. Acceptable at the moderate
   * concurrency levels this property targets: a starved document still completes once the pool
   * drains (FIFO queue), it is only delayed.
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
