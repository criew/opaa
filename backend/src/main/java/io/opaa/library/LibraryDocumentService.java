package io.opaa.library;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.ChecksumService;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.SupportedDocumentFormats;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.source.filesystem.FilesystemPathAllowlist;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.ProxyAndCredentials;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploads documents into, and removes them from, a {@link KnowledgeLibrary} via the REST API (#420,
 * docs/features/knowledge-sources.md#upload) - the human counterpart to the connector/crawl
 * ingestion paths {@code FileProcessingService} already serves. Both mutating methods here require
 * {@link AssetRole#EDITOR} on the target library (see {@link #requireEditable}), one level below
 * the {@code MANAGER} the library-configuration endpoints require - a person may add or remove
 * content without being allowed to change who else can.
 *
 * <p>Reuses the existing indexing pipeline deliberately: {@link #uploadDocument} stores the
 * incoming bytes, computes their checksum and creates the {@code Document} row itself (all three
 * are specific to how this endpoint decides *where* a file goes and whether it is a duplicate
 * *within its target library* - a different question from what {@code
 * FileProcessingService#processFile}'s file-path-keyed dedup answers), then hands off to {@link
 * FileProcessingService#processUploadedFileAsync} for parsing, chunking and vector storage - the
 * same three steps every other ingestion path goes through, so a chat query finds an uploaded
 * document exactly the same way it finds a crawled one.
 *
 * <p><b>Processing is asynchronous (#434).</b> {@link #uploadDocument} returns as soon as the file
 * is stored and the row is persisted with status {@code PENDING} - it does not wait for {@link
 * FileProcessingService#processUploadedFileAsync} to finish parsing/embedding on {@code
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
 * io.opaa.indexing.DocumentRepository#markIndexed}/{@code #markFailed} are conditional updates that
 * only ever affect a row that still exists, so once this method's transaction commits, a racing
 * task's status update is guaranteed to see the row gone and clean up any chunks it just wrote
 * itself (see {@code FileProcessingService#processUploadedFileAsync}). The vector store delete here
 * only has to handle documents that already had chunks before this call, deferred to after commit
 * (next paragraph) alongside the file, for the same reason.
 *
 * <p><b>Path traversal (#420 acceptance criteria):</b> the caller-supplied original file name is
 * never used to build a filesystem path. The stored file always lives at {@code
 * <storagePath>/<libraryId>/<random-uuid><matched-extension>} - {@code libraryId} comes from the
 * {@code @PathVariable UUID} (Spring rejects anything that does not parse as a UUID before this
 * class ever sees it), the generated file name is a fresh random UUID, and the extension is one of
 * {@link SupportedDocumentFormats#extensions()}, not a suffix sliced out of the original name. The
 * original name is kept only as {@link Document#getFileName()} display metadata, sanitized to its
 * last path segment as a second, defence-in-depth measure even though it is never interpreted as a
 * path.
 *
 * <p><b>{@link #deleteDocument} only ever deletes a file this class itself wrote (#420 code review,
 * finding 1).</b> A document's {@code file_path} is not always inside {@code
 * opaa.upload.storage-path}: {@code FILESYSTEM}-sourced documents point at the operator-managed
 * indexing directory, and {@code HTTP_DIRECTORY} ones do not name a local file OPAA owns at all.
 * Deleting on the strength of that column alone - without checking {@link Document#getSourceType()}
 * and that the path actually resolves under this library's own upload subdirectory - would let
 * anyone with {@code EDITOR} on a library that also happens to hold crawled documents (every
 * library can, since #419 routes crawl runs into a caller-chosen library rather than a single
 * reserved one) delete a file outside OPAA's own data directory entirely, with no undo.
 */
@Service
public class LibraryDocumentService {

  private static final Logger log = LoggerFactory.getLogger(LibraryDocumentService.class);

  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService accessService;
  private final DocumentRepository documentRepository;
  private final ChecksumService checksumService;
  private final FileProcessingService fileProcessingService;
  private final VectorChunkStore vectorChunkStore;
  private final UploadProperties uploadProperties;
  private final LibraryStorageQuotaService storageQuotaService;
  private final FilesystemPathAllowlist filesystemAllowlist;
  private final BoundedDownloader boundedDownloader;
  private final TargetAddressValidator targetAddressValidator;
  private final RemoteContentProperties remoteContentProperties;
  private final LibraryFolderRepository folderRepository;
  private final LibraryFolderService folderService;

  public LibraryDocumentService(
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService accessService,
      DocumentRepository documentRepository,
      ChecksumService checksumService,
      FileProcessingService fileProcessingService,
      VectorChunkStore vectorChunkStore,
      UploadProperties uploadProperties,
      LibraryStorageQuotaService storageQuotaService,
      FilesystemPathAllowlist filesystemAllowlist,
      BoundedDownloader boundedDownloader,
      TargetAddressValidator targetAddressValidator,
      RemoteContentProperties remoteContentProperties,
      LibraryFolderRepository folderRepository,
      LibraryFolderService folderService) {
    this.libraryRepository = libraryRepository;
    this.accessService = accessService;
    this.documentRepository = documentRepository;
    this.checksumService = checksumService;
    this.fileProcessingService = fileProcessingService;
    this.vectorChunkStore = vectorChunkStore;
    this.uploadProperties = uploadProperties;
    this.storageQuotaService = storageQuotaService;
    this.filesystemAllowlist = filesystemAllowlist;
    this.boundedDownloader = boundedDownloader;
    this.targetAddressValidator = targetAddressValidator;
    this.remoteContentProperties = remoteContentProperties;
    this.folderRepository = folderRepository;
    this.folderService = folderService;
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
    if (!SupportedDocumentFormats.isSupported(displayFileName)) {
      throw new ValidationException(
          "Das Dateiformat wird nicht unterstützt. Erlaubt sind: "
              + String.join(", ", SupportedDocumentFormats.extensions()));
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

    Path libraryDir = Paths.get(uploadProperties.storagePath()).resolve(libraryId.toString());
    Path storedFile = libraryDir.resolve(UUID.randomUUID() + extension);

    try {
      Files.createDirectories(libraryDir);
      try (InputStream in = file.getInputStream()) {
        Files.copy(in, storedFile, StandardCopyOption.REPLACE_EXISTING);
      }

      requireContentMatchesExtension(storedFile, extension);

      String checksum = checksumService.computeSha256(storedFile);
      // Dedup is scoped per library (#420 acceptance criteria): the same file uploaded into two
      // different libraries is two legitimate documents, only a second copy inside the *same*
      // library is rejected.
      Optional<Document> existing =
          documentRepository.findByLibraryIdAndChecksum(libraryId, checksum);
      if (existing.isPresent()) {
        Document existingDoc = existing.get();
        if (existingDoc.getStatus() != DocumentStatus.FAILED) {
          Files.deleteIfExists(storedFile);
          throw new ConflictException("Diese Datei ist bereits in dieser Bibliothek vorhanden");
        }
        // #589 review, item 3: a FAILED row must not block a retry of the same file forever - the
        // per-library dedup check above (and uk_documents_library_checksum, migration 020) can't
        // otherwise tell "this content already succeeded" from "this content failed once and the
        // user is trying again", so it replaces the old FAILED row instead of rejecting the new
        // upload. It should never have surviving chunks (FileProcessingService cleans those up on
        // every failure path), but the delete is unconditional anyway, mirroring processFile's own
        // re-index cleanup - defence in depth costs nothing here.
        Path oldFailedFile = uploadedFileIfManagedByThisService(existingDoc, libraryId);
        vectorChunkStore.deleteByDocumentId(existingDoc.getId());
        documentRepository.delete(existingDoc);
        if (oldFailedFile != null) {
          deleteQuietly(oldFailedFile);
        }
      }

      // #434: the row is created - and returned - as PENDING here, before any parsing/embedding
      // has happened. contentType/fileSize are read from the file this class itself just wrote,
      // mirroring FileProcessingService#processFile's own stat-after-store approach.
      String contentType = Files.probeContentType(storedFile);
      long fileSize = Files.size(storedFile);
      Document document =
          new Document(
              displayFileName,
              storedFile.toAbsolutePath().toString(),
              contentType,
              fileSize,
              DocumentSourceType.UPLOAD);
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
        fileProcessingService.processUploadedFileAsync(document.getId(), storedFile);
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
            storedFile,
            "Die Verarbeitung ist derzeit ausgelastet - bitte später erneut versuchen.");
      } catch (RuntimeException e) {
        log.error(
            "Failed to start asynchronous processing for uploaded document {}",
            document.getId(),
            e);
        return failAlreadyPersistedUpload(
            document, storedFile, "Die Verarbeitung konnte nicht gestartet werden");
      }

      return new LibraryDocumentEntry(
          document, LibraryFolderPaths.pathOf(folderRepository, document.getFolderId()));
    } catch (DataIntegrityViolationException e) {
      // #821 review round 1, finding 5: the save() above can violate two different constraints,
      // and they must not share one message. fk_documents_folder (migration 062) fires when
      // folderId - already confirmed to exist by resolveFolder above - is deleted by a concurrent
      // request in the narrow window between that check and this INSERT; without this
      // distinction, that race surfaced as the same "Diese Datei ist bereits in dieser Bibliothek
      // vorhanden" the checksum race below produces, actively misleading a caller whose file was
      // never a duplicate at all.
      deleteQuietly(storedFile);
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
      deleteQuietly(storedFile);
      throw new UncheckedIOException("Datei konnte nicht gespeichert werden", e);
    } catch (RuntimeException e) {
      // Only reachable before the row is committed (validation, file I/O, the dedup check above) -
      // everything from documentRepository.save(document) onward has its own inner try/catch that
      // never lets a RuntimeException escape to here (#589 review, item 4).
      deleteQuietly(storedFile);
      throw e;
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
   * this call (e.g. while {@link FileProcessingService#processUploadedFileAsync} is being handed
   * off and throws synchronously). A plain {@code save} on the caller's now-stale in-memory {@code
   * document} would not notice and silently re-{@code INSERT} it as a zombie - the same failure
   * mode {@link DocumentRepository#markIndexed}/{@code #markFailed}'s own Javadoc describes for the
   * asynchronous path. The response returned here still reflects the caller's own upload attempt
   * either way - {@code document} is only ever used to build it, never persisted directly again.
   */
  private LibraryDocumentEntry failAlreadyPersistedUpload(
      Document document, Path storedFile, String errorMessage) {
    int updated = documentRepository.markFailed(document.getId(), errorMessage);
    if (updated == 0) {
      log.warn(
          "Document {} was deleted before it could be marked FAILED after an upload processing"
              + " error",
          document.getId());
    }
    document.setStatus(DocumentStatus.FAILED);
    document.setErrorMessage(errorMessage);
    deleteQuietly(storedFile);
    return new LibraryDocumentEntry(
        document, LibraryFolderPaths.pathOf(folderRepository, document.getFolderId()));
  }

  /**
   * Resolves the on-disk original behind {@code documentId} for streaming (#736) - the read
   * counterpart to {@link #uploadDocument}/{@link #deleteDocument}'s write-side file handling, and
   * subject to the same "no existence leak" discipline {@link
   * io.opaa.library.LibraryAccessService#requireRole} already applies to every other library-scoped
   * endpoint: an unknown document, one in another organization, one the caller has no grant on, one
   * of a sourceType with no local file, and one whose file has since disappeared from disk all
   * answer the same {@code 404}, in that order, so a caller can never distinguish "does not exist"
   * from any of the others.
   *
   * <p>Requires only {@link AssetRole#VIEWER} (#736 acceptance criteria) - the same floor {@link
   * LibraryAccessService#canRead} already uses for a library's configuration and document list;
   * opening a document's own content is not more sensitive than seeing it listed.
   *
   * <p>Path traversal is closed the same way {@link #uploadedFileIfManagedByThisService} already
   * closes it for deletion: the resolved, normalized file path must actually resolve underneath the
   * one directory this {@code sourceType} is allowed to serve from - this library's own upload
   * subdirectory for {@code UPLOAD}, this library's own configured {@code sourcePath} for {@code
   * FILESYSTEM} - rather than trusting the stored {@code file_path} column on its own.
   *
   * <p>{@code HTTP_DIRECTORY}/{@code RSS_FEED} (#747): neither sourceType names a local file at all
   * - {@link #loadRemoteContent} proxies the original from the source URL stored at indexing time
   * instead, applying the library's own quellkonfiguration (proxy, credentials, insecure TLS) the
   * same way {@code UrlIndexingExecutor}/{@code RssFeedIndexingExecutor} already do.
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
    accessService.requireRole(library, caller.id(), caller.isSystemAdmin(), AssetRole.VIEWER);

    if (document.getSourceType() == DocumentSourceType.HTTP_DIRECTORY
        || document.getSourceType() == DocumentSourceType.RSS_FEED) {
      return loadRemoteContent(document, library);
    }

    Path resolvedFile =
        switch (document.getSourceType()) {
          case UPLOAD -> uploadedFileIfManagedByThisService(document, library.getId());
          case FILESYSTEM -> filesystemFileIfWithinConfiguredDirectory(document, library);
          case HTTP_DIRECTORY, RSS_FEED -> null; // unreachable, handled above
          // A Confluence page has no file of its own and its content sits behind the instance's
          // authentication; the citation opens the page directly via getDeepLinkSourceUrl.
          case CONFLUENCE -> null;
        };
    if (resolvedFile == null || !Files.isRegularFile(resolvedFile)) {
      throw new NotFoundException("Für dieses Dokument steht kein Originaldokument zur Verfügung");
    }

    // #742 review, finding 1: document.getContentType() is itself set from Files.probeContentType
    // at index time (see the extension-based decision this class and FileProcessingService already
    // make and document as intentional, #404's decideForFileName) - taking it as the primary source
    // here keeps a single decision point instead of a second, independent guess made from the bytes
    // at serve time, which could disagree with what was actually indexed. DocumentController's
    // Content-Security-Policy and X-Content-Type-Options headers are what keep that extension-based
    // guess from becoming a script execution vector, not this choice of source.
    String contentType = document.getContentType();
    if (contentType == null || contentType.isBlank()) {
      try {
        contentType = Files.probeContentType(resolvedFile);
      } catch (IOException e) {
        contentType = null;
      }
    }
    if (contentType == null || contentType.isBlank()) {
      contentType = "application/octet-stream";
    }
    return new DocumentContent(resolvedFile, document.getFileName(), contentType);
  }

  /**
   * Streams a {@code HTTP_DIRECTORY}/{@code RSS_FEED} document's original from its source URL
   * (#747) - {@link Document#getFilePath()}, the same identity {@code
   * FileProcessingService#processUrlFile}/{@code #processRssEntry} dedup by and {@link
   * Document#getDeepLinkSourceUrl()} already names as this document's own origin. No part of the
   * request ever influences which URL is fetched - only the value stored on this row at indexing
   * time, already validated against the target allowlist then (#267).
   *
   * <p><b>SSRF: the allowlist is checked again here, not just at indexing time (#747 acceptance
   * criteria).</b> {@link BoundedDownloader#downloadStreaming} re-validates {@link
   * TargetAddressValidator} on every hop before a single further byte is requested - the same
   * "Doppelprüfung" {@link #filesystemFileIfWithinConfiguredDirectory} already applies to {@link
   * FilesystemPathAllowlist}: an allowlist narrowed after this document was indexed must not let a
   * read against it silently keep succeeding. A redirect is only ever followed within the same
   * origin - {@code downloadStreaming} throws {@link
   * RedirectFollowingFetcher.RedirectRejectedException} outright for anything else, including a
   * protocol downgrade - and {@code Authorization} is therefore never built for, or sent to,
   * anything but the document's own stored URL and same-origin redirect hops from it. The
   * configured {@code sourceProxy} host is validated too (#748 review, nit 2) - it determines where
   * the TCP connection (and the credentials below) actually go, exactly as {@code
   * SourceConnectionTestService} already validates it before its own otherwise-identical probe.
   *
   * <p><b>DNS-Rebinding (#267, #748 review, "vorbestehend").</b> Like every other caller of {@link
   * TargetAddressValidator}, the address validated here and the address the JDK's {@code
   * HttpClient} eventually connects to both come from resolving the same hostname, but not
   * atomically - see {@link TargetAddressValidator}'s own Javadoc for why closing that gap
   * completely is not achievable on this HTTP client. Unlike an indexing run, this endpoint is
   * reachable by any caller with {@code VIEWER} on the library, repeatedly and on demand, which
   * narrows - without eliminating - the window a rebinding attack would need.
   *
   * <p><b>Credentials (#747 acceptance criteria).</b> The library's own {@code sourceCredentials}/
   * {@code sourceProxy}/{@code sourceInsecureSsl} - already offered to every {@code
   * HTTP_DIRECTORY}/{@code RSS_FEED} indexing run (ADR-0018, #505) - are applied to this fetch too,
   * mirroring {@code UrlIndexingExecutor#toUrlIndexingRequest}/{@code
   * RssFeedIndexingExecutor#execute}. They reach only the {@code Authorization} header built for
   * the outbound request; {@link DocumentContent} and the controller that serves it never see them.
   *
   * <p><b>Bounded by {@link RemoteContentProperties#maxBytes()} while streaming (#747, #748 review,
   * finding 1/3)</b> - deliberately not {@link UploadProperties#maxFileSize()}, and deliberately
   * not buffered into a {@code byte[]} or temp file first: the previous, buffering implementation
   * let a VIEWER clicking this endpoint repeatedly hold up to {@code maxFileSize} of heap per
   * in-flight request. {@link RemoteContentProperties#timeoutSeconds()} is likewise its own, short
   * timeout per hop - {@link BoundedDownloader#downloadBounded}'s 120s is sized for an unattended
   * background indexing run, not a human waiting on this click.
   *
   * <p>Every failure - the source offline, rejected by the allowlist, an invalid stored
   * configuration - answers the same German, user-facing 404 {@link #loadContent} already uses for
   * "no original available" locally (#747 acceptance criteria: "Quelle offline ≠ Serverfehler"),
   * never a 5xx that would suggest an OPAA-side error.
   */
  private DocumentContent loadRemoteContent(Document document, KnowledgeLibrary library) {
    String sourceUrl = document.getFilePath();
    if (sourceUrl == null || sourceUrl.isBlank()) {
      throw new NotFoundException("Für dieses Dokument steht kein Originaldokument zur Verfügung");
    }

    HttpClient httpClient = null;
    try {
      ProxyAndCredentials config =
          ProxyAndCredentials.parse(library.getSourceProxy(), library.getSourceCredentials());
      httpClient =
          SourceHttpClientFactory.buildHttpClient(
              config.proxyHost(), config.proxyPort(), library.isSourceInsecureSsl());
      // #748 review, nit 2: the proxy is exactly as caller-controlled as the target URL and
      // determines where the TCP connection (and Authorization below) actually goes - mirrors
      // SourceConnectionTestService's identical call before its own otherwise-analogous probe.
      targetAddressValidator.validateHost(config.proxyHost());
      String authHeader =
          SourceHttpClientFactory.buildAuthHeader(config.username(), config.password());

      BoundedDownloader.DownloadedStream downloaded =
          boundedDownloader.downloadStreaming(
              httpClient,
              sourceUrl,
              remoteContentProperties.maxBytes(),
              null,
              authHeader,
              Duration.ofSeconds(remoteContentProperties.timeoutSeconds()));

      // #742 review, finding 1/#748 review, finding 2: document.getContentType() - itself set from
      // Files.probeContentType/the RSS feed's own declared type at index time - is the primary
      // source here too, mirroring the local-file branch of loadContent above; the remote-declared
      // Content-Type is only a fallback, normalized to type/subtype (no parameters) so a stray
      // parameter cannot smuggle a value past a caller comparing it verbatim (frontend #743 SVG
      // sperre) and a malformed header cannot turn into a 500 (see normalizeContentType).
      String contentType = document.getContentType();
      if (contentType == null || contentType.isBlank()) {
        contentType = normalizeContentType(downloaded.contentType());
      }
      if (contentType == null || contentType.isBlank()) {
        contentType = "application/octet-stream";
      }
      // #748 review, nit 3: closes the per-request HttpClient once the response stream (or the
      // failure path below) is done with it, instead of leaking its connection pool/selector
      // thread until the next GC - ResourceHttpMessageConverter closes this stream in a finally
      // block once the response body has been written or the request aborted, so this always runs
      // exactly once.
      HttpClient clientToClose = httpClient;
      InputStream closingStream =
          new FilterInputStream(downloaded.stream()) {
            @Override
            public void close() throws IOException {
              try {
                super.close();
              } finally {
                clientToClose.close();
              }
            }
          };
      return DocumentContent.ofStream(closingStream, document.getFileName(), contentType);
    } catch (BoundedDownloader.AttachmentTooLargeException
        | ProxyAndCredentials.InvalidProxyConfigurationException
        | IOException e) {
      // #267/#747: every one of these is the source declining or being unreachable, never an
      // OPAA-side failure - logged with the technical detail, answered with the same generic
      // German 404 loadContent already uses so a caller cannot distinguish "offline" from any
      // other reason no original is available. RedirectFollowingFetcher.RedirectRejectedException
      // (a foreign-host redirect or protocol downgrade) is an IOException and therefore already
      // covered by the IOException branch here, not caught separately.
      log.warn("Remote document content unavailable: {} ({})", sourceUrl, e.getMessage());
      closeQuietly(httpClient);
      throw new NotFoundException("Für dieses Dokument steht kein Originaldokument zur Verfügung");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      closeQuietly(httpClient);
      throw new NotFoundException("Für dieses Dokument steht kein Originaldokument zur Verfügung");
    }
  }

  /**
   * Normalizes a remote-declared {@code Content-Type} header to its bare {@code type/subtype}
   * essence, dropping every parameter (e.g. {@code charset}) - both so a caller comparing the value
   * verbatim (the frontend's #743 SVG sperre, see {@code documentContent.ts}) cannot be bypassed by
   * a harmless-looking parameter, and so a header the source sends that is not a valid media type
   * at all (garbage, a bare {@code "pdf"}) never reaches {@link
   * org.springframework.http.MediaType#parseMediaType} a second time downstream and turns into a
   * 500 there (#748 review, finding 2a) - {@code null} here simply falls through to {@link
   * #loadRemoteContent}'s own {@code "application/octet-stream"} fallback instead.
   */
  private String normalizeContentType(String rawContentType) {
    if (rawContentType == null || rawContentType.isBlank()) {
      return null;
    }
    try {
      MediaType parsed = MediaType.parseMediaType(rawContentType);
      return new MediaType(parsed.getType(), parsed.getSubtype()).toString();
    } catch (InvalidMediaTypeException e) {
      log.debug("Remote source declared an invalid Content-Type: {}", rawContentType, e);
      return null;
    }
  }

  private void closeQuietly(HttpClient httpClient) {
    if (httpClient != null) {
      httpClient.close();
    }
  }

  /**
   * The {@code FILESYSTEM} counterpart to {@link #uploadedFileIfManagedByThisService} (#736): a
   * {@code FILESYSTEM} document's {@code file_path} may only be served if it actually resolves
   * underneath this library's own configured {@code sourcePath} - not merely inside some
   * operator-managed directory in general, and not at all when {@code sourcePath} is unset (a
   * {@code FILESYSTEM} library's own configuration is missing or was never set, in which case
   * nothing can be considered "the configured index directory").
   *
   * <p>Also re-checks {@code sourcePath} against {@link FilesystemPathAllowlist} (#742 review,
   * finding 2) - {@code KnowledgeLibraryService} enforces this at creation/update time, and {@link
   * io.opaa.indexing.source.filesystem.AsyncIndexingExecutor} enforces it again before every
   * indexing run for exactly the reason {@link FilesystemPathAllowlist}'s own Javadoc gives: the
   * allowlist can be narrowed (or emptied, which disables the {@code FILESYSTEM} sourceType
   * entirely) after a library was created, and a read against a {@code sourcePath} that has since
   * fallen outside it must not silently keep succeeding just because the library once passed
   * validation. Without this check, an operator who disables (or narrows) {@code FILESYSTEM} would
   * still have every previously indexed file readable through this endpoint.
   *
   * <p>Resolves both paths with {@link Path#toRealPath} rather than the lexical {@code
   * toAbsolutePath().normalize()} the allowlist check itself deliberately stops short of (#742
   * review, nit 8): a symlink inside {@code sourcePath} pointing outside it would otherwise pass
   * the {@code startsWith} check below on its lexical path alone. Unlike the allowlist's own
   * lexical boundary - a fast, pre-flight sanity check with no requirement that the path exist yet
   * - this is the point where the file is actually opened and streamed back to an HTTP caller, so
   * resolving symlinks here is required, not merely nice to have. A path that cannot be resolved
   * (already gone from disk) yields {@code null}, which the caller already turns into the same 404
   * it uses for every other "file not there" case.
   */
  private Path filesystemFileIfWithinConfiguredDirectory(
      Document document, KnowledgeLibrary library) {
    if (document.getFilePath() == null || library.getSourcePath() == null) {
      return null;
    }
    if (!filesystemAllowlist.isAllowed(library.getSourcePath())) {
      return null;
    }
    Path candidate = resolveReal(Path.of(document.getFilePath()));
    Path configuredDirectory = resolveReal(Path.of(library.getSourcePath()));
    if (candidate == null || configuredDirectory == null) {
      return null;
    }
    return candidate.startsWith(configuredDirectory) ? candidate : null;
  }

  /**
   * {@link Path#toRealPath()}, or {@code null} if the path does not (or no longer) exist - the
   * exception {@code toRealPath} throws in that case is not a traversal attempt, just the ordinary
   * "file has since disappeared" case {@link #loadContent} already answers with 404.
   */
  private Path resolveReal(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return null;
    }
  }

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

    Path fileManagedByThisService = uploadedFileIfManagedByThisService(document, libraryId);
    UUID chunkFilterDocumentId = document.getId();

    // The row is deleted first, the chunks only afterwards - deliberately the reverse of the order
    // this method used before #614. A concurrent uploadTaskExecutor task finishing the very same
    // document races this method: FileProcessingService#processUploadedFileAsync re-reads the row,
    // writes its chunks, and only then calls DocumentRepository#markIndexed, which affects a row
    // only if it is still there. Deleting the chunks here *before* the row let that task's
    // markIndexed still see (and update) the row while its own transaction was still in-flight,
    // leaving its freshly-written chunks behind after this method's own vectorStore.delete had
    // already run and would never run again - a document gone from the list but still returned by
    // /api/v1/query. Deleting the row first closes that window: by the time this transaction
    // commits, the row is unconditionally gone, so a racing markIndexed (which blocks on the same
    // row until this commits) is guaranteed to affect zero rows and clean up its own chunks itself
    // (see FileProcessingService#processUploadedFileAsync). The vector store delete below then only
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
          try {
            vectorChunkStore.deleteByDocumentId(chunkFilterDocumentId);
          } catch (RuntimeException e) {
            log.error(
                "Failed to remove vector store chunks for deleted document {} - orphaned chunks may"
                    + " remain",
                chunkFilterDocumentId,
                e);
          }
          if (fileManagedByThisService != null) {
            deleteQuietly(fileManagedByThisService);
          }
        });
  }

  /**
   * The file to delete alongside {@code document}'s row, or {@code null} if this service does not
   * own that file and must leave it alone - see the class Javadoc ("{@code deleteDocument} only
   * ever deletes a file this class itself wrote"). Both conditions are required: the {@code
   * sourceType} alone is not proof against a corrupted or foreign {@code file_path}, and a path
   * check alone would not stop a {@code FILESYSTEM} document whose operator-managed file
   * coincidentally lives under the same parent directory.
   */
  private Path uploadedFileIfManagedByThisService(Document document, UUID libraryId) {
    if (document.getSourceType() != DocumentSourceType.UPLOAD || document.getFilePath() == null) {
      return null;
    }
    Path candidate = Path.of(document.getFilePath()).toAbsolutePath().normalize();
    Path libraryUploadDir =
        Paths.get(uploadProperties.storagePath())
            .resolve(libraryId.toString())
            .toAbsolutePath()
            .normalize();
    return candidate.startsWith(libraryUploadDir) ? candidate : null;
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
    if (library.getSourceType() != DocumentSourceType.UPLOAD) {
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
    for (String extension : SupportedDocumentFormats.extensions()) {
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
    if (!SupportedDocumentFormats.contentMatchesExtension(extension, detectedMimeType)) {
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
