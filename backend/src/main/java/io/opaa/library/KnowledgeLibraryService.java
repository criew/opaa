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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages knowledge libraries - the first asset type (#201, see
 * docs/features/spaces-and-assets.md#assets). Read/write access checks are delegated to {@link
 * LibraryAccessService} (#202), which replaced this class's former coarse {@code canRead}/{@code
 * canManage} - see that class's Javadoc for the full reasoning, in particular why group ownership
 * alone no longer implies management rights.
 *
 * <p>{@link #createLibrary} always grants the creator {@link AssetRole#OWNER} explicitly via an
 * {@link AssetGrant} - the right to delete the library and transfer ownership always sits on a
 * named person, never on group membership alone. For a {@link LibraryOwnerType#GROUP} library the
 * owning group additionally gets {@link AssetRole#MANAGER} (sharing and granting roles to others),
 * <em>not</em> {@code OWNER}: every member automatically holding {@code OWNER} would grow without a
 * human decision point as a directory-synchronised group's membership grows (#237) and could never
 * be downgraded once it became the library's only {@code OWNER} grant (#202 code review round 2).
 * The accepted price is that the personal {@code OWNER} grant is lost when its holder leaves - #240
 * (succession instead of blocking) is what regulates that case, not this class.
 *
 * <p>{@link LibraryOwnerType#SYSTEM} libraries (exactly one per organization, see {@link
 * KnowledgeLibrary#SYSTEM_LIBRARY_ID}) are fail-closed by construction: {@link
 * LibraryAccessService#effectiveRole} requires {@code systemAdmin} for them regardless of any
 * grant, and {@link #createLibrary} rejects a caller-supplied {@code SYSTEM} owner type outright -
 * only the migration (012-seed-system-library) ever creates one.
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
  private final AssetGrantRepository grantRepository;
  private final LibraryAccessService accessService;
  private final TransactionTemplate requiresNewTransactionTemplate;

  public KnowledgeLibraryService(
      KnowledgeLibraryRepository libraryRepository,
      UserRepository userRepository,
      GroupRepository groupRepository,
      GroupMembershipResolver membershipResolver,
      DocumentRepository documentRepository,
      AssetGrantRepository grantRepository,
      LibraryAccessService accessService,
      PlatformTransactionManager transactionManager) {
    this.libraryRepository = libraryRepository;
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.membershipResolver = membershipResolver;
    this.documentRepository = documentRepository;
    this.grantRepository = grantRepository;
    this.accessService = accessService;
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
    Group ownerGroup = null;
    if (ownerType == LibraryOwnerType.GROUP) {
      if (request.getOwnerId() == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "ownerId ist erforderlich, wenn ownerType GROUP ist");
      }
      ownerGroup =
          requireGroupInOrganization(request.getOwnerId(), currentUser.getOrganizationId());
      if (!membershipResolver.groupIdsForUser(currentUserId).contains(ownerGroup.getId())) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "Nur Mitglieder der Gruppe koennen eine Bibliothek in ihrem Namen anlegen");
      }
      library =
          KnowledgeLibrary.ownedByGroup(
              currentUser.getOrganizationId(),
              normalizedName,
              request.getDescription(),
              ownerGroup.getId(),
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
    // #202 code review round 2 (Befund 2): a GROUP-owned library grants the *group* MANAGER, not
    // OWNER, and grants the *creator* (a person) OWNER separately - the round-1 fix (group gets
    // OWNER) went a step too far. Every current and future member of the owning group is
    // automatically OWNER under that rule - able to delete the library and transfer ownership -
    // and grows without a human decision point as a directory-synchronised group's membership
    // grows (#237), which is structurally the same defect #201 had, one level up. It is also not
    // demotable: the round-1 group grant is the library's only OWNER grant, so both
    // requireCallerCanTouchExistingGrant and the last-active-OWNER guard permanently protect it -
    // measured as a 409 on both the downgrade and the revoke path.
    //
    // Splitting the two roles keeps the group's real benefit (a centrally maintained library like
    // the feature spec's leitbeispiel "Rechtsquellen Soziales", owner "Referat 50 * Grundsatz",
    // survives its creator's departure - MANAGER already covers sharing and granting roles to
    // others) while keeping the two highest-stakes rights, delete and ownership transfer, on a
    // named person who can be held accountable for them. The accepted price - that OWNER hangs on
    // a person and is lost when they leave - is exactly the case #240 (succession instead of
    // blocking) exists to regulate: the library does not lock, it goes to "Nachfolge offen",
    // usable and frozen against growing reach until a curator is assigned. No other member of the
    // group inherits rights beyond what the group's MANAGER grant itself carries (see the class
    // Javadoc on why mere membership must never imply management on its own).
    if (ownerGroup != null) {
      grantRepository.save(
          AssetGrant.forGroup(
              saved.getId(),
              saved.getOrganizationId(),
              ownerGroup.getId(),
              AssetRole.MANAGER,
              null,
              currentUserId));
    }
    grantRepository.save(
        AssetGrant.forUser(
            saved.getId(),
            saved.getOrganizationId(),
            currentUserId,
            AssetRole.OWNER,
            null,
            currentUserId));
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
    if (!accessService.canRead(library, currentUserId, systemAdmin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
    }
    return toLibraryResponse(library);
  }

  @Transactional
  public LibraryResponse updateLibrary(
      UUID libraryId, LibraryUpdateRequest request, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = loadLibrary(libraryId, currentUserId);
    if (!accessService.canManage(library, currentUserId, systemAdmin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
    }
    // Mirrors the delete guard on the personal library (code review of #201/#305): once #202 makes
    // library_id the filter axis for the permission-aware vector search, widening a personal
    // library's visibility to ORGANIZATION would expose its owner's private documents
    // organization-wide - a change no owner is likely to intend for a library the system, not they,
    // created. The personal library's name and description can still be changed.
    if (library.isPersonal() && request.getVisibility() == LibraryVisibility.ORGANIZATION) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Die Sichtbarkeit der persoenlichen Bibliothek kann nicht auf ORGANIZATION gesetzt"
              + " werden");
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
    if (!accessService.canManage(library, currentUserId, systemAdmin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
    }
    // fk_documents_library_organization is RESTRICT (migration 012): deleting a library that
    // still contains documents would otherwise surface as an unhandled
    // DataIntegrityViolationException
    // -> HTTP 500 with no indication of the actual cause. Checking first turns that into a clean,
    // actionable 409.
    if (documentRepository.countByLibraryId(libraryId) > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Die Bibliothek enthaelt noch Dokumente und kann nicht geloescht werden");
    }

    libraryRepository.delete(library);
  }

  public List<LibraryDocumentResponse> listDocuments(
      UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    KnowledgeLibrary library = loadLibrary(libraryId, currentUserId);
    if (!accessService.canRead(library, currentUserId, systemAdmin)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek");
    }

    return documentRepository.findByLibraryId(libraryId).stream()
        .map(this::toLibraryDocumentResponse)
        .toList();
  }

  /**
   * Creates the automatic personal library "Meine Dokumente" for a user if it does not exist yet.
   * Mirrors {@code SpaceService#ensurePersonalSpace} exactly, including its {@code ON CONFLICT ...
   * DO NOTHING} race handling via the partial unique index {@code
   * uk_knowledge_libraries_personal_owner} (migration 012) - see that method's Javadoc for the full
   * reasoning, not repeated here. Both are called from the same {@code UserService} post-commit
   * callback for the same reason: the referenced {@code users} row must already be committed and
   * visible on this method's own connection (see {@code
   * UserService#ensurePersonalAssetsAfterCommit} for why the call is deferred to after commit).
   *
   * <p>Called independently of (not nested inside) {@code SpaceService#ensurePersonalSpace}'s own
   * transaction, so a failure creating the library never rolls back an already-committed personal
   * space and vice versa - each keeps the same self-contained failure boundary #265 established for
   * the personal space alone. "Atomically" in #201's acceptance criteria is satisfied at the level
   * that matters operationally: both calls are always attempted together, from the same afterCommit
   * callback, so provisioning never silently creates one without the other.
   *
   * <p><b>{@code Propagation.NOT_SUPPORTED}, deliberately overriding the class-level
   * {@code @Transactional(readOnly = true)}:</b> without this override, calling this public method
   * through the Spring proxy would open an ambient read-only transaction (and thus hold one JDBC
   * connection) for this method's entire duration, while {@code requiresNewTransactionTemplate}
   * below opens a <em>second</em>, independent connection for its {@code REQUIRES_NEW} transaction
   * - two connections held by one caller at once, the same class of bug #299 fixed in {@code
   * UserService.findOrCreateUser}. {@code SpaceService#ensurePersonalSpace} had the identical
   * defect and is fixed the same way, in this same PR (#201/#305 code review) - not deferred to a
   * follow-up issue, because the fix is one annotation and both methods are exercised together by
   * {@link io.opaa.auth.UserServiceCreationRaceIntegrationTest}. {@code NOT_SUPPORTED} suspends any
   * ambient transaction for this method's duration (there normally is none, since {@code
   * findOrCreateUser} itself is not {@code @Transactional} either) and leaves only the one
   * connection {@code requiresNewTransactionTemplate} actually needs.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void ensurePersonalLibrary(UUID userId, UUID organizationId) {
    if (libraryRepository.existsByOwnerUserIdAndPersonalTrue(userId)) {
      return;
    }

    requiresNewTransactionTemplate.executeWithoutResult(
        status -> {
          libraryRepository.insertPersonalLibraryIfAbsent(
              UUID.randomUUID(),
              organizationId,
              PERSONAL_LIBRARY_NAME,
              "Private persoenliche Wissensbibliothek",
              userId);
          // Same connection/transaction as the insert above, so it always sees the row it just
          // wrote (or the pre-existing one another concurrent call won the race for) - see
          // AssetGrantRepository#insertOwnerGrantForPersonalLibraryIfAbsent.
          grantRepository.insertOwnerGrantForPersonalLibraryIfAbsent(UUID.randomUUID(), userId);
        });
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
