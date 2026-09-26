package io.opaa.library;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentStatus;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.ServiceUnavailableException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.attachment.AttachmentProperties;
import io.opaa.indexing.attachment.StandaloneAttachmentAccess;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.document.AttachmentExtractor;
import io.opaa.indexing.document.AttachmentFilePath;
import io.opaa.indexing.document.ChecksumService;
import io.opaa.indexing.document.DocumentIngest;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.format.SupportedDocumentFormats;
import io.opaa.indexing.source.OriginalAccess;
import io.opaa.indexing.source.OriginalUnavailableException;
import io.opaa.indexing.source.ServedOriginals;
import io.opaa.indexing.source.SourceConnectorRegistry;
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
 * Uploads documents into, and removes them from, a {@link KnowledgeLibrary} via the REST API (#420,
 * docs/features/knowledge-sources.md#upload) - the human counterpart to the connector/crawl
 * ingestion paths {@code DocumentIngestService} already serves. Both mutating methods here require
 * {@link AssetRole#EDITOR} on the target library (see {@link #requireEditable}), one level below
 * the {@code MANAGER} the library-configuration endpoints require - a person may add or remove
 * content without being allowed to change who else can.
 *
 * <p>Reuses the existing indexing pipeline deliberately: {@link #uploadDocument} stores the
 * incoming bytes, computes their checksum and creates the {@code Document} row itself (all three
 * are specific to how this endpoint decides *where* a file goes and whether it is a duplicate
 * *within its target library* - a different question from what {@code
 * DocumentIngestService#ingest}'s file-path-keyed dedup answers), then hands off to {@link
 * DocumentIngestService#processUploadedFileAsync} for parsing, chunking and vector storage - the
 * same three steps every other ingestion path goes through, so a chat query finds an uploaded
 * document exactly the same way it finds a crawled one.
 *
 * <p><b>Processing is asynchronous (#434).</b> {@link #uploadDocument} returns as soon as the file
 * is stored and the row is persisted with status {@code PENDING} - it does not wait for {@link
 * DocumentIngestService#processUploadedFileAsync} to finish parsing/embedding on {@code
 * uploadTaskExecutor}. A caller with only {@code EDITOR} could otherwise tie up a request thread
 * for the full duration of Tika parsing and embedding on every upload, with no rate limit covering
 * this endpoint (#434 supersedes #420's synchronous design for exactly this reason). The caller
 * observes the eventual {@code INDEXED}/{@code FAILED} transition by polling {@code GET
 * /libraries/{libraryId}/documents}, the same way the frontend's {@code documentStore.ts} already
 * polls a directory/URL indexing run in progress.
 *
 * <p><b>{@link #deleteDocument} deletes the row before the chunks, not the chunks before the row
 * (#614).</b> Asynchronous processing (previous paragraph) means a document can still be mid-flight
 * on {@code uploadTaskExecutor} while a delete request for the same document arrives on another
 * thread. Deleting the row first closes that race: {@link
 * io.opaa.knowledge.DocumentRepository#markIndexed}/{@code #markFailed} are conditional updates
 * that only ever affect a row that still exists, so once this method's transaction commits, a
 * racing task's status update is guaranteed to see the row gone and clean up any chunks it just
 * wrote itself (see {@code DocumentIngestService#processUploadedFileAsync}). The vector store
 * delete here only has to handle documents that already had chunks before this call, deferred to
 * after commit (next paragraph) alongside the file, for the same reason.
 *
 * <p><b>Path traversal (#420 acceptance criteria):</b> the caller-supplied original file name never
 * reaches the storage. {@link UploadedOriginalStore} is handed the organization and library id -
 * the latter from the {@code @PathVariable UUID}, which Spring rejects unless it parses as one, the
 * former off the loaded library row - and an extension out of {@link
 * SupportedDocumentFormats#extensions()}, not a suffix sliced out of the original name; it names
 * the stored original itself (ADR-0030). The original name is kept only as {@link
 * Document#getFileName()} display metadata, sanitized to its last path segment as a second,
 * defence-in-depth measure even though it is never interpreted as a path.
 *
 * <p><b>{@link #deleteDocument} only ever deletes an original this application itself stored (#420
 * code review, finding 1).</b> A document's {@code file_path} is not always a locator of the upload
 * storage: {@code FILESYSTEM}-sourced documents point at the operator-managed indexing directory,
 * and {@code HTTP_DIRECTORY} ones do not name a local file OPAA owns at all. Deleting on the
 * strength of that column alone - without checking {@link Document#getSourceType()} and letting
 * {@link UploadedOriginalStore} resolve it against this library's own storage area - would let
 * anyone with {@code EDITOR} on a library that also happens to hold crawled documents (every
 * library can, since #419 routes crawl runs into a caller-chosen library rather than a single
 * reserved one) delete a file outside OPAA's own data directory entirely, with no undo.
 */
@Service
public class LibraryDocumentService implements FolderDocumentDeleter {

  private static final Logger log = LoggerFactory.getLogger(LibraryDocumentService.class);

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

  /** One transaction per document for {@link #deleteDocuments} - see its contract. */
  private final TransactionTemplate transactionTemplate;

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
      PlatformTransactionManager transactionManager) {
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
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * The pre-#823 signature, kept for every caller that never needs a {@code folderPath} - delegates
   * to the full overload below with {@code folderPath = null}.
   */
  public LibraryDocumentEntry uploadDocument(
      UUID libraryId, MultipartFile file, UUID folderId, CurrentUser caller) {
    return uploadDocument(libraryId, file, folderId, null, caller);
  }

  /**
   * {@code folderPath} (#823, Epic #520 Phase 4): an optional path relative to {@code folderId}
   * (itself optional, meaning the library's root) - e.g. {@code "Protokolle/2026"} - whose
   * intermediate folders are created idempotently (existing ones of the same name reused, never
   * duplicated) via {@link LibraryFolderService#resolveOrCreateFolderPath}, resolved right before
   * the file is actually written to disk - after every cheap, certain-to-reject check (empty file,
   * size, quota, format) has already passed, see the comment at that call site for why. Lets a
   * whole dragged-and-dropped directory tree upload one file at a time while still ending up under
   * a single, shared folder chain instead of a separate accidental duplicate per file.
   */
  public LibraryDocumentEntry uploadDocument(
      UUID libraryId, MultipartFile file, UUID folderId, String folderPath, CurrentUser caller) {
    UUID currentUserId = caller.id();
    KnowledgeLibrary library = loadLibrary(libraryId, caller);
    requireEditable(library, currentUserId, caller.isSystemAdmin());
    requireUploadLibrary(library);

    // #821: validated before any byte is written to disk, mirroring every other "reject this
    // request outright" check below - a folderId that does not exist (or belongs to another
    // library, treated identically per resolveFolder) must leave the bestand exactly as it was.
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
    // #119: checked before anything is written to disk, so a rejected upload leaves the bestand
    // (and the file system) exactly as it was - the same "nothing persisted" guarantee the size
    // check above already gives. Deliberately does not net out a same-checksum FAILED row this
    // upload might be about to replace (see the dedup check further down) - a conservative, simple
    // check rather than one that would have to duplicate that lookup this early.
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

    // #823 review, Befund 1: resolved (and, where needed, created) only after every cheap,
    // certain-to-reject check above (empty file, size, quota, format) has already passed - unlike
    // those checks, resolveOrCreateFolderPath's own transaction cannot be rolled back afterwards:
    // resolveOrCreateFolderPath is its own separate @Transactional call (this method itself is not
    // @Transactional), so it commits its folder chain the moment it
    // returns, regardless of what this method does afterwards. Running it before those checks
    // (the original #823 order) left a committed, empty folder skeleton behind for every rejected
    // upload - three hundred oversized/wrong-format files dropped into a new "Protokolle/2026"
    // path created that folder chain three hundred times over before ever failing. Moved to
    // directly precede the actual disk write, the last point before which nothing about this
    // upload is yet certain to fail; a failure past this point (a race on the folder chain itself
    // aside, see resolveOrCreateFolderPath) is comparatively rare and already leaves worse traces
    // (a written file, see the catch blocks below) that this method already has to clean up.
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
      // Dedup is scoped per library (#420 acceptance criteria): the same file uploaded into two
      // different libraries is two legitimate documents, only a second copy inside the *same*
      // library is rejected.
      Optional<Document> existing =
          documentRepository.findByLibraryIdAndChecksumAndParentDocumentIdIsNull(
              libraryId, checksum);
      if (existing.isPresent()) {
        Document existingDoc = existing.get();
        if (existingDoc.getStatus() != DocumentStatus.FAILED) {
          accepted.discard();
          throw new ConflictException("Diese Datei ist bereits in dieser Bibliothek vorhanden");
        }
        // #589 review, item 3: a FAILED row must not block a retry of the same file forever - the
        // per-library dedup check above (and uk_documents_library_checksum, migration 020) can't
        // otherwise tell "this content already succeeded" from "this content failed once and the
        // user is trying again", so it replaces the old FAILED row instead of rejecting the new
        // upload. A FAILED connector row can legitimately still have chunks since #1268 (a version
        // whose parsing failed keeps the previous, working ones), so the delete below is what
        // actually removes them here - not merely defence in depth.
        //
        // ADR-0022, Entscheidung 3 (Nebenpfad-Auflage, #1218): a FAILED mail row can still have
        // INDEXED attachment children from before the failure (attachments are indexed while the
        // parent is being parsed, before its own chunks are written) - they must go with it, or
        // documentRepository.delete below fails fk_documents_parent and the retry is blocked
        // forever. The retry's re-parse recreates them under the new row's own file_path.
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
      // Set on this first (and only synchronous) save: this is where a concurrent duplicate
      // upload race against uk_documents_library_checksum (migration 020) is meant to be settled -
      // before any embedding work starts, not after (#420 second code review round, finding 1,
      // still true now that the embedding work itself has moved off this thread entirely).
      document.setChecksum(checksum);
      document = documentRepository.save(document);

      // #589 review, item 4: from here on, the row is committed - a RuntimeException must never
      // again just delete the file and rethrow (the outer catch below), or the row would survive
      // pointing at a dead file_path forever. This inner try/catch keeps that guarantee local to
      // the one call that can still fail after the row exists, instead of relying on it being the
      // last statement in the method.
      try {
        // Parsing/chunking/embedding run on uploadTaskExecutor from here (#434) - this method
        // returns the PENDING row without waiting for that to finish.
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
        // #589 review, item 2: uploadTaskExecutor's queue is full - it never silently discards the
        // task (see IndexingConfiguration#uploadTaskExecutor; #501 later gave indexingTaskExecutor
        // the same AbortPolicy for the same reason), so this is the one place that ever has to
        // react to it here. The row is already visible to the caller as PENDING; leaving it there
        // would have it poll forever for a job nothing will ever run.
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
      // #821 review round 1, finding 5: the save() above can violate two different constraints,
      // and they must not share one message. fk_documents_folder (migration 062) fires when
      // folderId - already confirmed to exist by resolveFolder above - is deleted by a concurrent
      // request in the narrow window between that check and this INSERT; without this
      // distinction, that race surfaced as the same "Diese Datei ist bereits in dieser Bibliothek
      // vorhanden" the checksum race below produces, actively misleading a caller whose file was
      // never a duplicate at all.
      accepted.discard();
      if (isFolderForeignKeyViolation(e)) {
        throw new NotFoundException("Der Ordner wurde inzwischen gelöscht");
      }
      // Race-safety net for the findByLibraryIdAndChecksum check above (#420 code review, nit 5):
      // that check and the eventual INSERT are two separate steps with no database guarantee
      // between them, so two concurrent uploads of the same file into the same library could both
      // pass it. uk_documents_library_checksum (migration 020) is the actual guarantee; this maps
      // its violation - and any other DataIntegrityViolationException this INSERT could still
      // raise - to the same 409 the sequential check already produces, kept as the neutral
      // fallback rather than assuming every violation is the folder race handled above.
      throw new ConflictException("Diese Datei ist bereits in dieser Bibliothek vorhanden");
    } catch (IOException e) {
      accepted.discard();
      throw new UncheckedIOException("Datei konnte nicht gespeichert werden", e);
    } catch (RuntimeException e) {
      // Only reachable before the row is committed (validation, file I/O, the dedup check above) -
      // everything from documentRepository.save(document) onward has its own inner try/catch that
      // never lets a RuntimeException escape to here (#589 review, item 4).
      accepted.discard();
      throw e;
    }
    return new LibraryDocumentEntry(
        storedRow, LibraryFolderPaths.pathOf(folderRepository, storedRow.getFolderId()));
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
   * Marks an already-persisted upload row {@code FAILED} and cleans up its file, instead of leaving
   * a {@code PENDING} row with a dead {@code file_path} behind (#589 review, item 4) - the
   * counterpart, once the row exists, to the pre-commit paths above that simply delete the file and
   * rethrow. Returns the response the caller hands back to the client, same as the success path.
   *
   * <p>Uses {@link DocumentRepository#markFailed} - a conditional {@code UPDATE} - rather than a
   * plain {@code documentRepository.save} (#636 review, item 3): the row committed by {@link
   * #uploadDocument} just above is visible to every other request from that moment on, so a
   * concurrent {@link #deleteDocument} could remove it in the narrow window between that commit and
   * this call (e.g. while {@link DocumentIngestService#processUploadedFileAsync} is being handed
   * off and throws synchronously). A plain {@code save} on the caller's now-stale in-memory {@code
   * document} would not notice and silently re-{@code INSERT} it as a zombie - the same failure
   * mode {@link DocumentRepository#markIndexed}/{@code #markFailed}'s own Javadoc describes for the
   * asynchronous path. The response returned here still reflects the caller's own upload attempt
   * either way - {@code document} is only ever used to build it, never persisted directly again.
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
   * Resolves the on-disk original behind {@code documentId} for streaming (#736) - the read
   * counterpart to {@link #uploadDocument}/{@link #deleteDocument}'s write-side file handling, and
   * subject to the same "no existence leak" discipline {@link
   * io.opaa.knowledge.LibraryAccessService#requireRole} already applies to every other
   * library-scoped endpoint: an unknown document, one in another organization, one the caller has
   * no grant on, one of a sourceType with no local file, and one whose file has since disappeared
   * from disk all answer the same {@code 404}, in that order, so a caller can never distinguish
   * "does not exist" from any of the others.
   *
   * <p>Requires only {@link AssetRole#VIEWER} (#736 acceptance criteria) - the same floor {@link
   * LibraryAccessService#canRead} already uses for a library's configuration and document list;
   * opening a document's own content is not more sensitive than seeing it listed - checked through
   * {@link LibraryAccessService#requireContentRead}, which knows no administrative floor, so a
   * system admin without a grant gets {@code 403} (#1828).
   *
   * <p>The original itself comes from the document's connector ({@link OriginalAccess}), which
   * never trusts the stored {@code file_path} on its own: an upload resolves only within this
   * library's own storage area, a directory file only underneath the library's own {@code
   * sourcePath}, a URL-fetched original only from its stored address through the target validation,
   * an object only from the library's own store.
   *
   * <p>An attachment document (ADR-0022, #1239) has no original of its own at all - neither on disk
   * nor behind its synthetic {@code file_path} - and is served by {@link #loadAttachmentContent},
   * which re-extracts it from its parent chain's own original.
   */
  public DocumentContent loadContent(UUID documentId, CurrentUser caller) {
    Document document =
        documentRepository
            .findById(documentId)
            .orElseThrow(() -> new NotFoundException("Dokument nicht gefunden"));

    KnowledgeLibrary library =
        libraryRepository
            .findById(document.getLibraryId())
            .filter(lib -> lib.getOrganizationId().equals(caller.organizationId()))
            .orElseThrow(() -> new NotFoundException("Dokument nicht gefunden"));
    accessService.requireContentRead(library, caller.id(), caller.isSystemAdmin());

    if (isReExtractableAttachment(document)) {
      return loadAttachmentContent(document, library);
    }
    return loadOriginal(document, library);
  }

  /**
   * Whether {@code document}'s bytes only exist inside its parent's original and must be
   * re-extracted (ADR-0022, #1239) - recognized at its {@code file_path}, which then embeds the
   * parent's own path plus an extraction index ({@code DocumentIngestService#attachmentFilePath},
   * an {@code AttachmentSource.LocalFile} attachment: Mail). An attachment with a source identity
   * of its own - an RSS/Confluence download URL ({@code AttachmentSource.Download}) - carries
   * {@code parent_document_id} just the same but names a real, fetchable original, and is therefore
   * served by {@link #loadOriginal} unchanged.
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
   * The original of a document that is not itself an attachment, from its connector's {@link
   * OriginalAccess}; a type without one has no original to serve. Access has already been checked
   * by {@link #loadContent}; {@link #loadAttachmentContent} calls this for an attachment's root
   * ancestor, which always lives in the same library as the attachment itself.
   *
   * <p>Two failure pictures (ADR-0030, Entscheidung 9): an original the connector cannot serve
   * answers the same German 404 as every other "no original available" case; a storage that cannot
   * be reached is a {@code 503}, a temporary condition a caller must not read as "this original
   * does not exist". The storage's own sentence stays in the log.
   */
  private DocumentContent loadOriginal(Document document, KnowledgeLibrary library) {
    OriginalAccess access =
        connectors
            .originalAccess(document.getSourceType())
            .orElseThrow(LibraryDocumentService::noOriginalAvailable);
    try {
      return access
          .openOriginal(document, library)
          .orElseThrow(LibraryDocumentService::noOriginalAvailable);
    } catch (OriginalUnavailableException e) {
      log.warn(
          "Original of document {} is not readable right now: {}",
          document.getId(),
          e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
      throw new ServiceUnavailableException(OBJECT_STORE_UNAVAILABLE);
    }
  }

  /**
   * The {@code 503} of an object store that cannot be reached or refuses the application right now
   * (#1524) - carries no detail of the failure, which goes to the log; the wording mirrors {@link
   * io.opaa.knowledge.UploadStoreUnavailableException}, whose storage is a different one.
   */
  static final String OBJECT_STORE_UNAVAILABLE =
      "Der Objektspeicher dieser Bibliothek ist derzeit nicht erreichbar. Bitte später erneut"
          + " versuchen.";

  private static NotFoundException noOriginalAvailable() {
    return new NotFoundException("Für dieses Dokument steht kein Originaldokument zur Verfügung");
  }

  /**
   * The original of an attachment document (ADR-0022, #1239). Attachment bytes are never stored:
   * they are re-extracted on demand from the root ancestor's own original - loaded through the very
   * path {@link #loadOriginal} uses for any other document, so every sourceType behaves the same -
   * by re-running the parent chain's pipelines and following the 0-based extraction index each
   * synthetic {@code file_path} segment carries ({@code DocumentIngestService#attachmentFilePath}).
   * Mail-in-Mail is not a special case: each level of the chain is one more extraction step.
   *
   * <p>The index alone is only meaningful while the parent is unchanged, so the name the pipeline
   * reports for the extracted attachment must equal the row's own {@code file_name} - a mismatch
   * (an attachment removed or reordered since indexing) answers the same German 404 as "no original
   * available" rather than streaming a different attachment's bytes under this row's name.
   *
   * <p>The chain ends at the first document that is not itself re-extractable ({@link
   * #isReExtractableAttachment}): a downloaded {@code .eml} that is an RSS attachment of its own
   * entry is the root to fetch, not a step to extract from its entry.
   *
   * <p>Authorization is unchanged: it is decided in {@link #loadContent} against the attachment's
   * own library, and every ancestor the extraction uses must belong to that same library - a broken
   * chain, a foreign library, or a chain deeper than {@link #maxAttachmentChainDepth()} (which also
   * bounds a corrupt, cyclic chain) is a 404.
   *
   * <p>Every temp file this method creates is deleted when the returned stream is closed, or before
   * it throws.
   *
   * <p>The extraction itself runs under {@link AttachmentExtractionLimiter} (#1243): serialized per
   * parent document and capped instance-wide, with a 429 rather than an unbounded wait once the cap
   * is reached.
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
            parentPath);
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
   * indices} down {@code chain}. Runs under {@link AttachmentExtractionLimiter} (#1243) - it is the
   * part that parses (and, for a connector Bestand, downloads) the parent original and writes temp
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
            attachmentExtractor.extract(currentFile, currentName, indices.get(i));
        if (extracted == null) {
          log.info(
              "Attachment document {}: no attachment at index {} of {} anymore",
              document.getId(),
              indices.get(i),
              currentName);
          throw noOriginalAvailable();
        }
        tempFiles.add(extracted.file());
        if (!expected.getFileName().equals(extracted.fileName())) {
          log.info(
              "Attachment document {}: index {} of {} now holds a different attachment ({})",
              document.getId(),
              indices.get(i),
              currentName,
              extracted.fileName());
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
      log.warn("Attachment document {} could not be re-extracted", document.getId(), e);
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
   * Deletes each of {@code documentIds} on its own (#1943): one transaction per id through {@link
   * #transactionTemplate}, so an id that is no longer a document of this library costs only its own
   * entry in the answer while every other document is still gone - the whole call as one
   * transaction would roll the successful deletions back with it. Duplicates are deleted once and
   * reported once, in the order they were first requested.
   *
   * <p>Only an {@code UPLOAD} library is accepted ({@link #requireUploadLibrary}): a document
   * removed from a connector library returns with its next indexing run (ADR-0018, Entscheidung 1),
   * so the bulk action is refused outright rather than silently undone.
   */
  public BulkDocumentDeletion deleteDocuments(
      UUID libraryId, List<UUID> documentIds, CurrentUser caller) {
    KnowledgeLibrary library = loadLibrary(libraryId, caller);
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
    KnowledgeLibrary library = loadLibrary(libraryId, caller);
    requireEditable(library, caller.id(), caller.isSystemAdmin());

    Document document =
        documentRepository
            .findById(documentId)
            .orElseThrow(() -> new NotFoundException("Dokument nicht gefunden"));
    // Treats a document from another library the same as one that does not exist at all - the same
    // reasoning KnowledgeLibraryService#loadLibrary applies across organizations.
    if (!document.getLibraryId().equals(libraryId)) {
      throw new NotFoundException("Dokument nicht gefunden");
    }

    // Read while the row is still there; whether it names an original of this library is decided
    // by the delete itself, after the commit below - resolving it a second time there is what
    // catches a locator that only became foreign in between.
    Optional<UploadedOriginalRef> ownOriginal = UploadedOriginalRef.of(document);
    UUID chunkFilterDocumentId = document.getId();

    // ADR-0022, Entscheidung 3 (Nebenpfad-Auflage): a document with attachment rows pointing at
    // it via parent_document_id cannot be deleted first without failing fk_documents_parent - this
    // path takes its attachments with it, mirroring StaleDocumentCleanupService's own
    // deepest-nesting-first order (#1183: a Mail-in-Mail chain can nest an attachment inside an
    // attachment, not just one level). None of them are ever an UPLOAD-managed file of this
    // service's own (only the connector paths that create attachments set parentDocumentId), so
    // there is no extra file to clean up for a descendant the way there can be for the parent
    // below.
    List<Document> descendantsDeepestFirst = descendantsDeepestFirst(document.getId());
    List<UUID> attachmentChunkFilterIds = new ArrayList<>();
    for (Document descendant : descendantsDeepestFirst) {
      attachmentChunkFilterIds.add(descendant.getId());
      documentRepository.delete(descendant);
    }

    // The row is deleted first, the chunks only afterwards - deliberately the reverse of the order
    // this method used before #614. A concurrent uploadTaskExecutor task finishing the very same
    // document races this method: DocumentIngestService#processUploadedFileAsync re-reads the row,
    // writes its chunks, and only then calls DocumentRepository#markIndexed, which affects a row
    // only if it is still there. Deleting the chunks here *before* the row let that task's
    // markIndexed still see (and update) the row while its own transaction was still in-flight,
    // leaving its freshly-written chunks behind after this method's own vectorStore.delete had
    // already run and would never run again - a document gone from the list but still returned by
    // /api/v1/query. Deleting the row first closes that window: by the time this transaction
    // commits, the row is unconditionally gone, so a racing markIndexed (which blocks on the same
    // row until this commits) is guaranteed to affect zero rows and clean up its own chunks itself
    // (see DocumentIngestService#processUploadedFileAsync). The vector store delete below then only
    // has to handle the ordinary case: chunks a document already had before this call.
    documentRepository.delete(document);

    // Both deferred to after commit (#420 code review, nit 7, extended to the chunk deletion by
    // #614): if the row deletion above rolls back for any reason, the file and its chunks must
    // still be there afterwards - deleting either eagerly here would leave a document that is still
    // listed and still searchable pointing at nothing, or a file/chunks gone despite the row
    // surviving.
    //
    // Both steps below are individually guarded (PR #631 review, finding 1). By the time this
    // callback runs, the row deletion has already committed - the caller's request has already
    // succeeded from the database's point of view. Letting a vectorStore.delete failure propagate
    // from here (afterCommit synchronizations run outside the original request's exception
    // handling) would turn that success into a 500 the caller never asked for, and - since the
    // callback would never reach the line below - skip the file deletion entirely for a reason that
    // has nothing to do with the file. Each step is therefore its own try/catch: a pgvector outage
    // during the chunk delete must not stop the file from being removed, and vice versa. Accepted
    // residual risk: a chunk delete that fails this way leaves orphaned chunks in the vector store,
    // still returned by /api/v1/query, with no automatic retry - the same already-accepted risk
    // #614's own reasoning above describes for the concurrent-upload race, now also reachable via a
    // genuine vectorStore failure. Recovering from that is out of scope here; see #614's follow-up
    // discussion.
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
   * Every descendant of {@code rootId} (children, grandchildren, ...) via {@code
   * parent_document_id}, ordered deepest-first (#1183): a breadth-first walk collects each level in
   * turn, then the result is reversed so the deepest level - the one with no descendants of its own
   * still pending deletion - is deleted first, mirroring {@code
   * StaleDocumentCleanupService#sortedDeepestFirst}'s own reasoning for {@code
   * fk_documents_parent}. A Mail-in-Mail chain (an attachment that is itself a parent with its own
   * attachments) is the motivating case; a flat, one-level attachment set (RSS, Confluence) is just
   * a breadth-first walk of depth 1.
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
   * Registers {@code cleanup} to run only once the enclosing transaction has committed - mirrors
   * {@code AssetGrantService#invalidateAfterCommit}'s reasoning, except this uses {@code
   * afterCommit} rather than {@code afterCompletion}: a cache eviction is harmless to run after a
   * rollback too, but removing data (a file, a vector store's chunks) whose owning row deletion
   * just rolled back would destroy data the database still considers live. Falls back to running
   * immediately when no transaction is active.
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
   * ADR-0018, Entscheidung 1: only a {@code UPLOAD} library accepts manually uploaded files - a
   * lauf-basierte (connector) library's content comes exclusively from its own indexing run, so a
   * human upload into it would be indistinguishable from a crawled document the next run considers
   * gone (ADR-0017, Entscheidung 5's "je Quelle, niemals bibliotheksweit" absence check has no way
   * to tell the two apart). {@code 409} rather than {@code 400}: the request is well-formed, it
   * simply conflicts with this library's fixed, immutable source type (#479).
   */
  private void requireUploadLibrary(KnowledgeLibrary library) {
    if (connectors.descriptor(library.getSourceType()).indexingRun()) {
      throw new ConflictException(
          "Diese Bibliothek ist eine Konnektorbibliothek und akzeptiert keine manuellen Uploads");
    }
  }

  /**
   * Validates {@code folderId} references an existing folder in {@code libraryId} (#821) - mirrors
   * {@code LibraryFolderService#resolveParent}'s identical cross-library treatment: a folder from
   * another library answers the same 404 as one that does not exist at all.
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
   * Whether the caller may add or remove documents in {@code library} - requires {@link
   * AssetRole#EDITOR}. Introduced here in #420 with its own "no access at all" ({@code 404}) vs.
   * "some access, but not enough" ({@code 403}) distinction; #436 later generalised that same check
   * into {@link LibraryAccessService#requireRole} and moved every other library-scoped endpoint
   * onto it, so this method now only supplies the {@code EDITOR} threshold these two upload
   * endpoints require.
   */
  private void requireEditable(KnowledgeLibrary library, UUID currentUserId, boolean systemAdmin) {
    accessService.requireRole(library, currentUserId, systemAdmin, AssetRole.EDITOR);
  }

  /**
   * Loads a library and enforces the organization boundary, treating a library from another
   * organization as not found - mirrors {@code KnowledgeLibraryService#loadLibrary}.
   */
  private KnowledgeLibrary loadLibrary(UUID libraryId, CurrentUser caller) {
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .orElseThrow(() -> new NotFoundException("Bibliothek nicht gefunden"));
    if (!library.getOrganizationId().equals(caller.organizationId())) {
      throw new NotFoundException("Bibliothek nicht gefunden");
    }
    return library;
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
   * Splits a {@code folderPath} like {@code "Protokolle/2026"} into its individual segments (#823):
   * empty segments - a leading/trailing/doubled {@code "/"} - are dropped rather than rejected, so
   * a caller-built path does not have to be perfectly normalized first. {@link
   * LibraryFolderService#resolveOrCreateFolderPath}'s own {@code validatePathSegment} is what
   * actually validates each surviving segment's shape (length, no further separators, no {@code
   * ".."}). {@code null}/blank yields an empty list, meaning "no path" - the same as omitting the
   * parameter entirely.
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
   * Whether {@code e} was raised by {@code fk_documents_folder} (migration 062) specifically, not
   * {@code uk_documents_library_checksum} or anything else {@code documentRepository.save} could
   * violate (#821 review round 1, finding 5) - inspects the wrapped Hibernate {@link
   * ConstraintViolationException}'s own {@code constraintName} (populated from the database
   * driver's error detail, {@code PostgreSQLDialect}'s violated-constraint-name extractor for
   * Postgres) rather than guessing from {@link DataIntegrityViolationException#getMessage()} alone,
   * which does not reliably say which of several constraints on the same table actually fired.
   * {@code false} - the safe, conservative default - whenever no {@link
   * ConstraintViolationException} is found in the cause chain at all (e.g. a hand-built exception a
   * test throws directly, mirroring how a real driver failure is always wrapped in practice).
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
   * Rejects the upload if Tika's magic-byte detection on the actually stored bytes contradicts
   * {@code extension} (#435, Maintainer-Entscheidung 20.08.2026). Runs against the file already
   * written to {@code storedFile} rather than the multipart stream directly, so the same bytes that
   * end up parsed and indexed are the ones inspected here - and so a mismatch is caught before the
   * checksum/dedup work below spends any effort on content that will be rejected anyway.
   *
   * <p>Deliberately scoped to this upload path alone, not {@link SupportedDocumentFormats#
   * isSupported}: operator-managed sources (filesystem, network) keep the extension-only decision
   * #404 settled - see the class Javadoc there for why a human uploading a file through this
   * endpoint is a different situation.
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
