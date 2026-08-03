package io.opaa.library;

import io.opaa.api.dto.LibraryDocumentResponse;
import io.opaa.api.dto.LibraryListResponse;
import io.opaa.api.dto.LibraryRequest;
import io.opaa.api.dto.LibraryResponse;
import io.opaa.api.dto.LibraryUpdateRequest;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.GroupRepository;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages knowledge libraries - the first asset type (#201, see
 * docs/features/spaces-and-assets.md#assets). Read/write access implemented here is deliberately
 * the coarse subset the model already fixes for #201: the owner (a matching user, or any member of
 * an owning group), organization-wide read for {@link LibraryVisibility#ORGANIZATION}, and full
 * access for system administrators. It does not implement the graded asset roles ({@code
 * USER}/{@code VIEWER}/{@code EDITOR}/{@code MANAGER}/{@code OWNER}) or a grants table - that is
 * #202's "asset permissions and permission-aware vector search", the actual linchpin of the epic.
 * Building that here would be speculative: #201 only needs "does this library have a responsible
 * owner and can documents be attributed to it", not the full sharing model.
 *
 * <p>{@link LibraryOwnerType#SYSTEM} libraries (exactly one per organization, see {@link
 * KnowledgeLibrary#SYSTEM_LIBRARY_ID}) are fail-closed by construction: {@link #canRead} and {@link
 * #canManage} both require {@code systemAdmin} for them regardless of any other check, and {@link
 * #createLibrary} rejects a caller-supplied {@code SYSTEM} owner type outright - only the migration
 * (012-seed-system-library) ever creates one.
 */
@Service
@Transactional(readOnly = true)
public class KnowledgeLibraryService {

  private static final int MAX_NAME_LENGTH = 255;
  private static final int MAX_DESCRIPTION_LENGTH = 2000;
  private static final String PERSONAL_LIBRARY_NAME = "Meine Dokumente";

  private final KnowledgeLibraryRepository libraryRepository;
  private final UserRepository userRepository;
  private final GroupRepository groupRepository;
  private final GroupMembershipResolver membershipResolver;
  private final DocumentRepository documentRepository;
  private final TransactionTemplate requiresNewTransactionTemplate;

  public KnowledgeLibraryService(
      KnowledgeLibraryRepository libraryRepository,
      UserRepository userRepository,
      GroupRepository groupRepository,
      GroupMembershipResolver membershipResolver,
      DocumentRepository documentRepository,
      PlatformTransactionManager transactionManager) {
    this.libraryRepository = libraryRepository;
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.membershipResolver = membershipResolver;
    this.documentRepository = documentRepository;
    this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
    this.requiresNewTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Transactional
  public LibraryResponse createLibrary(LibraryRequest request, UUID currentUserId) {
    User currentUser = requireUser(currentUserId);
    String normalizedName = validateName(request.getName());
    validateDescription(request.getDescription());

    LibraryOwnerType ownerType =
        request.getOwnerType() != null ? request.getOwnerType() : LibraryOwnerType.USER;
    if (ownerType == LibraryOwnerType.SYSTEM) {
      // Only the migration (012-seed-system-library) creates a SYSTEM-owned library; accepting it
      // here would let any caller mint a second "readable by system admins only" library outside
      // that fail-closed, single-row invariant.
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "ownerType SYSTEM kann nicht ueber die API angelegt werden");
    }

    LibraryVisibility visibility =
        request.getVisibility() != null ? request.getVisibility() : LibraryVisibility.PRIVATE;
    boolean listed = Boolean.TRUE.equals(request.getListed());

    KnowledgeLibrary library;
    if (ownerType == LibraryOwnerType.GROUP) {
      if (request.getOwnerId() == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "ownerId ist erforderlich, wenn ownerType GROUP ist");
      }
      Group group =
          requireGroupInOrganization(request.getOwnerId(), currentUser.getOrganizationId());
      if (!membershipResolver.groupIdsForUser(currentUserId).contains(group.getId())) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "Nur Mitglieder der Gruppe koennen eine Bibliothek in ihrem Namen anlegen");
      }
      library =
          KnowledgeLibrary.ownedByGroup(
              currentUser.getOrganizationId(),
              normalizedName,
              request.getDescription(),
              group.getId(),
              visibility,
              listed);
    } else {
      library =
          KnowledgeLibrary.ownedByUser(
              currentUser.getOrganizationId(),
              normalizedName,
              request.getDescription(),
              currentUserId,
              visibility,
              listed,
              false);
    }

    KnowledgeLibrary saved = libraryRepository.save(library);
    return toLibraryResponse(saved);
  }

  public List<LibraryListResponse> listLibraries(UUID currentUserId) {
    User currentUser = requireUser(currentUserId);
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(currentUserId);

    List<KnowledgeLibrary> owned =
        libraryRepository.findByOrganizationIdAndOwnerUserId(
            currentUser.getOrganizationId(), currentUserId);
    List<KnowledgeLibrary> groupOwned =
        groupIds.isEmpty()
            ? List.of()
            : libraryRepository.findByOrganizationIdAndOwnerGroupIdIn(
                currentUser.getOrganizationId(), List.copyOf(groupIds));
    List<KnowledgeLibrary> organizationWide =
        libraryRepository.findByOrganizationIdAndVisibility(
            currentUser.getOrganizationId(), LibraryVisibility.ORGANIZATION);

    // LinkedHashSet by id, not by equals() on the entity - KnowledgeLibrary has no equals()
    // override, and the three lists above can legitimately overlap (an organization-wide library
    // owned by the caller's own group, for instance).
    var byId = new LinkedHashMap<UUID, KnowledgeLibrary>();
    for (KnowledgeLibrary library : owned) {
      byId.put(library.getId(), library);
    }
    for (KnowledgeLibrary library : groupOwned) {
      byId.put(library.getId(), library);
    }
    for (KnowledgeLibrary library : organizationWide) {
      byId.put(library.getId(), library);
    }

    return byId.values().stream().map(this::toLibraryListResponse).toList();
  }

  public LibraryResponse getLibrary(UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = loadLibrary(libraryId, currentUserId);
    if (!canRead(library, currentUserId, systemAdmin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
    }
    return toLibraryResponse(library);
  }

  @Transactional
  public LibraryResponse updateLibrary(
      UUID libraryId, LibraryUpdateRequest request, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = loadLibrary(libraryId, currentUserId);
    if (!canManage(library, currentUserId, systemAdmin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
    }

    String normalizedName = validateName(request.getName());
    validateDescription(request.getDescription());
    boolean listed = Boolean.TRUE.equals(request.getListed());
    library.updateDetails(
        normalizedName, request.getDescription(), request.getVisibility(), listed);
    KnowledgeLibrary updated = libraryRepository.save(library);
    return toLibraryResponse(updated);
  }

  @Transactional
  public void deleteLibrary(UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = loadLibrary(libraryId, currentUserId);
    if (library.isSystemLibrary()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Die System-Bibliothek kann nicht geloescht werden");
    }
    if (library.isPersonal()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Die persoenliche Bibliothek kann nicht geloescht werden");
    }
    if (!canManage(library, currentUserId, systemAdmin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
    }

    libraryRepository.delete(library);
  }

  public List<LibraryDocumentResponse> listDocuments(
      UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = loadLibrary(libraryId, currentUserId);
    if (!canRead(library, currentUserId, systemAdmin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
    }

    return documentRepository.findByLibraryId(libraryId).stream()
        .map(this::toLibraryDocumentResponse)
        .toList();
  }

  /**
   * Creates the automatic personal library "Meine Dokumente" for a user if it does not exist yet.
   * Mirrors {@code SpaceService#ensurePersonalSpace} exactly - same {@code REQUIRES_NEW}
   * transaction on its own connection, same race handling via the partial unique index {@code
   * uk_knowledge_libraries_personal_owner} (migration 012) - because both are called from the same
   * {@code UserService} post-commit callback for the same reason: the referenced {@code users} row
   * must already be committed and visible on this method's own connection. See {@code
   * UserService#ensurePersonalSpaceAfterCommit} for why the call is deferred to after commit, and
   * {@code SpaceService#ensurePersonalSpace}'s Javadoc for the full race explanation this method
   * does not repeat.
   *
   * <p>Called independently of (not nested inside) {@code SpaceService#ensurePersonalSpace}'s own
   * transaction, so a failure creating the library never rolls back an already-committed personal
   * space and vice versa - each keeps the same self-contained failure boundary #265 established for
   * the personal space alone. "Atomically" in #201's acceptance criteria is satisfied at the level
   * that matters operationally: both calls are always attempted together, from the same afterCommit
   * callback, so provisioning never silently creates one without the other.
   */
  public void ensurePersonalLibrary(UUID userId, UUID organizationId) {
    if (libraryRepository.existsByOwnerUserIdAndPersonalTrue(userId)) {
      return;
    }

    try {
      requiresNewTransactionTemplate.executeWithoutResult(
          status -> createPersonalLibrary(userId, organizationId));
    } catch (DataIntegrityViolationException raceLost) {
      if (!libraryRepository.existsByOwnerUserIdAndPersonalTrue(userId)) {
        throw raceLost;
      }
    }
  }

  private void createPersonalLibrary(UUID userId, UUID organizationId) {
    KnowledgeLibrary personalLibrary =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            PERSONAL_LIBRARY_NAME,
            "Private persoenliche Wissensbibliothek",
            userId,
            LibraryVisibility.PRIVATE,
            false,
            true);
    libraryRepository.saveAndFlush(personalLibrary);
  }

  private boolean canRead(KnowledgeLibrary library, UUID userId, boolean systemAdmin) {
    if (library.isSystemLibrary()) {
      return systemAdmin;
    }
    if (systemAdmin) {
      return true;
    }
    if (library.getVisibility() == LibraryVisibility.ORGANIZATION) {
      return true;
    }
    return isOwnerOrGroupMember(library, userId);
  }

  private boolean canManage(KnowledgeLibrary library, UUID userId, boolean systemAdmin) {
    if (library.isSystemLibrary()) {
      return systemAdmin;
    }
    if (systemAdmin) {
      return true;
    }
    return isOwnerOrGroupMember(library, userId);
  }

  private boolean isOwnerOrGroupMember(KnowledgeLibrary library, UUID userId) {
    if (library.isOwnedByUser(userId)) {
      return true;
    }
    return library.getOwnerType() == LibraryOwnerType.GROUP
        && membershipResolver.groupIdsForUser(userId).contains(library.getOwnerGroupId());
  }

  private String validateName(String name) {
    if (name == null || name.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name ist erforderlich");
    }
    String trimmed = name.trim();
    if (trimmed.length() > MAX_NAME_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "name darf hoechstens " + MAX_NAME_LENGTH + " Zeichen umfassen");
    }
    return trimmed;
  }

  private void validateDescription(String description) {
    if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "description darf hoechstens " + MAX_DESCRIPTION_LENGTH + " Zeichen umfassen");
    }
  }

  private User requireUser(UUID userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Benutzer nicht gefunden"));
  }

  /**
   * Resolves a group and enforces the organization boundary, treating a group from another
   * organization as not found - mirrors {@code SpaceService#requireUserInOrganization} and {@code
   * GroupService#loadGroup}. Returns 404 rather than 403 so a caller cannot distinguish "no such
   * group" from "group in another organization" - the same lesson #199's review drew for foreign
   * ids in a request body.
   */
  private Group requireGroupInOrganization(UUID groupId, UUID organizationId) {
    Group group =
        groupRepository
            .findById(groupId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gruppe nicht gefunden"));
    if (!group.getOrganizationId().equals(organizationId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gruppe nicht gefunden");
    }
    return group;
  }

  /**
   * Loads a library and enforces the organization boundary, treating a library from another
   * organization as not found - mirrors {@code SpaceService#loadSpace}. Applies to system admins as
   * well; the boundary is not overstepped even to reveal existence.
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

  private LibraryListResponse toLibraryListResponse(KnowledgeLibrary library) {
    return new LibraryListResponse(
            library.getId(),
            library.getName(),
            library.getOwnerType(),
            library.getVisibility(),
            library.isListed(),
            library.isPersonal(),
            library.getCreatedAt(),
            library.getUpdatedAt())
        .description(library.getDescription());
  }

  private LibraryResponse toLibraryResponse(KnowledgeLibrary library) {
    return new LibraryResponse(
            library.getId(),
            library.getName(),
            library.getOwnerType(),
            library.getVisibility(),
            library.isListed(),
            library.isPersonal(),
            library.getCreatedAt(),
            library.getUpdatedAt())
        .description(library.getDescription())
        .ownerId(library.getOwnerId())
        .documentCount(documentRepository.countByLibraryId(library.getId()));
  }

  private LibraryDocumentResponse toLibraryDocumentResponse(Document document) {
    return new LibraryDocumentResponse(
            document.getId(),
            document.getFileName(),
            document.getStatus(),
            document.getSourceType(),
            document.getChunkCount())
        .contentType(document.getContentType())
        .fileSize(document.getFileSize())
        .indexedAt(document.getIndexedAt());
  }
}
