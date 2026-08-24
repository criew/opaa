package io.opaa.library;

import io.opaa.api.dto.LibraryFolderRenameRequest;
import io.opaa.api.dto.LibraryFolderRequest;
import io.opaa.api.dto.LibraryFolderResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.DocumentSourceType;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Creates, renames and (recursively) deletes {@link LibraryFolder}s (#820, Epic #520 Phase 2,
 * ADR-0020) - the CRUD counterpart to {@link LibraryDocumentService}'s document upload/delete,
 * sharing its permission floor ({@link AssetRole#EDITOR}, see {@link #requireEditable}) and its
 * "only an UPLOAD library accepts this" restriction (see {@link #requireUploadLibrary}): a
 * FILESYSTEM library's folders will be read-only, derived from the crawled directory structure
 * itself in a later task (#824), not editable through this service.
 *
 * <p><b>Deletion is recursive and runs through the application layer, never a database cascade</b>
 * (ADR-0020, Entscheidung 5): {@link #deleteFolder} walks the folder's subtree leaf-first, deleting
 * every contained document through {@link LibraryDocumentService#deleteDocument} - the same
 * file/chunk/row cleanup a single document's own deletion already performs - before removing each
 * folder row. {@code fk_documents_folder}/{@code fk_library_folders_parent} (migration 062) are
 * both {@code RESTRICT}, turning any violation of that leaf-first order into a loud database error
 * instead of a silent orphan.
 */
@Service
@Transactional(readOnly = true)
public class LibraryFolderService {

  private static final int MAX_NAME_LENGTH = 255;

  /**
   * Caps how deeply folders may nest (root counts as depth 1). Not specified by #820's acceptance
   * criteria beyond "Tiefenlimit" - chosen generously enough for any realistic Aktenplan while
   * still bounding the recursive {@link #depthOfParentChain}/{@link #countDocumentsRecursive}/
   * {@link #deleteRecursive} walks this class performs.
   */
  private static final int MAX_DEPTH = 10;

  private final LibraryFolderRepository folderRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final UserRepository userRepository;
  private final LibraryAccessService accessService;
  private final DocumentRepository documentRepository;
  private final LibraryDocumentService documentService;

  public LibraryFolderService(
      LibraryFolderRepository folderRepository,
      KnowledgeLibraryRepository libraryRepository,
      UserRepository userRepository,
      LibraryAccessService accessService,
      DocumentRepository documentRepository,
      LibraryDocumentService documentService) {
    this.folderRepository = folderRepository;
    this.libraryRepository = libraryRepository;
    this.userRepository = userRepository;
    this.accessService = accessService;
    this.documentRepository = documentRepository;
    this.documentService = documentService;
  }

  @Transactional
  public LibraryFolderResponse createFolder(
      UUID libraryId, LibraryFolderRequest request, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = loadLibrary(libraryId, currentUserId);
    requireEditable(library, currentUserId, systemAdmin);
    requireUploadLibrary(library);

    String name = validateName(request.getName());
    UUID parentFolderId = request.getParentFolderId();
    resolveParent(libraryId, parentFolderId);
    int depth = depthOfParentChain(parentFolderId) + 1;
    if (depth > MAX_DEPTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Die Ordnerstruktur ist zu tief verschachtelt (maximal " + MAX_DEPTH + " Ebenen)");
    }

    ensureNameAvailable(libraryId, parentFolderId, name, null);

    LibraryFolder folder =
        new LibraryFolder(libraryId, parentFolderId, name, library.getOrganizationId());
    try {
      // saveAndFlush, not save (review finding #827): LibraryFolder assigns its own @Id in the
      // constructor, so a plain save's INSERT does not have to happen before this method returns -
      // Hibernate is free to defer it to the next flush, which could be well outside this try block
      // (e.g. at transaction commit, or the next query). A deferred INSERT would let the unique
      // index violation from a race ensureNameAvailable above missed surface as an unhandled
      // DataIntegrityViolationException/ConstraintViolationException far from here, turning into a
      // 500 instead of the 409 this catch is meant to produce - flushing here forces the INSERT
      // (and
      // therefore uk_library_folders_root_name/uk_library_folders_child_name, migration 062) to run
      // and either succeed or throw inside this try.
      folder = folderRepository.saveAndFlush(folder);
    } catch (DataIntegrityViolationException e) {
      // Race-safety net for ensureNameAvailable above (mirrors LibraryDocumentService#
      // uploadDocument's identical handling of uk_documents_library_checksum): the check and this
      // insert are two separate statements with no database guarantee between them, so two
      // concurrent creates of the same name on the same level could both pass it.
      // uk_library_folders_root_name/uk_library_folders_child_name (migration 062) are the actual
      // guarantee; this maps their violation to the same 409 the sequential check already
      // produces.
      throw conflict();
    }
    return toResponse(folder);
  }

  @Transactional
  public LibraryFolderResponse renameFolder(
      UUID libraryId,
      UUID folderId,
      LibraryFolderRenameRequest request,
      UUID currentUserId,
      boolean systemAdmin) {
    KnowledgeLibrary library = loadLibrary(libraryId, currentUserId);
    requireEditable(library, currentUserId, systemAdmin);
    requireUploadLibrary(library);

    LibraryFolder folder = loadFolder(libraryId, folderId);
    String name = validateName(request.getName());
    ensureNameAvailable(libraryId, folder.getParentFolderId(), name, folder.getId());

    folder.rename(name);
    try {
      // saveAndFlush - see createFolder's identical reasoning above.
      folder = folderRepository.saveAndFlush(folder);
    } catch (DataIntegrityViolationException e) {
      throw conflict();
    }
    return toResponse(folder);
  }

  public LibraryFolderResponse getFolder(
      UUID libraryId, UUID folderId, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = loadLibrary(libraryId, currentUserId);
    accessService.requireRole(library, currentUserId, systemAdmin, AssetRole.VIEWER);
    LibraryFolder folder = loadFolder(libraryId, folderId);
    return toResponse(folder);
  }

  @Transactional
  public void deleteFolder(UUID libraryId, UUID folderId, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = loadLibrary(libraryId, currentUserId);
    requireEditable(library, currentUserId, systemAdmin);
    requireUploadLibrary(library);

    LibraryFolder folder = loadFolder(libraryId, folderId);
    deleteRecursive(libraryId, folder, currentUserId, systemAdmin);
  }

  /**
   * Depth-first, leaf-first: every subfolder (and everything below it) is fully removed before this
   * folder's own documents are deleted, and this folder's own documents are removed before its row
   * - the order {@code fk_library_folders_parent}/{@code fk_documents_folder}'s {@code RESTRICT}
   * constraints require (see this class's Javadoc).
   */
  private void deleteRecursive(
      UUID libraryId, LibraryFolder folder, UUID currentUserId, boolean systemAdmin) {
    for (LibraryFolder child :
        folderRepository.findByLibraryIdAndParentFolderIdOrderByNameAsc(
            libraryId, folder.getId())) {
      deleteRecursive(libraryId, child, currentUserId, systemAdmin);
    }
    for (Document document : documentRepository.findByFolderId(folder.getId())) {
      documentService.deleteDocument(libraryId, document.getId(), currentUserId, systemAdmin);
    }
    folderRepository.delete(folder);
  }

  private long countDocumentsRecursive(UUID libraryId, UUID folderId) {
    long count = documentRepository.countByFolderId(folderId);
    for (LibraryFolder child :
        folderRepository.findByLibraryIdAndParentFolderIdOrderByNameAsc(libraryId, folderId)) {
      count += countDocumentsRecursive(libraryId, child.getId());
    }
    return count;
  }

  private void ensureNameAvailable(
      UUID libraryId, UUID parentFolderId, String name, UUID excludedFolderId) {
    boolean exists =
        parentFolderId == null
            ? (excludedFolderId == null
                    ? folderRepository.findByLibraryIdAndParentFolderIdIsNullAndName(
                        libraryId, name)
                    : folderRepository.findByLibraryIdAndParentFolderIdIsNullAndNameAndIdNot(
                        libraryId, name, excludedFolderId))
                .isPresent()
            : (excludedFolderId == null
                    ? folderRepository.findByLibraryIdAndParentFolderIdAndName(
                        libraryId, parentFolderId, name)
                    : folderRepository.findByLibraryIdAndParentFolderIdAndNameAndIdNot(
                        libraryId, parentFolderId, name, excludedFolderId))
                .isPresent();
    if (exists) {
      throw conflict();
    }
  }

  private ResponseStatusException conflict() {
    return new ResponseStatusException(
        HttpStatus.CONFLICT, "Ein Ordner mit diesem Namen existiert bereits auf dieser Ebene");
  }

  /**
   * Validates {@code parentFolderId} references an existing folder in the same library - treats a
   * parent from another library the same as one that does not exist, mirroring {@link
   * #loadLibrary}'s cross-organization treatment.
   */
  private void resolveParent(UUID libraryId, UUID parentFolderId) {
    if (parentFolderId == null) {
      return;
    }
    LibraryFolder parent =
        folderRepository
            .findById(parentFolderId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Übergeordneter Ordner nicht gefunden"));
    if (!parent.getLibraryId().equals(libraryId)) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Übergeordneter Ordner nicht gefunden");
    }
  }

  /**
   * The nesting depth of {@code parentFolderId} (0 for the library's root, i.e. {@code null}) -
   * used to reject a create that would push a new folder past {@link #MAX_DEPTH}. Bounded at {@code
   * MAX_DEPTH + 1} iterations as a defensive guard against an unexpected cycle in stored data (this
   * class's own create path can never produce one - a freshly created folder cannot become its own
   * ancestor - but a corrupted/foreign write should fail loudly here rather than loop forever).
   */
  private int depthOfParentChain(UUID parentFolderId) {
    int depth = 0;
    UUID current = parentFolderId;
    while (current != null) {
      depth++;
      if (depth > MAX_DEPTH + 1) {
        throw new IllegalStateException("Unexpected folder cycle detected at " + parentFolderId);
      }
      LibraryFolder folder =
          folderRepository
              .findById(current)
              .orElseThrow(
                  () -> new IllegalStateException("Folder chain references a missing row"));
      current = folder.getParentFolderId();
    }
    return depth;
  }

  private String validateName(String name) {
    if (name == null || name.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name ist erforderlich");
    }
    String trimmed = name.trim();
    if (trimmed.length() > MAX_NAME_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "name darf höchstens " + MAX_NAME_LENGTH + " Zeichen umfassen");
    }
    if (trimmed.contains("/")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name darf kein \"/\" enthalten");
    }
    return trimmed;
  }

  private LibraryFolder loadFolder(UUID libraryId, UUID folderId) {
    LibraryFolder folder =
        folderRepository
            .findById(folderId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ordner nicht gefunden"));
    if (!folder.getLibraryId().equals(libraryId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ordner nicht gefunden");
    }
    return folder;
  }

  private LibraryFolderResponse toResponse(LibraryFolder folder) {
    long documentCount = countDocumentsRecursive(folder.getLibraryId(), folder.getId());
    return new LibraryFolderResponse(
            folder.getId(),
            folder.getLibraryId(),
            folder.getName(),
            documentCount,
            folder.getCreatedAt())
        .parentFolderId(folder.getParentFolderId());
  }

  /**
   * ADR-0020: only an {@code UPLOAD} library accepts folder creation/rename/deletion through this
   * service - a {@code FILESYSTEM} library's folders will mirror its crawled directory structure
   * automatically (#824), and {@code HTTP_DIRECTORY}/{@code RSS_FEED} have no directory concept at
   * all. {@code 409} rather than {@code 400}, mirroring {@code LibraryDocumentService#
   * requireUploadLibrary}: the request is well-formed, it simply conflicts with this library's
   * fixed, immutable source type.
   */
  private void requireUploadLibrary(KnowledgeLibrary library) {
    if (library.getSourceType() != DocumentSourceType.UPLOAD) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Diese Bibliothek ist eine Konnektorbibliothek und unterstützt keine manuell verwalteten"
              + " Ordner");
    }
  }

  /** Requires {@link AssetRole#EDITOR}, the same floor upload/delete of a document already uses. */
  private void requireEditable(KnowledgeLibrary library, UUID currentUserId, boolean systemAdmin) {
    accessService.requireRole(library, currentUserId, systemAdmin, AssetRole.EDITOR);
  }

  /**
   * Loads a library and enforces the organization boundary, treating a library from another
   * organization as not found - mirrors {@code KnowledgeLibraryService#loadLibrary}/{@code
   * LibraryDocumentService#loadLibrary}.
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
}
