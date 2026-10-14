package io.opaa.library;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentStatus;
import io.opaa.asset.AssetRepository;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.ServiceUnavailableException;
import io.opaa.common.ValidationException;
import io.opaa.connection.profile.ConnectorLockService;
import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.attachment.AttachmentProperties;
import io.opaa.indexing.attachment.StandaloneAttachmentAccess;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.credentials.SourceConnectionResolver;
import io.opaa.indexing.document.AttachmentExtractor;
import io.opaa.indexing.document.AttachmentFilePath;
import io.opaa.indexing.document.ChecksumService;
import io.opaa.indexing.document.DocumentIngest;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.registry.SourceConnectorRegistry;
import io.opaa.indexing.spi.OriginalAccess;
import io.opaa.indexing.spi.OriginalUnavailableException;
import io.opaa.indexing.spi.ServedOriginals;
import io.opaa.indexing.spi.SourceConnectionBlockedException;
import io.opaa.indexing.spi.SourceCredentialsException;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.FolderDocumentDeleter;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.knowledge.LibraryFolder;
import io.opaa.knowledge.LibraryFolderRepository;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.knowledge.ServedContentTypes;
import io.opaa.knowledge.UploadProperties;
import io.opaa.knowledge.UploadedOriginalRef;
import io.opaa.knowledge.UploadedOriginalStore;
import io.opaa.sourceaccess.BoundedStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploads documents into, and removes them from, a {@link KnowledgeLibrary}; both need {@link
 * AssetRole#EDITOR}. An upload stores the file, creates the {@code PENDING} row and hands parsing
 * and embedding to {@link DocumentIngestService#processUploadedFileAsync}; the caller polls for the
 * outcome. The original file name never reaches the storage, and {@link #deleteDocument} deletes
 * the row before the chunks and only ever an original this application itself stored.
 */
@Service
public class LibraryDocumentService implements FolderDocumentDeleter {

  private static final Logger log = LoggerFactory.getLogger(LibraryDocumentService.class);

  /** The {@code code} of the 404 for a library being erased; only a reader of it sees it. */
  public static final String LIBRARY_BEING_ERASED = "LIBRARY_BEING_ERASED";

  /** The 503 a reader sees when the core cannot sign in; account and endpoint stay in the log. */
  static final String ORIGINAL_SIGN_IN_FAILED =
      "Das Original ist derzeit nicht abrufbar. Bitte später erneut versuchen.";

  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService accessService;
  private final DocumentRepository documentRepository;
  private final ChecksumService checksumService;
  private final DocumentIngestService documentIngestService;
  private final VectorChunkStore vectorChunkStore;
  private final UploadProperties uploadProperties;
  private final UploadedOriginalStore uploadedOriginalStore;
  private final LibraryStorageQuotaService storageQuotaService;
  private final LibraryFolderRepository folderRepository;
  private final LibraryFolderService folderService;
  private final AttachmentExtractor attachmentExtractor;
  private final AttachmentProperties attachmentProperties;
  private final AttachmentExtractionLimiter attachmentExtractionLimiter;
  private final SupportedDocumentFormats supportedFormats;
  private final SourceConnectorRegistry connectors;
  private final AssetRepository assetRepository;
  private final SourceConnectionResolver connectionResolver;
  private final SourceSyncStateRepository syncStateRepository;

  /** One transaction per document for {@link #deleteDocuments} - see its contract. */
  private final TransactionTemplate transactionTemplate;

  private final Clock clock;

  public LibraryDocumentService(
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService accessService,
      DocumentRepository documentRepository,
      ChecksumService checksumService,
      DocumentIngestService documentIngestService,
      VectorChunkStore vectorChunkStore,
      UploadProperties uploadProperties,
      UploadedOriginalStore uploadedOriginalStore,
      LibraryStorageQuotaService storageQuotaService,
      LibraryFolderRepository folderRepository,
      LibraryFolderService folderService,
      AttachmentExtractor attachmentExtractor,
      AttachmentProperties attachmentProperties,
      AttachmentExtractionLimiter attachmentExtractionLimiter,
      SupportedDocumentFormats supportedFormats,
      SourceConnectorRegistry connectors,
      AssetRepository assetRepository,
      PlatformTransactionManager transactionManager,
      SourceConnectionResolver connectionResolver,
      SourceSyncStateRepository syncStateRepository,
      Clock clock) {
    this.libraryRepository = libraryRepository;
    this.accessService = accessService;
    this.documentRepository = documentRepository;
    this.checksumService = checksumService;
    this.documentIngestService = documentIngestService;
    this.vectorChunkStore = vectorChunkStore;
    this.uploadProperties = uploadProperties;
    this.uploadedOriginalStore = uploadedOriginalStore;
    this.storageQuotaService = storageQuotaService;
    this.folderRepository = folderRepository;
    this.folderService = folderService;
    this.attachmentExtractor = attachmentExtractor;
    this.attachmentProperties = attachmentProperties;
    this.attachmentExtractionLimiter = attachmentExtractionLimiter;
    this.supportedFormats = supportedFormats;
    this.connectors = connectors;
    this.assetRepository = assetRepository;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.connectionResolver = connectionResolver;
    this.syncStateRepository = syncStateRepository;
    this.clock = clock;
  }

  /**
   * The signature for every caller that never needs a {@code folderPath} - delegates to the full
   * overload below with {@code folderPath = null}.
   */
  public LibraryDocumentEntry uploadDocument(
      UUID libraryId, MultipartFile file, UUID folderId, CurrentUser caller) {
    return uploadDocument(libraryId, file, folderId, null, caller);
  }

  /**
   * {@code folderPath} is an optional path below {@code folderId} (or the root), e.g. {@code
   * "Protokolle/2026"}; its folders are created idempotently, so a dropped directory tree uploaded
   * file by file ends up under one shared folder chain.
   */
  public LibraryDocumentEntry uploadDocument(
      UUID libraryId, MultipartFile file, UUID folderId, String folderPath, CurrentUser caller) {
    UUID currentUserId = caller.id();
    KnowledgeLibrary library =
        libraryRepository.requireInOrganization(libraryId, caller.organizationId());
    requireEditable(library, currentUserId, caller.isSystemAdmin());
    requireUploadLibrary(library);

    // Validated before any byte is written to disk, mirroring every other "reject this request
    // outright" check below - a folderId that does not exist (or belongs to another library,
    // treated identically per resolveFolder) must leave the bestand exactly as it was.
    if (folderId != null) {
      resolveFolder(libraryId, folderId);
    }

    if (file == null || file.isEmpty()) {
      throw new ValidationException("Datei ist erforderlich");
    }
    if (file.getSize() > uploadProperties.maxFileSize()) {
      throw new PayloadTooLargeException(
          "Die Datei ist zu groß. Erlaubt sind höchstens "
              + (uploadProperties.maxFileSize() / (1024 * 1024))
              + " MB");
    }
    // Checked before anything is written; a FAILED row this upload may replace is not netted out.
    if (storageQuotaService.wouldExceedQuota(libraryId, file.getSize())) {
      throw new PayloadTooLargeException(storageQuotaService.quotaExceededMessage(libraryId));
    }

    String displayFileName = sanitizeDisplayFileName(file.getOriginalFilename());
    if (!supportedFormats.isSupported(displayFileName)) {
      throw new ValidationException(
          "Das Dateiformat wird nicht unterstützt. Erlaubt sind: "
              + String.join(", ", supportedFormats.extensions()));
    }
    String extension = matchedExtension(displayFileName);

    // Created only after every certain-to-reject check above: resolveOrCreateFolderPath commits its
    // folder chain on return, so a rejected upload must not reach it.
    UUID effectiveFolderId = folderId;
    List<String> pathSegments = splitFolderPath(folderPath);
    if (!pathSegments.isEmpty()) {
      effectiveFolderId =
          folderService.resolveOrCreateFolderPath(libraryId, folderId, pathSegments, caller);
    }

    UploadedOriginalStore.AcceptedUpload accepted =
        acceptUpload(library.getOrganizationId(), libraryId, extension, file);
    // The working file the rest of this upload reads - the same file the asynchronous processing
    // gets, and the one it releases when it is done (ADR-0030, Entscheidung 2). It only becomes the
    // library's original at accepted.store() below, after the checks that still reject an upload.
    Path storedFile = accepted.workingFile();

    // Assigned as the last statement of the try below, and read only after it: once the upload has
    // been handed to the asynchronous task, nothing may run inside a block whose catch discards the
    // original the task is parsing (it would leave the committed row pointing at a dead file_path).
    Document storedRow;
    try {
      requireContentMatchesExtension(storedFile, extension);

      String checksum = checksumService.computeSha256(storedFile);
      // Dedup is scoped per library: the same file uploaded into two different libraries is two
      // legitimate documents, only a second copy inside the *same* library is rejected.
      Optional<Document> existing =
          documentRepository.findByLibraryIdAndChecksumAndParentDocumentIdIsNull(
              libraryId, checksum);
      if (existing.isPresent()) {
        Document existingDoc = existing.get();
        if (existingDoc.getStatus() != DocumentStatus.FAILED) {
          accepted.discard();
          throw new ConflictException("Diese Datei ist bereits in dieser Bibliothek vorhanden");
        }
        // A retry replaces a FAILED row together with its chunks, its original and its attachment
        // children (ADR-0022, Entscheidung 3); the children go first (fk_documents_parent).
        Optional<UploadedOriginalRef> oldFailedOriginal = UploadedOriginalRef.of(existingDoc);
        for (Document descendant : descendantsDeepestFirst(existingDoc.getId())) {
          vectorChunkStore.deleteByDocumentId(descendant.getId());
          documentRepository.delete(descendant);
        }
        vectorChunkStore.deleteByDocumentId(existingDoc.getId());
        documentRepository.delete(existingDoc);
        oldFailedOriginal.ifPresent(uploadedOriginalStore::delete);
      }

      // The row is created - and returned - as PENDING here, before any parsing/embedding has
      // happened. contentType/fileSize are read from the file this class itself just wrote; the
      // connector paths store the canonical type of the routed format instead.
      String contentType = Files.probeContentType(storedFile);
      long fileSize = Files.size(storedFile);
      Document document =
          new Document(
              displayFileName,
              accepted.store().locator(),
              contentType,
              fileSize,
              library.getSourceType());
      document.setLibraryId(libraryId);
      document.setOrganizationId(library.getOrganizationId());
      document.setUploadedByUserId(currentUserId);
      document.setFolderId(effectiveFolderId);
      // Set on this first (and only synchronous) save: this is where a concurrent duplicate upload
      // race against uk_documents_library_checksum is meant to be settled - before any embedding
      // work starts, not after, even though the embedding work itself runs off this thread.
      document.setChecksum(checksum);
      document = documentRepository.save(document);

      // The row is committed: a failure from here on marks it FAILED instead of reaching the outer
      // catch, which would leave the row pointing at a deleted file.
      try {
        // Parsing/chunking/embedding run on uploadTaskExecutor from here - this method returns the
        // PENDING row without waiting for that to finish.
        documentIngestService.processUploadedFileAsync(
            DocumentIngest.builder(library)
                .file(storedFile, fileSize)
                .filePath(document.getFilePath())
                .fileName(displayFileName)
                .sourceType(library.getSourceType())
                .existingRow()
                .build(),
            new StandaloneAttachmentAccess(library, "Upload"),
            accepted::release);
      } catch (TaskRejectedException e) {
        // uploadTaskExecutor rejects instead of discarding when full; a row left PENDING would be
        // polled forever for a job nothing runs.
        log.warn(
            "Upload processing queue is full; marking document {} as FAILED immediately",
            document.getId(),
            e);
        return failAlreadyPersistedUpload(
            document,
            accepted,
            "Die Verarbeitung ist derzeit ausgelastet - bitte später erneut versuchen.");
      } catch (RuntimeException e) {
        log.error(
            "Failed to start asynchronous processing for uploaded document {}",
            document.getId(),
            e);
        return failAlreadyPersistedUpload(
            document, accepted, "Die Verarbeitung konnte nicht gestartet werden");
      }

      storedRow = document;
    } catch (DataIntegrityViolationException e) {
      // fk_documents_folder: the folder was deleted concurrently after resolveFolder above.
      accepted.discard();
      if (isFolderForeignKeyViolation(e)) {
        throw new NotFoundException("Der Ordner wurde inzwischen gelöscht");
      }
      // uk_documents_library_checksum settles a concurrent duplicate upload; any other violation
      // gets the same neutral 409.
      throw new ConflictException("Diese Datei ist bereits in dieser Bibliothek vorhanden");
    } catch (IOException e) {
      accepted.discard();
      throw new UncheckedIOException("Datei konnte nicht gespeichert werden", e);
    } catch (RuntimeException e) {
      // Only reachable before the row is committed (validation, file I/O, the dedup check above) -
      // everything from documentRepository.save(document) onward has its own inner try/catch that
      // never lets a RuntimeException escape to here.
      accepted.discard();
      throw e;
    }
    markUploadAsContentChange(libraryId);
    return new LibraryDocumentEntry(
        storedRow, LibraryFolderPaths.pathOf(folderRepository, storedRow.getFolderId()));
  }

  /**
   * The catalog's "last change" of the library. Bookkeeping only: the row is committed and its
   * processing started, so a failure here is logged and never fails the upload.
   */
  private void markUploadAsContentChange(UUID libraryId) {
    try {
      assetRepository.markContentChanged(libraryId, Instant.now(clock));
    } catch (RuntimeException e) {
      log.warn("Could not mark library {} as changed after an upload", libraryId, e);
    }
  }

  /**
   * The uploaded bytes, taken into the store as this library's next original-to-be. Nothing of a
   * failed attempt survives - including one that only fails while closing the multipart stream,
   * after the bytes were already taken.
   */
  private UploadedOriginalStore.AcceptedUpload acceptUpload(
      UUID organizationId, UUID libraryId, String extension, MultipartFile file) {
    UploadedOriginalStore.AcceptedUpload accepted = null;
    try (InputStream in = file.getInputStream()) {
      accepted = uploadedOriginalStore.accept(organizationId, libraryId, extension, in);
      return accepted;
    } catch (IOException e) {
      if (accepted != null) {
        accepted.discard();
      }
      throw new UncheckedIOException("Datei konnte nicht gespeichert werden", e);
    }
  }

  /**
   * Marks a committed upload row {@code FAILED} and discards its file. The conditional {@link
   * DocumentRepository#markFailed} cannot re-insert a row a concurrent delete already removed;
   * {@code document} only builds the response.
   */
  private LibraryDocumentEntry failAlreadyPersistedUpload(
      Document document, UploadedOriginalStore.AcceptedUpload accepted, String errorMessage) {
    int updated = documentRepository.markFailed(document.getId(), errorMessage);
    if (updated == 0) {
      log.warn(
          "Document {} was deleted before it could be marked FAILED after an upload processing"
              + " error",
          document.getId());
    }
    document.setStatus(DocumentStatus.FAILED);
    document.setErrorMessage(errorMessage);
    accepted.discard();
    return new LibraryDocumentEntry(
        document, LibraryFolderPaths.pathOf(folderRepository, document.getFolderId()));
  }

  /**
   * The original behind {@code documentId} for streaming. Unknown, foreign and missing documents
   * answer the same {@code 404}; reading needs {@link AssetRole#VIEWER} through {@link
   * LibraryAccessService#requireContentRead}, without an administrative floor. The connector's
   * {@link OriginalAccess} resolves the original; an attachment is re-extracted from its parent.
   */
  public DocumentContent loadContent(UUID documentId, CurrentUser caller) {
    Document document =
        documentRepository
            .findById(documentId)
            .orElseThrow(() -> new NotFoundException("Dokument nicht gefunden"));

    KnowledgeLibrary library =
        libraryRepository
            .findByIdAndOrganizationId(document.getLibraryId(), caller.organizationId())
            // another person's private library does not exist for the caller, nor its documents
            .filter(lib -> !lib.isOwnerOnly() || caller.id().equals(lib.getOwnerUserId()))
            .orElseThrow(() -> new NotFoundException("Dokument nicht gefunden"));
    accessService.requireContentRead(library, caller.id(), caller.isSystemAdmin());
    if (library.isErasureRequested()) {
      // its originals may already be gone while the erasure waits or resumes
      throw new NotFoundException("Die Bibliothek wird gelöscht", LIBRARY_BEING_ERASED);
    }

    if (isReExtractableAttachment(document)) {
      return loadAttachmentContent(document, library);
    }
    return loadOriginal(document, library);
  }

  /**
   * Whether {@code document}'s bytes exist only inside its parent's original (ADR-0022): its {@code
   * file_path} embeds the parent's path plus an extraction index. A downloaded attachment has an
   * original of its own and is served by {@link #loadOriginal}.
   */
  private boolean isReExtractableAttachment(Document document) {
    if (document.getParentDocumentId() == null) {
      return false;
    }
    return documentRepository
        .findById(document.getParentDocumentId())
        .map(
            parent -> AttachmentFilePath.indexIn(parent.getFilePath(), document.getFilePath()) >= 0)
        .orElse(false);
  }

  /**
   * The original from the connector's {@link OriginalAccess}; access was checked by the caller. A
   * missing original is the German 404, an unreachable storage a {@code 503} with the connector's
   * message (ADR-0030, Entscheidung 9); the storage's own sentence stays in the log.
   */
  private DocumentContent loadOriginal(Document document, KnowledgeLibrary library) {
    OriginalAccess access =
        connectors
            .originalAccess(document.getSourceType())
            .orElseThrow(LibraryDocumentService::noOriginalAvailable);
    try {
      return access
          .openOriginal(document, library, connectionResolver.resolve(library))
          .orElseThrow(LibraryDocumentService::noOriginalAvailable);
    } catch (OriginalUnavailableException e) {
      log.warn(
          "Original of document {} is not readable right now: {}",
          document.getId(),
          library
              .loggedNames()
              .of(e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
      throw new ServiceUnavailableException(e.userMessage());
    } catch (SourceConnectionBlockedException e) {
      if (e.block().locked()) {
        // a lock lasts until the administration lifts it - not a temporary unavailability
        throw new ConflictException(e.getMessage(), ConnectorLockService.SOURCE_LOCKED);
      }
      throw new ServiceUnavailableException(e.getMessage());
    } catch (SourceCredentialsException e) {
      log.warn(
          "Original of document {} has no usable secret: {}",
          document.getId(),
          library.loggedNames().of(e.getMessage()));
      throw new ServiceUnavailableException(ORIGINAL_SIGN_IN_FAILED);
    }
  }

  private static NotFoundException noOriginalAvailable() {
    return new NotFoundException("Für dieses Dokument steht kein Originaldokument zur Verfügung");
  }

  /**
   * Re-extracts an attachment (ADR-0022) from its root ancestor's original along each level's
   * extraction index. A name mismatch, a broken or foreign chain, or one deeper than {@link
   * #maxAttachmentChainDepth()} is a 404. Runs under {@link AttachmentExtractionLimiter} (429 at
   * the cap); temp files are deleted when the stream closes or on failure.
   */
  private DocumentContent loadAttachmentContent(Document document, KnowledgeLibrary library) {
    List<Document> chain = new ArrayList<>();
    Document current = document;
    while (current.getParentDocumentId() != null) {
      if (chain.size() >= maxAttachmentChainDepth()) {
        log.warn("Attachment document {} has an over-deep parent chain", document.getId());
        throw noOriginalAvailable();
      }
      Document parent = documentRepository.findById(current.getParentDocumentId()).orElse(null);
      if (parent == null) {
        log.warn(
            "Attachment document {} has a broken parent chain at {}",
            document.getId(),
            current.getParentDocumentId());
        throw noOriginalAvailable();
      }
      if (AttachmentFilePath.indexIn(parent.getFilePath(), current.getFilePath()) < 0) {
        // current has a source identity of its own (a downloaded .eml, itself an attachment of an
        // RSS entry) - it is the root to fetch, its own parent is not part of the extraction.
        break;
      }
      if (!library.getId().equals(parent.getLibraryId())) {
        log.warn(
            "Attachment document {} has an ancestor in another library ({})",
            document.getId(),
            parent.getId());
        throw noOriginalAvailable();
      }
      chain.add(current);
      current = parent;
    }
    Document root = current;

    // Root-first extraction indices: chain is attachment-first, so it is walked backwards here.
    List<Integer> indices = new ArrayList<>(chain.size());
    String parentPath = root.getFilePath();
    for (int i = chain.size() - 1; i >= 0; i--) {
      int index = AttachmentFilePath.indexIn(parentPath, chain.get(i).getFilePath());
      if (index < 0) {
        log.warn(
            "Attachment document {} has a file_path that does not embed its parent's path {}",
            document.getId(),
            library.loggedNames().of(parentPath));
        throw noOriginalAvailable();
      }
      indices.add(index);
      parentPath = chain.get(i).getFilePath();
    }

    return attachmentExtractionLimiter.runExtraction(
        root.getId(), () -> extractFromRoot(document, library, root, chain, indices));
  }

  /**
   * The actual re-extraction of {@code document} out of {@code root}'s original, following {@code
   * indices} down {@code chain}. Runs under {@link AttachmentExtractionLimiter} - it is the part
   * that parses (and, for a connector Bestand, downloads) the parent original and writes temp
   * files, everything before it is repository lookups.
   */
  private DocumentContent extractFromRoot(
      Document document,
      KnowledgeLibrary library,
      Document root,
      List<Document> chain,
      List<Integer> indices) {
    DocumentContent rootContent = loadOriginal(root, library);
    List<Path> tempFiles = new ArrayList<>();
    boolean streaming = false;
    try {
      Path currentFile =
          rootContent.isStreamed()
              ? bufferToTempFile(rootContent, bufferBoundFor(root))
              : rootContent.path();
      if (rootContent.isStreamed()) {
        tempFiles.add(currentFile);
      }
      String currentName = root.getFileName();
      for (int i = 0; i < indices.size(); i++) {
        Document expected = chain.get(chain.size() - 1 - i);
        AttachmentExtractor.Extracted extracted =
            attachmentExtractor.extract(
                currentFile, currentName, indices.get(i), library.loggedNames());
        if (extracted == null) {
          log.info(
              "Attachment document {}: no attachment at index {} of {} anymore",
              document.getId(),
              indices.get(i),
              library.loggedNames().of(currentName));
          throw noOriginalAvailable();
        }
        tempFiles.add(extracted.file());
        if (!expected.getFileName().equals(extracted.fileName())) {
          log.info(
              "Attachment document {}: index {} of {} now holds a different attachment ({})",
              document.getId(),
              indices.get(i),
              library.loggedNames().of(currentName),
              library.loggedNames().of(extracted.fileName()));
          throw noOriginalAvailable();
        }
        currentFile = extracted.file();
        currentName = extracted.fileName();
      }

      InputStream stream =
          ServedOriginals.deletingOnClose(Files.newInputStream(currentFile), tempFiles);
      streaming = true;
      return DocumentContent.ofStream(
          stream,
          document.getFileName(),
          ServedContentTypes.forFile(document.getContentType(), currentFile));
    } catch (IOException e) {
      log.warn(
          "Attachment document {} could not be re-extracted",
          document.getId(),
          library.loggedNames().of(e));
      throw noOriginalAvailable();
    } finally {
      closeQuietly(rootContent);
      if (!streaming) {
        tempFiles.forEach(this::deleteQuietly);
      }
    }
  }

  /**
   * The chain length {@link #loadAttachmentContent} still follows: the nesting depth the indexing
   * path itself may produce ({@code opaa.indexing.attachments.max-depth}) plus one level of
   * headroom, so raising that property never makes an indexable attachment unopenable, while a
   * corrupt or cyclic chain is still cut off after a bounded number of steps.
   */
  private int maxAttachmentChainDepth() {
    return attachmentProperties.maxDepth() + 1;
  }

  /**
   * The ceiling {@link #bufferToTempFile} applies to a streamed root: the bound the root's own
   * origin already had to pass, so a root that was legitimately indexed can always be buffered
   * again - taking one storage's bound for another's content would make an attachment inside a
   * large object unopenable as soon as an operator sets the two differently. The upload file-size
   * limit applies where the connector names none.
   */
  private long bufferBoundFor(Document root) {
    return connectors
        .originalAccess(root.getSourceType())
        .map(OriginalAccess::streamedOriginalBound)
        .orElse(OptionalLong.empty())
        .orElse(uploadProperties.maxFileSize());
  }

  /**
   * Copies a streamed root original into a temp file so the pipeline can re-read it, capped at
   * {@code maxBytes} ({@link #bufferBoundFor}) - an original swapped at its source for a larger one
   * cannot fill the temp directory.
   */
  private Path bufferToTempFile(DocumentContent content, long maxBytes) throws IOException {
    Path temp = Files.createTempFile("opaa-attachment-parent-", ".tmp");
    try (InputStream in = BoundedStreams.input(content.stream(), maxBytes)) {
      Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException | RuntimeException e) {
      deleteQuietly(temp);
      throw e;
    }
    return temp;
  }

  private void closeQuietly(DocumentContent content) {
    if (content.isStreamed()) {
      try {
        content.stream().close();
      } catch (IOException e) {
        log.debug("Failed to close the proxied parent stream", e);
      }
    }
  }

  /**
   * Deletes each of {@code documentIds} in its own transaction, so a failing id costs only its own
   * entry in the answer. Duplicates count once, in first-requested order. Only an upload library is
   * accepted: a connector library would bring the documents back (ADR-0018, Entscheidung 1).
   */
  public BulkDocumentDeletion deleteDocuments(
      UUID libraryId, List<UUID> documentIds, CurrentUser caller) {
    KnowledgeLibrary library =
        libraryRepository.requireInOrganization(libraryId, caller.organizationId());
    requireEditable(library, caller.id(), caller.isSystemAdmin());
    requireUploadLibrary(library);

    List<UUID> deleted = new ArrayList<>();
    List<BulkDocumentDeletion.Failure> failures = new ArrayList<>();
    for (UUID documentId : new LinkedHashSet<>(documentIds)) {
      try {
        transactionTemplate.executeWithoutResult(
            status -> deleteDocument(libraryId, documentId, caller));
        deleted.add(documentId);
      } catch (NotFoundException e) {
        failures.add(new BulkDocumentDeletion.Failure(documentId, e.getMessage()));
      } catch (RuntimeException e) {
        // The remaining ids must still be attempted; the caller is told which one stayed and why,
        // without the internal cause the German message deliberately omits.
        log.error("Bulk delete of document {} in library {} failed", documentId, libraryId, e);
        failures.add(
            new BulkDocumentDeletion.Failure(
                documentId, "Dokument konnte nicht gelöscht werden — bitte erneut versuchen"));
      }
    }
    return new BulkDocumentDeletion(List.copyOf(deleted), List.copyOf(failures));
  }

  @Override
  @Transactional
  public void deleteDocument(UUID libraryId, UUID documentId, CurrentUser caller) {
    KnowledgeLibrary library =
        libraryRepository.requireInOrganization(libraryId, caller.organizationId());
    requireEditable(library, caller.id(), caller.isSystemAdmin());

    Document document =
        documentRepository
            .findById(documentId)
            .orElseThrow(() -> new NotFoundException("Dokument nicht gefunden"));
    // Treats a document from another library the same as one that does not exist at all - the same
    // reasoning KnowledgeLibraryRepository#requireInOrganization applies across organizations.
    if (!document.getLibraryId().equals(libraryId)) {
      throw new NotFoundException("Dokument nicht gefunden");
    }

    // Read while the row is still there; whether it names an original of this library is decided
    // by the delete itself, after the commit below - resolving it a second time there is what
    // catches a locator that only became foreign in between.
    Optional<UploadedOriginalRef> ownOriginal = UploadedOriginalRef.of(document);
    UUID chunkFilterDocumentId = document.getId();

    // Attachments go with their document, deepest first because of fk_documents_parent (ADR-0022,
    // Entscheidung 3); none of them has a stored original of its own.
    List<Document> descendantsDeepestFirst = descendantsDeepestFirst(document.getId());
    List<UUID> attachmentChunkFilterIds = new ArrayList<>();
    for (Document descendant : descendantsDeepestFirst) {
      attachmentChunkFilterIds.add(descendant.getId());
      documentRepository.delete(descendant);
    }

    // Row first, chunks after commit: a racing upload task's conditional markIndexed then affects
    // zero rows and removes the chunks it just wrote itself.
    documentRepository.delete(document);
    noteRevisit(document);
    assetRepository.markContentChanged(document.getLibraryId(), Instant.now(clock));

    // Chunks and file go only after commit, so a rollback keeps them. Each step is guarded on its
    // own: a failed chunk delete neither fails the committed request nor skips the file, and leaves
    // orphaned chunks without a retry (accepted).
    deleteAfterCommit(
        () -> {
          for (UUID attachmentId : attachmentChunkFilterIds) {
            try {
              vectorChunkStore.deleteByDocumentId(attachmentId);
            } catch (RuntimeException e) {
              log.error(
                  "Failed to remove vector store chunks for deleted attachment {} of document {} -"
                      + " orphaned chunks may remain",
                  attachmentId,
                  chunkFilterDocumentId,
                  e);
            }
          }
          try {
            vectorChunkStore.deleteByDocumentId(chunkFilterDocumentId);
          } catch (RuntimeException e) {
            log.error(
                "Failed to remove vector store chunks for deleted document {} - orphaned chunks may"
                    + " remain",
                chunkFilterDocumentId,
                e);
          }
          ownOriginal.ifPresent(uploadedOriginalStore::delete);
        });
  }

  /**
   * Notes the deleted document's folder for the next full sync of a library with a sync state, in
   * this transaction: otherwise the folder memory keeps the folder unlisted and the document never
   * returns (ADR-0045, Entscheidung 5). A document outside every container lies under no remembered
   * folder.
   */
  private void noteRevisit(Document document) {
    if (document.getSourceContainerKey() != null) {
      syncStateRepository.recordRevisit(
          UUID.randomUUID(),
          document.getLibraryId(),
          document.getSourceContainerKey(),
          document.getSourceHierarchyPath(),
          Instant.now(clock));
    }
  }

  /**
   * Every descendant of {@code rootId} via {@code parent_document_id}, deepest level first, so
   * deleting in this order never violates {@code fk_documents_parent}.
   */
  private List<Document> descendantsDeepestFirst(UUID rootId) {
    List<Document> deepestFirst = new ArrayList<>();
    List<UUID> currentLevel = List.of(rootId);
    while (!currentLevel.isEmpty()) {
      List<Document> nextLevel = new ArrayList<>();
      for (UUID parentId : currentLevel) {
        nextLevel.addAll(documentRepository.findByParentDocumentId(parentId));
      }
      if (nextLevel.isEmpty()) {
        break;
      }
      deepestFirst.addAll(0, nextLevel);
      currentLevel = nextLevel.stream().map(Document::getId).toList();
    }
    return deepestFirst;
  }

  /**
   * Runs {@code cleanup} after commit, never after a rollback: it removes data the database would
   * otherwise still consider live. Runs immediately without an active transaction.
   */
  private void deleteAfterCommit(Runnable cleanup) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      cleanup.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            cleanup.run();
          }
        });
  }

  /**
   * Only a library whose connector accepts uploads takes manual files (ADR-0018, Entscheidung 1);
   * {@code 409}, because the request conflicts with the library's fixed source type.
   */
  private void requireUploadLibrary(KnowledgeLibrary library) {
    if (!connectors.descriptor(library.getSourceType()).uploads()) {
      throw new ConflictException(
          "Diese Bibliothek ist eine Konnektorbibliothek und akzeptiert keine manuellen Uploads");
    }
  }

  /**
   * Validates {@code folderId} references an existing folder in {@code libraryId} - mirrors {@code
   * LibraryFolderService#resolveParent}'s identical cross-library treatment: a folder from another
   * library answers the same 404 as one that does not exist at all.
   */
  private void resolveFolder(UUID libraryId, UUID folderId) {
    LibraryFolder folder =
        folderRepository
            .findById(folderId)
            .orElseThrow(() -> new NotFoundException("Ordner nicht gefunden"));
    if (!folder.getLibraryId().equals(libraryId)) {
      throw new NotFoundException("Ordner nicht gefunden");
    }
  }

  /**
   * Requires {@link AssetRole#EDITOR} through {@link LibraryAccessService#requireRole}: {@code 404}
   * without any access, {@code 403} with too little.
   */
  private void requireEditable(KnowledgeLibrary library, UUID currentUserId, boolean systemAdmin) {
    accessService.requireRole(library, currentUserId, systemAdmin, AssetRole.EDITOR);
  }

  /**
   * Strips any directory components from the caller-supplied file name, keeping only its last
   * segment - defence in depth against {@code ../} alongside the fact that this name is never used
   * to build a filesystem path in the first place (see the class Javadoc).
   */
  private String sanitizeDisplayFileName(String originalFileName) {
    if (originalFileName == null || originalFileName.isBlank()) {
      return "";
    }
    String normalized = originalFileName.replace('\\', '/');
    int lastSlash = normalized.lastIndexOf('/');
    String lastSegment = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
    return lastSegment.isBlank() ? originalFileName : lastSegment;
  }

  /**
   * Splits {@code folderPath} at {@code "/"} and drops empty segments; {@link
   * LibraryFolderService#resolveOrCreateFolderPath} validates each segment. Blank means no path.
   */
  private List<String> splitFolderPath(String folderPath) {
    if (folderPath == null || folderPath.isBlank()) {
      return List.of();
    }
    List<String> segments = new ArrayList<>();
    for (String rawSegment : folderPath.split("/")) {
      if (!rawSegment.isBlank()) {
        segments.add(rawSegment);
      }
    }
    return segments;
  }

  /**
   * Whether {@code e} was raised by {@code fk_documents_folder}, read from the constraint name of
   * the wrapped {@link ConstraintViolationException}; {@code false} if the cause chain has none.
   */
  private boolean isFolderForeignKeyViolation(DataIntegrityViolationException e) {
    Throwable cause = e.getCause();
    while (cause != null) {
      if (cause instanceof ConstraintViolationException constraintViolation) {
        return "fk_documents_folder".equals(constraintViolation.getConstraintName());
      }
      cause = cause.getCause();
    }
    return false;
  }

  /** The accepted extension the given (already validated as supported) file name ends with. */
  private String matchedExtension(String fileName) {
    String lowerCased = fileName.toLowerCase();
    for (String extension : supportedFormats.extensions()) {
      if (lowerCased.endsWith(extension)) {
        return extension;
      }
    }
    // Unreachable: callers only invoke this after SupportedDocumentFormats.isSupported returned
    // true for the same name, which guarantees exactly this loop finds a match.
    throw new IllegalStateException("No supported extension matched for: " + fileName);
  }

  /**
   * Rejects the upload if magic-byte detection on the stored bytes, the ones later parsed,
   * contradicts {@code extension}. Applies to manual uploads only; operator-managed sources decide
   * by extension alone.
   */
  private void requireContentMatchesExtension(Path storedFile, String extension) {
    String detectedMimeType;
    try {
      detectedMimeType = SupportedDocumentFormats.detectMediaType(storedFile);
    } catch (IOException e) {
      throw new UncheckedIOException("Datei konnte nicht auf ihr Format geprueft werden", e);
    }
    if (!supportedFormats.contentMatchesExtension(extension, detectedMimeType)) {
      throw new ValidationException(
          "Der Inhalt der Datei entspricht nicht dem Format " + extension);
    }
  }

  private void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      log.warn("Could not delete file {}", path, e);
    }
  }
}
