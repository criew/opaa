package io.opaa.library;

import io.opaa.api.dto.LibraryDocumentResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.indexing.ChecksumService;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.indexing.DocumentStatus;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.SupportedDocumentFormats;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

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
  private final UserRepository userRepository;
  private final LibraryAccessService accessService;
  private final DocumentRepository documentRepository;
  private final ChecksumService checksumService;
  private final FileProcessingService fileProcessingService;
  private final VectorStore vectorStore;
  private final UploadProperties uploadProperties;

  public LibraryDocumentService(
      KnowledgeLibraryRepository libraryRepository,
      UserRepository userRepository,
      LibraryAccessService accessService,
      DocumentRepository documentRepository,
      ChecksumService checksumService,
      FileProcessingService fileProcessingService,
      VectorStore vectorStore,
      UploadProperties uploadProperties) {
    this.libraryRepository = libraryRepository;
    this.userRepository = userRepository;
    this.accessService = accessService;
    this.documentRepository = documentRepository;
    this.checksumService = checksumService;
    this.fileProcessingService = fileProcessingService;
    this.vectorStore = vectorStore;
    this.uploadProperties = uploadProperties;
  }

  public LibraryDocumentResponse uploadDocument(
      UUID libraryId, MultipartFile file, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = loadLibrary(libraryId, currentUserId);
    requireEditable(library, currentUserId, systemAdmin);
    requireUploadLibrary(library);

    if (file == null || file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datei ist erforderlich");
    }
    if (file.getSize() > uploadProperties.maxFileSize()) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE,
          "Die Datei ist zu groß. Erlaubt sind höchstens "
              + (uploadProperties.maxFileSize() / (1024 * 1024))
              + " MB");
    }

    String displayFileName = sanitizeDisplayFileName(file.getOriginalFilename());
    if (!SupportedDocumentFormats.isSupported(displayFileName)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Das Dateiformat wird nicht unterstützt. Erlaubt sind: "
              + String.join(", ", SupportedDocumentFormats.extensions()));
    }
    String extension = matchedExtension(displayFileName);

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
          throw new ResponseStatusException(
              HttpStatus.CONFLICT, "Diese Datei ist bereits in dieser Bibliothek vorhanden");
        }
        // #589 review, item 3: a FAILED row must not block a retry of the same file forever - the
        // per-library dedup check above (and uk_documents_library_checksum, migration 020) can't
        // otherwise tell "this content already succeeded" from "this content failed once and the
        // user is trying again", so it replaces the old FAILED row instead of rejecting the new
        // upload. It should never have surviving chunks (FileProcessingService cleans those up on
        // every failure path), but the delete is unconditional anyway, mirroring processFile's own
        // re-index cleanup - defence in depth costs nothing here.
        Path oldFailedFile = uploadedFileIfManagedByThisService(existingDoc, libraryId);
        vectorStore.delete("document_id == '" + existingDoc.getId() + "'");
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

      return LibraryDocumentResponses.from(document);
    } catch (DataIntegrityViolationException e) {
      // Race-safety net for the findByLibraryIdAndChecksum check above (#420 code review, nit 5):
      // that check and the eventual INSERT are two separate steps with no database guarantee
      // between them, so two concurrent uploads of the same file into the same library could both
      // pass it. uk_documents_library_checksum (migration 020) is the actual guarantee; this maps
      // its violation to the same 409 the sequential check already produces.
      deleteQuietly(storedFile);
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Diese Datei ist bereits in dieser Bibliothek vorhanden");
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
  private LibraryDocumentResponse failAlreadyPersistedUpload(
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
    return LibraryDocumentResponses.from(document);
  }

  @Transactional
  public void deleteDocument(
      UUID libraryId, UUID documentId, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = loadLibrary(libraryId, currentUserId);
    requireEditable(library, currentUserId, systemAdmin);

    Document document =
        documentRepository
            .findById(documentId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dokument nicht gefunden"));
    // Treats a document from another library the same as one that does not exist at all - the same
    // reasoning KnowledgeLibraryService#loadLibrary applies across organizations.
    if (!document.getLibraryId().equals(libraryId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dokument nicht gefunden");
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
            vectorStore.delete("document_id == '" + chunkFilterDocumentId + "'");
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
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Diese Bibliothek ist eine Konnektorbibliothek und akzeptiert keine manuellen Uploads");
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
  private KnowledgeLibrary loadLibrary(UUID libraryId, UUID currentUserId) {
    User currentUser = requireUser(currentUserId);
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Bibliothek nicht gefunden"));
    if (!library.getOrganizationId().equals(currentUser.getOrganizationId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bibliothek nicht gefunden");
    }
    return library;
  }

  private User requireUser(UUID userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Benutzer nicht gefunden"));
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
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Der Inhalt der Datei entspricht nicht dem Format " + extension);
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
