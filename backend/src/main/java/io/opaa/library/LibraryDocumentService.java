package io.opaa.library;

import io.opaa.api.dto.LibraryDocumentResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.indexing.ChecksumService;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.SupportedDocumentFormats;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Uploads documents into, and removes them from, a {@link KnowledgeLibrary} via the REST API (#420,
 * docs/features/knowledge-sources.md#upload) - the human counterpart to the connector/crawl
 * ingestion paths {@code FileProcessingService} already serves. Both mutating methods here require
 * {@link AssetRole#EDITOR} on the target library (see {@link LibraryAccessService#canEdit}), one
 * level below the {@code MANAGER} the library-configuration endpoints require - a person may add or
 * remove content without being allowed to change who else can.
 *
 * <p>Reuses the existing indexing pipeline deliberately: {@link #uploadDocument} stores the
 * incoming bytes and computes their checksum itself (both are specific to how this endpoint decides
 * *where* a file goes and whether it is a duplicate *within its target library* - a different
 * question from what {@code FileProcessingService#processFile}'s file-path-keyed dedup answers),
 * then hands off to {@link FileProcessingService#processUploadedFile} for parsing, chunking and
 * vector storage - the same three steps every other ingestion path goes through, so a chat query
 * finds an uploaded document exactly the same way it finds a crawled one.
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

    if (file == null || file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datei ist erforderlich");
    }
    if (file.getSize() > uploadProperties.maxFileSize()) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE,
          "Die Datei ist zu gross. Erlaubt sind hoechstens "
              + (uploadProperties.maxFileSize() / (1024 * 1024))
              + " MB");
    }

    String displayFileName = sanitizeDisplayFileName(file.getOriginalFilename());
    if (!SupportedDocumentFormats.isSupported(displayFileName)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Das Dateiformat wird nicht unterstuetzt. Erlaubt sind: "
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

      String checksum = checksumService.computeSha256(storedFile);
      // Dedup is scoped per library (#420 acceptance criteria): the same file uploaded into two
      // different libraries is two legitimate documents, only a second copy inside the *same*
      // library is rejected.
      if (documentRepository.findByLibraryIdAndChecksum(libraryId, checksum).isPresent()) {
        Files.deleteIfExists(storedFile);
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Diese Datei ist bereits in dieser Bibliothek vorhanden");
      }

      Document document =
          fileProcessingService.processUploadedFile(
              storedFile,
              displayFileName,
              checksum,
              libraryId,
              library.getOrganizationId(),
              currentUserId);
      return LibraryDocumentResponses.from(document);
    } catch (IOException e) {
      deleteQuietly(storedFile);
      throw new UncheckedIOException("Datei konnte nicht gespeichert werden", e);
    } catch (RuntimeException e) {
      deleteQuietly(storedFile);
      throw e;
    }
  }

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

    vectorStore.delete("document_id == '" + document.getId() + "'");
    documentRepository.delete(document);
    if (document.getFilePath() != null) {
      deleteQuietly(Path.of(document.getFilePath()));
    }
  }

  private void requireEditable(KnowledgeLibrary library, UUID currentUserId, boolean systemAdmin) {
    if (!accessService.canEdit(library, currentUserId, systemAdmin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
    }
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

  private void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      log.warn("Could not delete file {}", path, e);
    }
  }
}
