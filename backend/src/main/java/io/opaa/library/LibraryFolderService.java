package io.opaa.library;

import io.opaa.api.dto.LibraryFolderRenameRequest;
import io.opaa.api.dto.LibraryFolderRequest;
import io.opaa.api.dto.LibraryFolderResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.DocumentSourceType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Creates, renames and (recursively) deletes {@link LibraryFolder}s (#820, Epic #520 Phase 2,
 * ADR-0020) - the CRUD counterpart to {@link LibraryDocumentService}'s document upload/delete,
 * sharing its permission floor ({@link AssetRole#EDITOR}, see {@link #requireEditable}) and its
 * "only an UPLOAD library accepts this" restriction (see {@link #requireUploadLibrary}): a
 * FILESYSTEM library's folders will be read-only, derived from the crawled directory structure
 * itself in a later task (#824), not editable through this service.
 *
 * <p><b>#824 (Epic #520 Phase 4) is that later task:</b> {@link #materializeFolderPath} and {@link
 * #pruneOrphanedFolders} are the internal counterparts a FILESYSTEM indexing run uses to mirror its
 * source directory structure - deliberately bypassing {@link #requireUploadLibrary} (a FILESYSTEM
 * library's folders are meant to be created this way, not blocked) and {@link #requireEditable} (an
 * indexing job acts on the system's own behalf, there is no request-scoped caller/role to check).
 * Neither method is reachable through {@link io.opaa.api.LibraryController} - only {@code
 * io.opaa.indexing.AsyncIndexingExecutor} calls them.
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
  private final TransactionTemplate requiresNewTransactionTemplate;

  public LibraryFolderService(
      LibraryFolderRepository folderRepository,
      KnowledgeLibraryRepository libraryRepository,
      UserRepository userRepository,
      LibraryAccessService accessService,
      DocumentRepository documentRepository,
      // #823: LibraryDocumentService now depends on this class too (uploadDocument's folderPath
      // materializes a folder chain via resolveOrCreateFolderPath below), which would otherwise be
      // a genuine constructor-injection cycle Spring cannot resolve. @Lazy breaks it on this side -
      // the only use of documentService here (deleteRecursive, below) runs long after both beans
      // are fully constructed, so a lazy proxy costs nothing at the one call site that needs it.
      @Lazy LibraryDocumentService documentService,
      PlatformTransactionManager transactionManager) {
    this.folderRepository = folderRepository;
    this.libraryRepository = libraryRepository;
    this.userRepository = userRepository;
    this.accessService = accessService;
    this.documentRepository = documentRepository;
    this.documentService = documentService;
    // #823 review (pre-existing #824 race, made user-reachable by #823's concurrent, request-
    // driven callers): see materializeSingleFolder's own comment for why the insert attempt needs
    // its own REQUIRES_NEW transaction rather than running inside the caller's ambient one -
    // mirrors ChatService/SpaceService's identical constructor-built TransactionTemplate.
    this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
    this.requiresNewTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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

  /**
   * Materializes (idempotently) the {@link LibraryFolder} chain for {@code segments} - one row per
   * path component, reusing an existing folder at the same level instead of creating a duplicate
   * (#824). The internal counterpart to {@link #createFolder} a FILESYSTEM indexing run uses to
   * mirror its source directory structure - see this class's own Javadoc for why it bypasses {@link
   * #requireUploadLibrary}/{@link #requireEditable}.
   *
   * <p><b>{@link #validateName}/{@link #MAX_DEPTH} are bypassed too, deliberately</b> (#824 review,
   * Befund 4b) - not just the permission/upload-type checks named above. A mirrored directory name
   * is whatever the filesystem allows (which can differ from what {@link #validateName} accepts for
   * a manually-typed {@code UPLOAD} folder name), and a real directory tree is free to nest deeper
   * than {@link #MAX_DEPTH}; rejecting either would mean silently refusing to mirror part of the
   * source instead of representing it as-is. {@code createFolder}'s own callers (the CRUD REST
   * endpoints) never reach this method - see this class's own Javadoc - so neither gap is reachable
   * through user input.
   *
   * <p><b>A single {@code fk_documents_folder} violation can occur if two runs of the same library
   * overlap</b> (#824 review, Befund 4b) - e.g. after {@code IndexingJobRecoveryScheduler} restarts
   * a run whose previous attempt was merely stuck, not actually finished, past {@code
   * staleJobTimeout}: if the stale run's own {@link LibraryDocumentService#deleteDocument}-driven
   * document write races the fresh run's {@link #pruneOrphanedFolders} deleting the very folder
   * that document is about to reference, the insert fails loudly (the constraint is {@code
   * RESTRICT}, by design - see this class's own Javadoc). Not specifically guarded against here:
   * the next run re-materializes the same folder from the still uncrawled directory and re-attempts
   * the document, so the condition self-heals rather than leaving a permanently broken document
   * behind.
   *
   * @param segments the path components between the library's {@code sourcePath} and the file
   *     itself, outermost first; an empty list means the library's root
   * @return the id of the deepest folder in {@code segments}, or {@code null} for an empty list
   * @throws IllegalArgumentException if {@code library} is not {@link
   *     DocumentSourceType#FILESYSTEM} - this method's only caller today ({@code
   *     AsyncIndexingExecutor}) only ever passes a FILESYSTEM library, but the guard protects the
   *     next one from silently mirroring a directory structure into an {@code UPLOAD}/{@code
   *     HTTP_DIRECTORY}/{@code RSS_FEED} library's CRUD-managed folder tree
   */
  @Transactional
  public UUID materializeFolderPath(KnowledgeLibrary library, List<String> segments) {
    requireFilesystemLibrary(library);
    UUID parentFolderId = null;
    for (String name : segments) {
      parentFolderId = materializeSingleFolder(library, parentFolderId, name);
    }
    return parentFolderId;
  }

  /**
   * The internal counterpart to {@link #requireUploadLibrary}, guarding the opposite direction
   * (#824 review, Befund 4a): {@link #materializeFolderPath}/{@link #pruneOrphanedFolders} must
   * never run against anything but a {@code FILESYSTEM} library - see {@link
   * #materializeFolderPath} 's own Javadoc for why.
   */
  private void requireFilesystemLibrary(KnowledgeLibrary library) {
    if (library.getSourceType() != DocumentSourceType.FILESYSTEM) {
      throw new IllegalArgumentException(
          "materializeFolderPath/pruneOrphanedFolders is only valid for a FILESYSTEM library, got "
              + library.getSourceType());
    }
  }

  /**
   * The insert attempt below runs in its own {@code REQUIRES_NEW} transaction ({@link
   * #requiresNewTransactionTemplate}), not the caller's ambient one - a pre-existing #824 race
   * (review, Befund 6) that #823 made user-reachable: on Postgres, a unique-constraint violation
   * aborts the <em>whole</em> transaction it occurs in, not just the failing statement, so without
   * this isolation, the retry lookup in the {@code catch} block below would itself fail against the
   * same now-poisoned transaction/connection ("current transaction is aborted, commands ignored
   * until end of transaction block") - turning a legitimate reuse into a loud 500 instead. Two
   * concurrent uploads racing the same brand-new folder path (two browser tabs, or a whole
   * dragged-and-dropped tree's parallel-enough requests) hit exactly this window. Isolating the
   * insert into its own transaction means only that small transaction rolls back on a race; the
   * retry lookup then runs against the resumed, still-healthy outer transaction instead.
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
            // saveAndFlush, not save: forces the unique-index violation (a concurrent
            // materialization race - e.g. a re-triggered run overlapping the previous one, or two
            // concurrent uploads) to surface here, inside this try, rather than at this inner
            // transaction's commit further down the call stack.
            return folderRepository.saveAndFlush(folder).getId();
          });
    } catch (DataIntegrityViolationException e) {
      // Race-safety net, mirroring createFolder's identical handling: another concurrent
      // materialization already won the insert for this exact (library, parent, name) - reuse its
      // row instead of failing this one. Safe to query here specifically because the failed
      // insert's own REQUIRES_NEW transaction (see this method's own Javadoc above) has already
      // rolled back by the time execute() rethrows - this runs against the resumed outer
      // transaction, never the poisoned inner one.
      return findByParentAndName(library.getId(), parentFolderId, name)
          .map(LibraryFolder::getId)
          .orElseThrow(() -> e);
    }
  }

  /**
   * Resolves (idempotently creating as needed) the folder chain described by {@code pathSegments},
   * relative to {@code baseFolderId} in an {@code UPLOAD} library (#823, Epic #520 Phase 4) - the
   * upload-path counterpart to {@link #materializeFolderPath}'s FILESYSTEM-only mirroring. Unlike
   * that method, this one enforces every check {@link #createFolder} itself enforces - permission,
   * {@code UPLOAD}-only, name shape, depth - once for the whole chain, exactly as if each segment
   * had been created one REST call at a time; an existing folder at any level is reused rather than
   * duplicated, mirroring {@link #materializeSingleFolder}.
   *
   * @param baseFolderId the folder {@code pathSegments} is relative to; {@code null} means the
   *     library's root, mirroring {@link #createFolder}'s own {@code parentFolderId}
   * @return the id of the deepest folder in {@code pathSegments}, or {@code baseFolderId} unchanged
   *     for an empty list
   */
  @Transactional
  public UUID resolveOrCreateFolderPath(
      UUID libraryId,
      UUID baseFolderId,
      List<String> pathSegments,
      UUID currentUserId,
      boolean systemAdmin) {
    KnowledgeLibrary library = loadLibrary(libraryId, currentUserId);
    requireEditable(library, currentUserId, systemAdmin);
    requireUploadLibrary(library);
    resolveParent(libraryId, baseFolderId);

    // #823 review, Befund 1 (follow-up to Befund 6): every segment is validated - and the
    // resulting depth checked - in this own upfront pass, before materializeSingleFolder ever
    // creates a single row. materializeSingleFolder's own insert now runs in its own REQUIRES_NEW
    // transaction (see its Javadoc, Befund 6) and therefore commits independently the moment it
    // succeeds; validating and materializing one segment at a time would let an earlier, valid
    // segment's folder survive permanently even when a later segment's invalid name or a depth
    // overrun aborts this call - exactly the partial folder skeleton this whole method exists to
    // avoid, just one level further in than the original "resolved before any byte is written"
    // ordering bug (LibraryDocumentService#uploadDocument's own Befund 1 fix).
    List<String> names = new ArrayList<>(pathSegments.size());
    int depth = baseFolderId == null ? 0 : depthOfParentChain(baseFolderId);
    for (String rawSegment : pathSegments) {
      names.add(validatePathSegment(rawSegment));
      depth++;
      if (depth > MAX_DEPTH) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
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
   * typed folder name (#823): no {@code "\"} (a Windows-style separator {@code validateName}'s own
   * {@code "/"} check does not catch) and no {@code ".."}/{@code "."} (a relative-path traversal
   * segment that means something other than a literal folder name).
   */
  private String validatePathSegment(String rawSegment) {
    String name = validateName(rawSegment);
    if (name.contains("\\")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Ordnername darf kein \"\\\" enthalten");
    }
    if (name.equals("..") || name.equals(".")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Ordnername darf nicht \"..\" oder \".\" lauten");
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
   * Removes every {@link LibraryFolder} of {@code libraryId} that is both absent from {@code
   * currentFolderIds} (this indexing run's own directory walk never touched it - its source
   * directory is gone) and empty, including transitively (#824, docs/features/knowledge-sources.md
   * "Ordner in FILESYSTEM-Bibliotheken"). Deliberately conservative: a FILESYSTEM run does not yet
   * delete a document whose backing file disappeared ("Löschung durch Abwesenheit" is decided by
   * ADR-0017 but not yet built for documents) - so a folder that still holds such a stale document
   * (directly, or in one of its own subfolders) is left standing rather than silently discarding
   * it. Once document deletion-by-absence ships, this same check keeps working unchanged: an empty
   * folder is empty regardless of why.
   *
   * <p>Walked leaf-first (post-order): a folder only qualifies once every one of its own subfolders
   * has already either survived (still referenced, or non-empty) or been removed - mirroring {@link
   * #deleteRecursive}'s own order, though here driven by absence from {@code currentFolderIds}
   * rather than an explicit delete request.
   *
   * @throws IllegalArgumentException if {@code library} is not {@link
   *     DocumentSourceType#FILESYSTEM} - see {@link #materializeFolderPath}'s own Javadoc, which
   *     this method mirrors (#824 review, Befund 4a/4b)
   */
  @Transactional
  public void pruneOrphanedFolders(KnowledgeLibrary library, Set<UUID> currentFolderIds) {
    requireFilesystemLibrary(library);
    UUID libraryId = library.getId();
    List<LibraryFolder> all = folderRepository.findByLibraryId(libraryId);
    // Built by hand, not via Collectors.groupingBy (#824 review self-catch): groupingBy's
    // classifier is required to return a non-null key, but LibraryFolder#getParentFolderId is
    // null for exactly the root-level folders this method must also walk - java.util.HashMap
    // itself has no such restriction, so a plain computeIfAbsent loop handles the null-parent
    // (root) case the same way as every other parent id.
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
