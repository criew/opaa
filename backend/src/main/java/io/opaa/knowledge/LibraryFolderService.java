package io.opaa.knowledge;

import io.opaa.api.types.AssetRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates, renames and recursively deletes {@link LibraryFolder}s (ADR-0020) of an upload library,
 * with {@link AssetRole#EDITOR}. {@link #materializeFolderPath} and {@link #pruneOrphanedFolders}
 * mirror a connector's directory structure on the system's behalf, without those checks. Deletion
 * runs leaf-first through {@link FolderDocumentDeleter}, never a database cascade; the {@code
 * RESTRICT} foreign keys turn a wrong order into a loud error (ADR-0020, Entscheidung 5).
 */
@Service
@Transactional(readOnly = true)
public class LibraryFolderService {

  private static final int MAX_NAME_LENGTH = 255;

  /**
   * How deeply folders may nest (root counts as depth 1): enough for a realistic Aktenplan, and a
   * bound for the recursive walks of this class.
   */
  public static final int MAX_DEPTH = 10;

  private final LibraryFolderRepository folderRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService accessService;
  private final DocumentRepository documentRepository;
  private final FolderDocumentDeleter documentService;
  private final TransactionTemplate requiresNewTransactionTemplate;

  public LibraryFolderService(
      LibraryFolderRepository folderRepository,
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService accessService,
      DocumentRepository documentRepository,
      // The implementation depends on this class too, a constructor cycle Spring cannot resolve;
      // @Lazy breaks it on this side. The only use (deleteRecursive) runs after both beans exist.
      @Lazy FolderDocumentDeleter documentService,
      PlatformTransactionManager transactionManager) {
    this.folderRepository = folderRepository;
    this.libraryRepository = libraryRepository;
    this.accessService = accessService;
    this.documentRepository = documentRepository;
    this.documentService = documentService;
    // Concurrent, request-driven callers race on the folder insert: see materializeSingleFolder's
    // own comment for why the insert attempt needs its own REQUIRES_NEW transaction rather than
    // running inside the caller's ambient one - mirrors ChatService/SpaceService's identical
    // constructor-built TransactionTemplate.
    this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
    this.requiresNewTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Transactional
  public LibraryFolderDetail createFolder(
      UUID libraryId, String requestedName, UUID parentFolderId, CurrentUser caller) {
    KnowledgeLibrary library =
        libraryRepository.requireInOrganization(libraryId, caller.organizationId());
    requireEditable(library, caller.id(), caller.isSystemAdmin());
    requireUploadLibrary(library);

    String name = validateName(requestedName);
    resolveParent(libraryId, parentFolderId);
    int depth = depthOfParentChain(parentFolderId) + 1;
    if (depth > MAX_DEPTH) {
      throw new ValidationException(
          "Die Ordnerstruktur ist zu tief verschachtelt (maximal " + MAX_DEPTH + " Ebenen)");
    }

    ensureNameAvailable(libraryId, parentFolderId, name, null);

    LibraryFolder folder =
        new LibraryFolder(libraryId, parentFolderId, name, library.getOrganizationId());
    try {
      // saveAndFlush: the entity assigns its own @Id, so a plain save could defer the INSERT and a
      // unique-name violation would surface outside this try as a 500.
      folder = folderRepository.saveAndFlush(folder);
    } catch (DataIntegrityViolationException e) {
      // The unique indexes settle a concurrent create of the same name; same 409 as the check.
      throw conflict();
    }
    return toDetail(folder);
  }

  @Transactional
  public LibraryFolderDetail renameFolder(
      UUID libraryId, UUID folderId, String requestedName, CurrentUser caller) {
    KnowledgeLibrary library =
        libraryRepository.requireInOrganization(libraryId, caller.organizationId());
    requireEditable(library, caller.id(), caller.isSystemAdmin());
    requireUploadLibrary(library);

    LibraryFolder folder = loadFolder(libraryId, folderId);
    String name = validateName(requestedName);
    ensureNameAvailable(libraryId, folder.getParentFolderId(), name, folder.getId());

    folder.rename(name);
    try {
      // saveAndFlush - see createFolder's identical reasoning above.
      folder = folderRepository.saveAndFlush(folder);
    } catch (DataIntegrityViolationException e) {
      throw conflict();
    }
    return toDetail(folder);
  }

  public LibraryFolderDetail getFolder(UUID libraryId, UUID folderId, CurrentUser caller) {
    KnowledgeLibrary library =
        libraryRepository.requireInOrganization(libraryId, caller.organizationId());
    accessService.requireRole(library, caller.id(), caller.isSystemAdmin(), AssetRole.VIEWER);
    LibraryFolder folder = loadFolder(libraryId, folderId);
    return toDetail(folder);
  }

  @Transactional
  public void deleteFolder(UUID libraryId, UUID folderId, CurrentUser caller) {
    KnowledgeLibrary library =
        libraryRepository.requireInOrganization(libraryId, caller.organizationId());
    requireEditable(library, caller.id(), caller.isSystemAdmin());
    requireUploadLibrary(library);

    LibraryFolder folder = loadFolder(libraryId, folderId);
    deleteRecursive(libraryId, folder, caller);
  }

  /**
   * Depth-first, leaf-first: every subfolder (and everything below it) is fully removed before this
   * folder's own documents are deleted, and this folder's own documents are removed before its row
   * - the order {@code fk_library_folders_parent}/{@code fk_documents_folder}'s {@code RESTRICT}
   * constraints require (see this class's Javadoc).
   */
  private void deleteRecursive(UUID libraryId, LibraryFolder folder, CurrentUser caller) {
    for (LibraryFolder child :
        folderRepository.findByLibraryIdAndParentFolderIdOrderByNameAsc(
            libraryId, folder.getId())) {
      deleteRecursive(libraryId, child, caller);
    }
    for (Document document : documentRepository.findByFolderId(folder.getId())) {
      documentService.deleteDocument(libraryId, document.getId(), caller);
    }
    folderRepository.delete(folder);
  }

  /**
   * Materializes the folder chain for {@code segments} idempotently, reusing existing folders. Name
   * rules and {@link #MAX_DEPTH} do not apply: a mirrored tree is represented as-is, each run
   * judges its segments through {@code SourceFolderPath}. Two overlapping runs can fail one
   * document on {@code fk_documents_folder}; the next run heals it.
   *
   * @param segments the path components between the library's {@code sourcePath} and the file
   *     itself, outermost first; an empty list means the library's root
   * @return the id of the deepest folder in {@code segments}, or {@code null} for an empty list
   * @throws IllegalArgumentException for an {@code UPLOAD} library - the guard protects its
   *     CRUD-managed folder tree from being written by an indexing run
   */
  @Transactional
  public UUID materializeFolderPath(KnowledgeLibrary library, List<String> segments) {
    requireMirroredSourceLibrary(library);
    UUID parentFolderId = null;
    for (String name : segments) {
      parentFolderId = materializeSingleFolder(library, parentFolderId, name);
    }
    return parentFolderId;
  }

  /** Mirroring never touches an upload library, whose folders are managed by hand. */
  private void requireMirroredSourceLibrary(KnowledgeLibrary library) {
    if (SourceType.UPLOAD.equals(library.getSourceType())) {
      throw new IllegalArgumentException(
          "materializeFolderPath/pruneOrphanedFolders is only valid for a library whose folders"
              + " mirror a source directory structure, got "
              + library.getSourceType());
    }
  }

  /**
   * The insert runs in its own {@code REQUIRES_NEW} transaction: a unique violation aborts the
   * whole Postgres transaction it occurs in, and the lookup of the concurrent winner must run in
   * the still-healthy outer one.
   */
  private UUID materializeSingleFolder(KnowledgeLibrary library, UUID parentFolderId, String name) {
    Optional<LibraryFolder> existing = findByParentAndName(library.getId(), parentFolderId, name);
    if (existing.isPresent()) {
      return existing.get().getId();
    }
    try {
      return requiresNewTransactionTemplate.execute(
          status -> {
            LibraryFolder folder =
                new LibraryFolder(
                    library.getId(), parentFolderId, name, library.getOrganizationId());
            // saveAndFlush: a concurrent insert's unique violation surfaces inside this try.
            return folderRepository.saveAndFlush(folder).getId();
          });
    } catch (DataIntegrityViolationException e) {
      // A concurrent materialization won; reuse its row, read in the resumed outer transaction.
      return findByParentAndName(library.getId(), parentFolderId, name)
          .map(LibraryFolder::getId)
          .orElseThrow(() -> e);
    }
  }

  /**
   * Resolves the folder chain {@code pathSegments} below {@code baseFolderId} in an upload library,
   * creating missing folders and reusing existing ones, with every check of {@link #createFolder}.
   *
   * @param baseFolderId the folder {@code pathSegments} is relative to; {@code null} means the
   *     library's root, mirroring {@link #createFolder}'s own {@code parentFolderId}
   * @return the id of the deepest folder in {@code pathSegments}, or {@code baseFolderId} unchanged
   *     for an empty list
   */
  @Transactional
  public UUID resolveOrCreateFolderPath(
      UUID libraryId, UUID baseFolderId, List<String> pathSegments, CurrentUser caller) {
    KnowledgeLibrary library =
        libraryRepository.requireInOrganization(libraryId, caller.organizationId());
    requireEditable(library, caller.id(), caller.isSystemAdmin());
    requireUploadLibrary(library);
    resolveParent(libraryId, baseFolderId);

    // All segments and the depth are validated before the first row: each insert commits on its
    // own, so a later invalid segment would leave a partial chain behind.
    List<String> names = new ArrayList<>(pathSegments.size());
    int depth = baseFolderId == null ? 0 : depthOfParentChain(baseFolderId);
    for (String rawSegment : pathSegments) {
      names.add(validatePathSegment(rawSegment));
      depth++;
      if (depth > MAX_DEPTH) {
        throw new ValidationException(
            "Die Ordnerstruktur ist zu tief verschachtelt (maximal " + MAX_DEPTH + " Ebenen)");
      }
    }

    UUID parentFolderId = baseFolderId;
    for (String name : names) {
      parentFolderId = materializeSingleFolder(library, parentFolderId, name);
    }
    return parentFolderId;
  }

  /**
   * {@link #validateName} plus the extra checks a path segment needs beyond a single, manually
   * typed folder name: no {@code "\"} (a Windows-style separator {@code validateName}'s own {@code
   * "/"} check does not catch) and no {@code ".."}/{@code "."} (a relative-path traversal segment
   * that means something other than a literal folder name).
   */
  private String validatePathSegment(String rawSegment) {
    String name = validateName(rawSegment);
    if (name.contains("\\")) {
      throw new ValidationException("Ordnername darf kein \"\\\" enthalten");
    }
    if (name.equals("..") || name.equals(".")) {
      throw new ValidationException("Ordnername darf nicht \"..\" oder \".\" lauten");
    }
    return name;
  }

  private Optional<LibraryFolder> findByParentAndName(
      UUID libraryId, UUID parentFolderId, String name) {
    return parentFolderId == null
        ? folderRepository.findByLibraryIdAndParentFolderIdIsNullAndName(libraryId, name)
        : folderRepository.findByLibraryIdAndParentFolderIdAndName(libraryId, parentFolderId, name);
  }

  /**
   * Removes, leaf-first, every folder absent from {@code currentFolderIds} that is empty, also
   * transitively. Callers remove vanished documents first, so a folder holding only those is empty.
   *
   * @throws IllegalArgumentException for an upload library
   */
  @Transactional
  public void pruneOrphanedFolders(KnowledgeLibrary library, Set<UUID> currentFolderIds) {
    requireMirroredSourceLibrary(library);
    UUID libraryId = library.getId();
    List<LibraryFolder> all = folderRepository.findByLibraryId(libraryId);
    // Built by hand, not via Collectors.groupingBy: groupingBy's classifier is required to return a
    // non-null key, but LibraryFolder#getParentFolderId is null for exactly the root-level folders
    // this method must also walk - java.util.HashMap itself has no such restriction, so a plain
    // computeIfAbsent loop handles the null-parent (root) case the same way as every other parent
    // id.
    Map<UUID, List<LibraryFolder>> childrenByParent = new HashMap<>();
    for (LibraryFolder folder : all) {
      childrenByParent
          .computeIfAbsent(folder.getParentFolderId(), key -> new ArrayList<>())
          .add(folder);
    }
    for (LibraryFolder root : childrenByParent.getOrDefault(null, List.of())) {
      pruneRecursive(root, childrenByParent, currentFolderIds);
    }
  }

  /**
   * @return whether {@code folder} itself was removed
   */
  private boolean pruneRecursive(
      LibraryFolder folder,
      Map<UUID, List<LibraryFolder>> childrenByParent,
      Set<UUID> currentFolderIds) {
    boolean everyChildRemoved = true;
    for (LibraryFolder child : childrenByParent.getOrDefault(folder.getId(), List.of())) {
      if (!pruneRecursive(child, childrenByParent, currentFolderIds)) {
        everyChildRemoved = false;
      }
    }
    if (!everyChildRemoved
        || currentFolderIds.contains(folder.getId())
        || documentRepository.countByFolderId(folder.getId()) > 0) {
      return false;
    }
    folderRepository.delete(folder);
    return true;
  }

  private long countDocumentsRecursive(UUID libraryId, UUID folderId) {
    long count = documentRepository.countByFolderIdAndParentDocumentIdIsNull(folderId);
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

  private ConflictException conflict() {
    return new ConflictException("Ein Ordner mit diesem Namen existiert bereits auf dieser Ebene");
  }

  /**
   * Validates {@code parentFolderId} references an existing folder in the same library - treats a
   * parent from another library the same as one that does not exist, mirroring {@link
   * KnowledgeLibraryRepository#requireInOrganization}'s cross-organization treatment.
   */
  private void resolveParent(UUID libraryId, UUID parentFolderId) {
    if (parentFolderId == null) {
      return;
    }
    LibraryFolder parent =
        folderRepository
            .findById(parentFolderId)
            .orElseThrow(() -> new NotFoundException("Übergeordneter Ordner nicht gefunden"));
    if (!parent.getLibraryId().equals(libraryId)) {
      throw new NotFoundException("Übergeordneter Ordner nicht gefunden");
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
      throw new ValidationException("name ist erforderlich");
    }
    String trimmed = name.trim();
    if (trimmed.length() > MAX_NAME_LENGTH) {
      throw new ValidationException("name darf höchstens " + MAX_NAME_LENGTH + " Zeichen umfassen");
    }
    if (trimmed.contains("/")) {
      throw new ValidationException("name darf kein \"/\" enthalten");
    }
    return trimmed;
  }

  private LibraryFolder loadFolder(UUID libraryId, UUID folderId) {
    LibraryFolder folder =
        folderRepository
            .findById(folderId)
            .orElseThrow(() -> new NotFoundException("Ordner nicht gefunden"));
    if (!folder.getLibraryId().equals(libraryId)) {
      throw new NotFoundException("Ordner nicht gefunden");
    }
    return folder;
  }

  private LibraryFolderDetail toDetail(LibraryFolder folder) {
    long documentCount = countDocumentsRecursive(folder.getLibraryId(), folder.getId());
    return new LibraryFolderDetail(folder, documentCount);
  }

  /**
   * Only an upload library takes folder changes through this service (ADR-0020); {@code 409},
   * because the request conflicts with the library's fixed source type.
   */
  private void requireUploadLibrary(KnowledgeLibrary library) {
    if (!SourceType.UPLOAD.equals(library.getSourceType())) {
      throw new ConflictException(
          "Diese Bibliothek ist eine Konnektorbibliothek und unterstützt keine manuell verwalteten"
              + " Ordner");
    }
  }

  /** Requires {@link AssetRole#EDITOR}, the same floor upload/delete of a document already uses. */
  private void requireEditable(KnowledgeLibrary library, UUID currentUserId, boolean systemAdmin) {
    accessService.requireRole(library, currentUserId, systemAdmin, AssetRole.EDITOR);
  }
}
