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
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.observability.IndexingMetrics;
import java.io.IOException;
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

  /** The row's {@code error_message} for a document the pipeline read and found empty. */
  static final String NO_CONTENT_MESSAGE = "Aus der Datei konnte kein Text extrahiert werden";

  /** The row's {@code error_message} for a document that could not be parsed or embedded. */
  static final String PROCESSING_FAILED_MESSAGE = "Die Datei konnte nicht verarbeitet werden";

  /** Text that never was a file has no detectable media type; every text source delivers HTML. */
  private static final String TEXT_CONTENT_TYPE = "text/html";

  /**
   * Makes {@code getFormattedContent(EMBED)} byte-identical to {@code getText()}: no metadata ever
   * reaches the embedding input. Applied to a document {@link #storeChunks} found to be one chunk.
   */
  private static final ContentFormatter CHUNK_EMBED_CONTENT_FORMATTER_NO_PREFIX =
      (document, mode) -> document.getText();

  /**
   * The {@code EMBED}-only formatter for a document that split into 2 or more chunks: the title in
   * brackets, a blank line, then the chunk text (ingestion-pipelines.md, Querschnittsregel (b)).
   * Ignores every metadata key rather than excluding a known list, so a key added later cannot
   * re-enter the embedding input. Stored chunk text and citations are unaffected.
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
   * Lazily resolved: {@link AttachmentIndexer} itself depends on this class to store an attachment,
   * so an eager dependency in both directions would deadlock Spring's bean graph.
   */
  private final ObjectProvider<AttachmentIndexer> attachmentIndexerProvider;

  /** The generalized attachment path's limits for a Mail attachment - see its own Javadoc. */
  private final AttachmentDownloadLimits mailAttachmentLimits;

  /** The core-field extraction between parsing and {@link #storeChunks} (ADR-0024). */
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
    this.documentMetadataService = documentMetadataService;
  }

  /**
   * Takes one document into its library: checksum, find or create the row, run the pipeline,
   * exchange the chunks. A changed document is updated in place under the same id - an attachment's
   * {@code parent_document_id} points at it - and its previous chunks are removed only once the new
   * version has parsed and chunked (ingestion-pipelines.md, "Übergabepunkt"). Every status
   * transition after the row's own save is a conditional {@code UPDATE}, so a row deleted meanwhile
   * is never re-inserted; not {@code @Transactional}, so that row stays visible to the attachment
   * path and to a concurrent delete while chunks are embedded. A non-{@code null} {@code
   * attachmentAccess} turns every discovered attachment into a child {@code Document} (ADR-0022).
   *
   * @return {@code PROCESSED} once the row is {@code INDEXED} with its new chunks; {@code SKIPPED}
   *     for unchanged content and for a row that vanished meanwhile; otherwise the rejection or
   *     failure {@link FileProcessingResult} names
   * @throws IOException when the content cannot be read; an exception out of parsing, embedding or
   *     the final update is rethrown after the row was marked {@code FAILED}
   */
  public FileProcessingResult ingest(DocumentIngest ingest, AttachmentAccess attachmentAccess)
      throws IOException {
    KnowledgeLibrary library = ingest.library();
    String filePath = ingest.filePath();
    String fileName = ingest.fileName();
    String checksum;
    long byteSize;
    switch (ingest.content()) {
      case DocumentIngest.File file -> {
        checksum = checksumService.computeSha256(file.path());
        byteSize = file.byteSize();
      }
      case DocumentIngest.Text text -> {
        byte[] bytes = text.bytes();
        checksum = checksumService.computeSha256(bytes);
        byteSize = bytes.length;
      }
    }

    // Identity is (library_id, file_path), never file_path alone - the same path in a different
    // library is an independent document.
    Optional<Document> existing =
        documentRepository.findByLibraryIdAndFilePath(library.getId(), filePath);
    Selection selection;
    Document doc;
    boolean replacingExistingChunks;
    if (ingest.existingRow()) {
      if (existing.isEmpty()) {
        log.warn("Document {} no longer exists, skipping", filePath);
        metrics.recordSkipped();
        return FileProcessingResult.SKIPPED;
      }
      doc = existing.get();
      replacingExistingChunks = true;
      selection = select(ingest);
    } else if (existing.isPresent()) {
      Document existingDoc = existing.get();
      if (checksum.equals(existingDoc.getChecksum())
          && existingDoc.getStatus() == DocumentStatus.INDEXED) {
        refreshProvenance(existingDoc, ingest, checksum);
        log.info("Skipping unchanged document (same checksum): {}", filePath);
        metrics.recordSkipped();
        return FileProcessingResult.SKIPPED;
      }
      // The row is still present when the quota is checked, so the check measures the size
      // delta: the full new size against a usedBytes that still includes the old size would
      // double-count the document being replaced.
      long previousSize = existingDoc.getFileSize() == null ? 0L : existingDoc.getFileSize();
      if (storageQuotaService.wouldExceedQuota(library.getId(), byteSize - previousSize)) {
        return quotaExceeded(filePath, library);
      }
      selection = select(ingest);
      replacingExistingChunks = true;
      existingDoc.setFileName(fileName);
      existingDoc.setContentType(selection.contentType());
      existingDoc.setFileSize(byteSize);
      doc = documentRepository.save(withProvenance(existingDoc, ingest));
    } else {
      if (storageQuotaService.wouldExceedQuota(library.getId(), byteSize)) {
        return quotaExceeded(filePath, library);
      }
      selection = select(ingest);
      replacingExistingChunks = false;
      Document created =
          new Document(fileName, filePath, selection.contentType(), byteSize, ingest.sourceType());
      created.setLibraryId(library.getId());
      created.setOrganizationId(library.getOrganizationId());
      doc = documentRepository.save(withProvenance(created, ingest));
    }

    Document savedDoc = doc;
    UUID documentId = doc.getId();
    DocumentPipeline pipeline = selection.pipeline();
    boolean preservingPreviousChunks = replacingExistingChunks;
    try {
      DocumentPipelineResult parsed =
          DocumentPipelineRunner.run(
              pipeline,
              selection.source(),
              result -> {
                if (attachmentAccess == null) {
                  // Discovered attachments are discarded and the parent keeps its raw fileSize -
                  // reducing it without indexing the attachments would under-count the quota.
                  return;
                }
                // Before the attachments: their own quota checks must already see the parent's
                // corrected (attachment-free) fileSize, or the attachment bytes count twice.
                applyContentByteSizeOverride(savedDoc, result);
                processDiscoveredAttachments(
                    result.discoveredAttachments(),
                    documentId,
                    savedDoc.getFilePath(),
                    savedDoc.getSourceType(),
                    attachmentAccess);
              });
      if (ingest.reindex() && parsed.outcome() != DocumentPipelineResult.Outcome.CHUNKED) {
        log.warn("Re-index of {} ended {}, keeping it as it is", filePath, parsed.outcome());
        return parsed.outcome() == DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT
            ? FileProcessingResult.NO_EXTRACTABLE_TEXT
            : FileProcessingResult.FAILED;
      }
      switch (parsed.outcome()) {
        case NO_EXTRACTABLE_TEXT -> {
          log.warn("No usable text extracted from {} by pipeline {}", filePath, pipeline.id());
          deletePreviousChunks(replacingExistingChunks, documentId);
          return markConnectorRejected(documentId);
        }
        case NO_CONTENT -> {
          log.warn("No content extracted from {} by pipeline {}", filePath, pipeline.id());
          deletePreviousChunks(replacingExistingChunks, documentId);
          return markConnectorFailed(documentId, true, NO_CONTENT_MESSAGE);
        }
        case PARSE_FAILED -> {
          log.warn("Could not parse {} with pipeline {}", filePath, pipeline.id());
          return markConnectorFailed(documentId, false, PROCESSING_FAILED_MESSAGE);
        }
        case CHUNKED -> log.debug("{} chunked via pipeline {}", filePath, pipeline.id());
      }
      List<org.springframework.ai.document.Document> chunks = parsed.chunks();
      attachSourceContext(chunks, pipeline, ingest.context());
      DocumentChunkMetadata coreMetadata =
          extractCoreMetadata(
              doc, fileName, parsed.withProperties(declaredProperties(parsed, ingest)));

      if (replacingExistingChunks) {
        // Only now, with the new chunks in hand - see this method's own Javadoc.
        vectorChunkStore.deleteByDocumentId(documentId);
        preservingPreviousChunks = false;
      }
      storeChunks(
          doc,
          chunks,
          contextTitleFor(ingest),
          pipeline,
          selection.routingExtension(),
          coreMetadata);

      FileProcessingResult result =
          markConnectorIndexed(documentId, chunks.size(), checksum, ingest.changeMarker());
      if (result == FileProcessingResult.SKIPPED) {
        return result;
      }
    } catch (Exception e) {
      if (!(ingest.reindex() && preservingPreviousChunks)) {
        markConnectorFailedAfterException(documentId, preservingPreviousChunks);
      }
      metrics.recordFailed();
      throw e;
    }

    metrics.recordProcessed();
    return FileProcessingResult.PROCESSED;
  }

  /**
   * {@link #ingest} for an upload whose row is already {@code PENDING}, on a pool of its own so
   * uploads cannot exhaust an indexing run's; there is no caller to rethrow to.
   */
  @Async("uploadTaskExecutor")
  public void processUploadedFileAsync(DocumentIngest ingest, AttachmentAccess attachmentAccess) {
    try {
      ingest(ingest, attachmentAccess);
    } catch (Exception e) {
      log.error("Failed to process uploaded document {}", ingest.fileName(), e);
    }
  }

  /**
   * The pipeline, the source it is handed and what routing learned on the way: a file is routed by
   * its detected content (its media type becomes the row's {@code content_type}), text goes to the
   * fallback pipeline; a {@link DocumentIngest#pipelineId()} names the pipeline directly, and a
   * registry without it is a wiring error.
   */
  private Selection select(DocumentIngest ingest) {
    String fileName = ingest.fileName();
    return switch (ingest.content()) {
      case DocumentIngest.File file -> {
        DocumentPipelineRegistry.Routed routed =
            pipelineRegistry.routedPipelineFor(file.path(), fileName);
        DocumentPipeline pipeline =
            ingest.pipelineId() == null ? routed.pipeline() : pipelineById(ingest.pipelineId());
        yield new Selection(
            pipeline,
            DocumentPipelineSource.ofFile(file.path(), fileName, routed.detectedExtension()),
            routingExtensionFor(routed),
            routed.detectedMediaType());
      }
      case DocumentIngest.Text text -> {
        DocumentPipeline pipeline =
            ingest.pipelineId() == null
                ? pipelineRegistry.fallbackPipeline()
                : pipelineById(ingest.pipelineId());
        yield new Selection(
            pipeline,
            DocumentPipelineSource.ofExtractedText(text.text(), fileName),
            Optional.empty(),
            TEXT_CONTENT_TYPE);
      }
    };
  }

  private record Selection(
      DocumentPipeline pipeline,
      DocumentPipelineSource source,
      Optional<String> routingExtension,
      String contentType) {}

  private DocumentPipeline pipelineById(String id) {
    return pipelineRegistry
        .pipelineById(id)
        .orElseThrow(
            () -> new IllegalStateException("Document pipeline " + id + " not registered"));
  }

  /** Writes the provenance the source declares onto {@code doc}; an absent folder is left alone. */
  private static Document withProvenance(Document doc, DocumentIngest ingest) {
    doc.setSourceEntryUrl(ingest.sourceEntryUrl());
    doc.setParentDocumentId(ingest.parentDocumentId());
    doc.applySourceContext(ingest.context());
    if (ingest.folder() != null) {
      doc.setFolderId(ingest.folder().id());
    }
    return doc;
  }

  private FileProcessingResult quotaExceeded(String filePath, KnowledgeLibrary library) {
    log.warn("Skipping {}: library {} storage quota would be exceeded", filePath, library.getId());
    metrics.recordSkipped();
    return FileProcessingResult.QUOTA_EXCEEDED;
  }

  /**
   * Same content under a new marker, title, place or folder (a title-only edit, a move, a renamed
   * ancestor): the chunks stay, but the provenance moves with the row, so the next run's pre-fetch
   * check skips the document again and every view shows its current title and place.
   */
  private void refreshProvenance(Document existing, DocumentIngest ingest, String checksum) {
    String marker = ingest.changeMarker();
    if (marker != null && !marker.equals(existing.getLastModifiedRemote())) {
      documentRepository.markIndexedFromSource(
          existing.getId(), existing.getChunkCount(), existing.getIndexedAt(), checksum, marker);
    }
    SourceDocumentContext context = ingest.context();
    String containerKey =
        context == null ? existing.getSourceContainerKey() : context.containerKey();
    String hierarchyPath =
        context == null ? existing.getSourceHierarchyPath() : context.hierarchyPath();
    if (!Objects.equals(existing.getFileName(), ingest.fileName())
        || !Objects.equals(existing.getSourceContainerKey(), containerKey)
        || !Objects.equals(existing.getSourceHierarchyPath(), hierarchyPath)) {
      documentRepository.refreshConnectorTitleAndContext(
          existing.getId(), ingest.fileName(), containerKey, hierarchyPath);
    }
    if (ingest.folder() != null && !Objects.equals(existing.getFolderId(), ingest.folder().id())) {
      existing.setFolderId(ingest.folder().id());
      documentRepository.save(existing);
    }
  }

  /**
   * Puts the document's source context onto every chunk of a pipeline that declares the context
   * keys as passthrough - the container and the hierarchy path are not in the body, so the pipeline
   * cannot set them itself, and {@link #storeChunks} keeps declared keys only.
   */
  private static void attachSourceContext(
      List<org.springframework.ai.document.Document> chunks,
      DocumentPipeline pipeline,
      SourceDocumentContext context) {
    if (context == null
        || !pipeline
            .passthroughMetadataKeys()
            .contains(ConfluenceDocumentPipeline.SPACE_METADATA_KEY)) {
      return;
    }
    Map<String, Object> contextKeys = new HashMap<>();
    if (context.containerKey() != null) {
      contextKeys.put(ConfluenceDocumentPipeline.SPACE_METADATA_KEY, context.containerKey());
    }
    if (context.hierarchyPath() != null) {
      contextKeys.put(ConfluenceDocumentPipeline.HIERARCHY_METADATA_KEY, context.hierarchyPath());
    }
    chunks.forEach(chunk -> chunk.getMetadata().putAll(contextKeys));
  }

  /**
   * What the source declares about the document (ADR-0024), laid over what the format declared: a
   * headline or page title, the name being free text, a publication or version instant.
   */
  private static DocumentProperties declaredProperties(
      DocumentPipelineResult parsed, DocumentIngest ingest) {
    DocumentProperties properties = parsed.properties();
    if (ingest.syntheticName()) {
      properties = properties.withSyntheticName(true);
    }
    if (ingest.title() != null) {
      properties = properties.withTitle(ingest.title());
    }
    if (ingest.documentDate() != null) {
      properties = properties.withDocumentDate(ingest.documentDate());
    }
    if (ingest.modifiedAt() != null) {
      properties = properties.withModifiedAt(ingest.modifiedAt());
    }
    return properties;
  }

  /**
   * The chunk-context prefix (ingestion-pipelines.md, Querschnittsregel (b)): a file name is
   * humanized by {@link ChunkContextTitle}; a synthetic name is the declared title verbatim, behind
   * its ancestor titles (root first) where the document has a hierarchy path, and {@code null}
   * without a title - a URL fallback would share a prefix across every document of the same source.
   */
  private static String contextTitleFor(DocumentIngest ingest) {
    if (!ingest.syntheticName()) {
      return ChunkContextTitle.deriveTitle(ingest.fileName());
    }
    String title = ingest.title();
    SourceDocumentContext context = ingest.context();
    if (title == null) {
      return null;
    }
    if (context == null || context.hierarchyPath() == null || context.hierarchyPath().isBlank()) {
      return title;
    }
    return context.hierarchyPath() + SourceDocumentContext.HIERARCHY_SEPARATOR + title;
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

  /**
   * Backs the successful transition to {@code INDEXED} with a conditional {@code UPDATE}: the row
   * can be deleted while {@link #storeChunks} runs, and a plain save would re-insert it as a
   * zombie.
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
   * Backs the {@code FAILED} transition when no content could be extracted - before {@link
   * #storeChunks}, so there are no new chunks to clean up.
   *
   * @param chunksRemoved {@code true} for {@code NO_CONTENT}, whose previous chunks were just
   *     deleted, so {@code chunk_count} has to become {@code 0}; {@code false} for {@code
   *     PARSE_FAILED}, where the previous chunks and count both stand
   * @param errorMessage the German, user-facing reason the row carries
   */
  private FileProcessingResult markConnectorFailed(
      UUID documentId, boolean chunksRemoved, String errorMessage) {
    int updated =
        chunksRemoved
            ? documentRepository.markFailedWithoutChunks(documentId, errorMessage)
            : documentRepository.markFailed(documentId, errorMessage);
    if (updated == 0) {
      log.warn("Document {} was deleted before it could be marked FAILED", documentId);
      metrics.recordSkipped();
      return FileProcessingResult.SKIPPED;
    }
    metrics.recordFailed();
    return FileProcessingResult.FAILED;
  }

  /**
   * The {@code FAILED} transition for a document without a usable chunk: marked with {@link
   * DocumentService#NO_EXTRACTABLE_TEXT_MESSAGE}, always with {@code chunk_count = 0}.
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
   * The catch-block counterpart to {@link #markConnectorFailed}: deletes this document's chunks
   * rather than tracking whether {@link #storeChunks} was reached, in its own {@code try/catch} so
   * a store outage neither swallows the original failure nor skips the {@code FAILED} mark.
   *
   * @param preservingPreviousChunks {@code true} while a document being replaced still has its
   *     previous, working chunks and nothing new has been written - those stay, with the count
   *     describing them; otherwise the chunks go and the row is left at {@code chunk_count = 0}
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
            ? documentRepository.markFailed(documentId, PROCESSING_FAILED_MESSAGE)
            : documentRepository.markFailedWithoutChunks(documentId, PROCESSING_FAILED_MESSAGE);
    if (updated == 0) {
      log.warn("Document {} was deleted before it could be marked FAILED", documentId);
    }
  }

  /**
   * Turns every {@code discovered} attachment into its own {@code Document} row, a child of {@code
   * parentDocumentId}, via the generalized attachment path (ADR-0022); every attachment's own
   * {@code file_path} embeds {@code parentFilePath} (see {@link AttachmentFilePath}).
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
              AttachmentFilePath.of(parentFilePath, i, attachment.fileName())));
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
   * Overrides {@code document}'s {@code fileSize} with {@link
   * DocumentPipelineResult#contentByteSizeOverride()} when the pipeline reported one (ADR-0022,
   * Entscheidung 6): a Mail attachment's base64 payload must not count toward the parent's quota
   * footprint once the attachment is its own row. A no-op, and {@code save}-free, otherwise.
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
   * The value to persist as {@link ChunkPipelineMetadata#ROUTING_EXTENSION_METADATA_KEY}, or {@link
   * Optional#empty()} to omit the key: a failed format detection is a transient read failure, not a
   * routing verdict.
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
   * Enriches {@code chunks} with permission-filter and citation metadata and, for a document that
   * split into 2 or more chunks, prefixes {@code contextTitle} onto the embedding input only (see
   * {@link #chunkEmbedFormatterWithPrefix}). A single chunk gets {@link
   * #CHUNK_EMBED_CONTENT_FORMATTER_NO_PREFIX}, since it already carries its whole document.
   *
   * @param contextTitle the candidate prefix, or {@code null} if this document never gets one (see
   *     {@link #contextTitleFor})
   * @param pipeline the pipeline that produced {@code chunks}; its id and version go onto every
   *     chunk. Which further keys ride along is decided by {@link
   *     DocumentPipelineRegistry#allPassthroughMetadataKeys()}, not by {@code pipeline} alone - a
   *     nested attachment's chunks are attributed to the outer pipeline, so filtering by its own
   *     declaration would drop a key only the inner pipeline declares
   * @param routingExtension see {@link #routingExtensionFor}: written when present, omitted when
   *     empty
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
                  enrichedChunk.setContentFormatter(embedFormatter);
                  return enrichedChunk;
                })
            .toList();

    addToVectorStore(enriched);
  }

  /**
   * The deterministic core-field extraction (ADR-0024) for a document about to get chunks: stores
   * the values at the document and returns the effective fields for {@link #storeChunks}. Never
   * fails the ingest - a failure is logged and the chunks are written without core fields.
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
   * shared {@code embeddingTaskExecutor}, all awaited before returning. The order sub-batches reach
   * the store is not fixed, which is why the evaluation harnesses pin the concurrency to 1.
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
   * The sub-batch size for {@code chunkCount} chunks: spread as evenly as possible across up to
   * {@code embeddingConcurrency} workers, capped at {@code opaa.indexing.batchSize}, never 0.
   */
  private int subBatchSize(int chunkCount) {
    int perWorker = (int) Math.ceil((double) chunkCount / embeddingConcurrency);
    return Math.max(1, Math.min(embeddingBatchSize, perWorker));
  }
}
