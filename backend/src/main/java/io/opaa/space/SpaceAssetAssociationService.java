package io.opaa.space;

import io.opaa.api.dto.LibrarySpaceAssociationResponse;
import io.opaa.api.dto.SpaceLibraryAssociationListResponse;
import io.opaa.api.dto.SpaceLibraryAssociationResponse;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditEventType;
import io.opaa.audit.AuditObjectType;
import io.opaa.audit.AuditOutcome;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.PermissionSubject;
import io.opaa.library.AssetRole;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.notification.NotificationService;
import io.opaa.notification.NotificationType;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages {@link SpaceAssetAssociation}s - the pure-curation link between a {@link Space} and a
 * knowledge library (#203/#686, docs/features/spaces-and-assets.md#assets-in-einen-space-
 * assoziieren). Every mutating method reads {@link Space#getMemberships()} through {@link
 * SpaceRepository#findByIdWithMemberships}, mirroring {@code SpaceService}'s own loading pattern.
 *
 * <p><b>The association changes no one's effective permissions</b> - this class never touches
 * {@link LibraryAccessService#readableLibraryIds} or any {@code AssetGrant}; it only ever reads
 * through {@link LibraryAccessService} to check whether a caller or a space member already has
 * access, never to grant any.
 */
@Service
@Transactional(readOnly = true)
public class SpaceAssetAssociationService {

  private final SpaceAssetAssociationRepository associationRepository;
  private final SpaceRepository spaceRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryAccessService libraryAccessService;
  private final UserRepository userRepository;
  private final GroupMembershipResolver groupMembershipResolver;
  private final AuditEventRecorder auditEventRecorder;
  private final NotificationService notificationService;

  public SpaceAssetAssociationService(
      SpaceAssetAssociationRepository associationRepository,
      SpaceRepository spaceRepository,
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService libraryAccessService,
      UserRepository userRepository,
      GroupMembershipResolver groupMembershipResolver,
      AuditEventRecorder auditEventRecorder,
      NotificationService notificationService) {
    this.associationRepository = associationRepository;
    this.spaceRepository = spaceRepository;
    this.libraryRepository = libraryRepository;
    this.libraryAccessService = libraryAccessService;
    this.userRepository = userRepository;
    this.groupMembershipResolver = groupMembershipResolver;
    this.auditEventRecorder = auditEventRecorder;
    this.notificationService = notificationService;
  }

  /**
   * Number of libraries each of the given spaces shows the caller (#682) - the overview card's
   * "Quellen" figure. Mirrors {@link #listForSpace}'s rule: CURATOR/ADMIN, the owner and a system
   * admin count every association, a plain MEMBER only the libraries they may read - otherwise the
   * figure next to a filtered list would give away how many libraries are withheld, which
   * docs/features/spaces-and-assets.md forbids ("darf keine Anzahlen nennen"). One query for all
   * associations plus one readable-set lookup, never a query per space; spaces without associations
   * map to zero. Expects all spaces to belong to one organization, as {@code
   * SpaceService#listSpaces} guarantees (the readable set is resolved once for that organization).
   */
  public Map<UUID, Long> countVisibleBySpace(
      List<Space> spaces, UUID currentUserId, boolean systemAdmin) {
    if (spaces.isEmpty()) {
      return Map.of();
    }
    List<SpaceAssetAssociation> associations =
        associationRepository.findBySpaceIdIn(spaces.stream().map(Space::getId).toList());
    if (associations.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Space> spacesById =
        spaces.stream().collect(Collectors.toMap(Space::getId, Function.identity()));
    Set<UUID> readable =
        systemAdmin
            ? Set.of()
            : libraryAccessService.readableLibraryIds(
                currentUserId, spaces.getFirst().getOrganizationId());
    return associations.stream()
        .filter(
            association -> {
              Space space = spacesById.get(association.getSpaceId());
              return systemAdmin
                  || hasCuratorRole(space, currentUserId)
                  || readable.contains(association.getLibraryId());
            })
        .collect(Collectors.groupingBy(SpaceAssetAssociation::getSpaceId, Collectors.counting()));
  }

  /**
   * The space's associated libraries. For a plain {@code MEMBER}, filtered to what {@code
   * currentUserId} may themselves read (#203 acceptance criterion: two members of the same space
   * with different grants see different lists). For a {@code CURATOR}, {@code ADMIN} or the space
   * owner, unfiltered - every association is returned, including one they cannot themselves read,
   * with {@code readableByCaller=false} and no {@code libraryName} - so a manager can also see and
   * detach an over-broad association they have no personal grant on (#706 review: the filtered list
   * otherwise hid it from the one role that is supposed to be able to undo it).
   *
   * <p>{@link SpaceLibraryAssociationListResponse#getHasAssociations()} is computed unfiltered,
   * independently of the (possibly filtered) item list - it is what lets the caller (via {@code
   * ChatService#effectiveLibraryScope}'s counterpart logic, mirrored here for the read side)
   * distinguish "no association at all" from "curated, but nothing the viewer may read" (#706
   * review, finding 2).
   */
  public SpaceLibraryAssociationListResponse listForSpace(
      UUID spaceId, UUID currentUserId, boolean systemAdmin) {
    Space space = loadSpace(spaceId, currentUserId);
    requireMember(space, currentUserId, systemAdmin);

    List<SpaceAssetAssociation> associations =
        associationRepository.findBySpaceIdOrderByCreatedAtAsc(space.getId());
    boolean hasAssociations = !associations.isEmpty();
    if (associations.isEmpty()) {
      return new SpaceLibraryAssociationListResponse(hasAssociations, List.of());
    }
    boolean unfiltered = hasCuratorRole(space, currentUserId) || systemAdmin;
    Set<UUID> readable =
        libraryAccessService.readableLibraryIds(currentUserId, space.getOrganizationId());
    Map<UUID, String> displayNames =
        resolveDisplayNames(
            associations.stream().map(SpaceAssetAssociation::getCreatedByUserId).toList());
    Map<UUID, KnowledgeLibrary> librariesById = loadLibraries(associations);

    List<SpaceLibraryAssociationResponse> items =
        associations.stream()
            .filter(association -> unfiltered || readable.contains(association.getLibraryId()))
            .map(
                association -> {
                  boolean readableByCaller = readable.contains(association.getLibraryId());
                  KnowledgeLibrary library = librariesById.get(association.getLibraryId());
                  return new SpaceLibraryAssociationResponse(
                          association.getLibraryId(),
                          readableByCaller,
                          association.getCreatedByUserId(),
                          association.getCreatedAt())
                      .libraryName(readableByCaller && library != null ? library.getName() : null)
                      .createdByDisplayName(displayNames.get(association.getCreatedByUserId()));
                })
            .toList();
    return new SpaceLibraryAssociationListResponse(hasAssociations, items);
  }

  /**
   * Associates {@code libraryId} with {@code spaceId} (#203/#686). Idempotent per (space, library):
   * an already-existing association is returned unchanged rather than duplicated or rejected.
   */
  @Transactional
  public SpaceLibraryAssociationResponse associate(
      UUID spaceId, UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    Space space = loadSpace(spaceId, currentUserId);
    requireCurator(space, currentUserId, systemAdmin);

    KnowledgeLibrary library = requireLibrary(libraryId, space.getOrganizationId());
    // A CURATOR may only associate an asset they can themselves access - the same rule #203
    // states explicitly, checked through the caller's own real grants (never a system-admin
    // bypass, mirroring LibraryAccessService#readableLibraryIds's own no-bypass rule) so that
    // "may associate" and "may search" never diverge. requireRole (not canRead + a 403) answers
    // 404 for "no access", the same existence-oracle guard #436 already established elsewhere
    // (LibraryAccessService#requireRole's own Javadoc, and this class's own listForLibrary) - a
    // plain 403 here would let a caller distinguish "library exists in my organization but I lack
    // access" from "no such library" for any id they can guess, regardless of whether they may
    // ever see it (#706 review, finding 6).
    libraryAccessService.requireRole(library, currentUserId, false, AssetRole.VIEWER);

    var existing = associationRepository.findBySpaceIdAndLibraryId(space.getId(), library.getId());
    if (existing.isPresent()) {
      return toSpaceLibraryAssociationResponse(existing.get(), library);
    }

    SpaceAssetAssociation association =
        new SpaceAssetAssociation(
            space.getId(), library.getId(), space.getOrganizationId(), currentUserId);
    SpaceAssetAssociation saved = associationRepository.save(association);

    // Space is not a rights subject (AuditSubjectKind only covers USER/GROUP - the actual
    // grantees of a permission, see that enum's Javadoc) - the space id is carried in the payload
    // instead, mirroring how ASSET_OWNER_CHANGED carries ownerId in its own payload.
    auditEventRecorder.recordUserAction(
        space.getOrganizationId(),
        currentUserId,
        AuditEventType.LIBRARY_SHARED_TO_SPACE,
        AuditObjectType.KNOWLEDGE_LIBRARY,
        library.getId(),
        library.getName(),
        null,
        Map.of("spaceId", space.getId().toString()),
        AuditOutcome.SUCCESS,
        null);

    notifyOwnerIfMixedAudience(space, library, currentUserId);

    return toSpaceLibraryAssociationResponse(saved, library);
  }

  /**
   * Removes an association - allowed for a CURATOR or above on the space, or unilaterally for a
   * MANAGER or above on the library itself, regardless of the caller's own space membership (#203:
   * "Der Eigentümer des Assets ... kann jede davon jederzeit einseitig lösen"). A no-op (still
   * 204/void) if no such association exists.
   */
  @Transactional
  public void detach(UUID spaceId, UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    Space space = loadSpace(spaceId, currentUserId);
    KnowledgeLibrary library = requireLibrary(libraryId, space.getOrganizationId());

    boolean spaceCurator = hasCuratorRole(space, currentUserId) || systemAdmin;
    boolean libraryManager = libraryAccessService.canManage(library, currentUserId, systemAdmin);
    if (!spaceCurator && !libraryManager) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Nur Kuratoren dieses Space oder Verwaltende der Bibliothek können die Zuordnung lösen");
    }

    associationRepository
        .findBySpaceIdAndLibraryId(space.getId(), library.getId())
        .ifPresent(
            association -> {
              associationRepository.delete(association);
              auditEventRecorder.recordUserAction(
                  space.getOrganizationId(),
                  currentUserId,
                  AuditEventType.LIBRARY_DETACHED_FROM_SPACE,
                  AuditObjectType.KNOWLEDGE_LIBRARY,
                  library.getId(),
                  library.getName(),
                  Map.of("spaceId", space.getId().toString()),
                  null,
                  AuditOutcome.SUCCESS,
                  null);
            });
  }

  /**
   * Every space this library is associated with - the owner-facing view (#203), requiring MANAGER
   * or above on the library. Unlike {@link #listForSpace}, this is never filtered by the caller's
   * own space membership: the owner sees every association, including in spaces they do not belong
   * to.
   */
  public List<LibrarySpaceAssociationResponse> listForLibrary(
      UUID libraryId, UUID currentUserId, boolean systemAdmin) {
    User currentUser = requireUser(currentUserId);
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .filter(l -> l.getOrganizationId().equals(currentUser.getOrganizationId()))
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Bibliothek nicht gefunden"));
    libraryAccessService.requireRole(library, currentUserId, systemAdmin, AssetRole.MANAGER);

    List<SpaceAssetAssociation> associations =
        associationRepository.findByLibraryIdOrderByCreatedAtAsc(library.getId());
    if (associations.isEmpty()) {
      return List.of();
    }
    Map<UUID, Space> spacesById = new LinkedHashMap<>();
    for (SpaceAssetAssociation association : associations) {
      spacesById.computeIfAbsent(
          association.getSpaceId(), id -> spaceRepository.findByIdWithMemberships(id).orElse(null));
    }
    Map<UUID, String> displayNames =
        resolveDisplayNames(
            associations.stream().map(SpaceAssetAssociation::getCreatedByUserId).toList());

    return associations.stream()
        .map(
            association -> {
              Space space = spacesById.get(association.getSpaceId());
              return new LibrarySpaceAssociationResponse(
                      association.getSpaceId(),
                      space != null ? space.getName() : "",
                      association.getCreatedByUserId(),
                      association.getCreatedAt(),
                      space != null && !allMembersCanRead(space, library))
                  .createdByDisplayName(displayNames.get(association.getCreatedByUserId()));
            })
        .toList();
  }

  /**
   * Notifies the library's owner (every member, if group-owned) when the space just associated has
   * at least one member without read access to the library (#203: "Benachrichtigung statt
   * Zustimmung"). No consent is required - the association already took effect; this only ensures
   * the owner learns of it without having to check a list.
   *
   * <p>{@code triggeringUserId} - the caller who just created the association - is always excluded
   * from the recipient set (#706 review): a curator who happens to be a member of the owning group
   * already knows what they just did, and a self-notification would only be noise, never new
   * information.
   */
  private void notifyOwnerIfMixedAudience(
      Space space, KnowledgeLibrary library, UUID triggeringUserId) {
    if (allMembersCanRead(space, library)) {
      return;
    }
    Set<UUID> recipients =
        switch (library.getOwnerType()) {
          case USER -> Set.of(library.getOwnerUserId());
          case GROUP ->
              groupMembershipResolver.resolveUserIds(
                  PermissionSubject.group(library.getOwnerGroupId(), library.getOrganizationId()));
        };
    String title = "Ihre Bibliothek wurde in einem Space bereitgestellt";
    String body =
        "Die Bibliothek \""
            + library.getName()
            + "\" wurde im Space \""
            + space.getName()
            + "\" bereitgestellt, dessen Mitglieder nicht alle Lesezugriff darauf haben.";
    for (UUID recipientId : recipients) {
      if (recipientId.equals(triggeringUserId)) {
        continue;
      }
      notificationService.notify(
          library.getOrganizationId(),
          recipientId,
          NotificationType.LIBRARY_ASSOCIATED_TO_MIXED_SPACE,
          AuditObjectType.KNOWLEDGE_LIBRARY,
          library.getId(),
          title,
          body);
    }
  }

  /**
   * Whether every current member of {@code space} already has at least VIEWER on {@code library}.
   */
  private boolean allMembersCanRead(Space space, KnowledgeLibrary library) {
    for (SpaceMembership membership : space.getMemberships()) {
      // Deliberately not systemAdmin-bypassed: a system-admin member would trivially satisfy "can
      // read", masking whether ordinary members actually have a real grant - the exact signal
      // this check exists to surface. See LibraryAccessService#readableLibraryIds's own no-bypass
      // rule for the same reasoning applied to search.
      if (!libraryAccessService.canRead(library, membership.getUserId(), false)) {
        return false;
      }
    }
    return true;
  }

  private boolean hasCuratorRole(Space space, UUID userId) {
    if (space.getOwnerId().equals(userId)) {
      return true;
    }
    return space.getMemberships().stream()
        .filter(m -> m.getUserId().equals(userId))
        .anyMatch(m -> m.getRole() == SpaceRole.CURATOR || m.getRole() == SpaceRole.ADMIN);
  }

  private void requireCurator(Space space, UUID userId, boolean systemAdmin) {
    if (systemAdmin || hasCuratorRole(space, userId)) {
      return;
    }
    throw new ResponseStatusException(
        HttpStatus.FORBIDDEN, "Nur Kuratoren dieses Space können Bibliotheken zuordnen");
  }

  private void requireMember(Space space, UUID userId, boolean systemAdmin) {
    if (systemAdmin) {
      return;
    }
    boolean member = space.getMemberships().stream().anyMatch(m -> m.getUserId().equals(userId));
    if (!member) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Sie sind kein Mitglied dieses Space");
    }
  }

  private Space loadSpace(UUID spaceId, UUID currentUserId) {
    User currentUser = requireUser(currentUserId);
    Space space =
        spaceRepository
            .findByIdWithMemberships(spaceId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Space nicht gefunden"));
    if (!space.getOrganizationId().equals(currentUser.getOrganizationId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Space nicht gefunden");
    }
    return space;
  }

  private KnowledgeLibrary requireLibrary(UUID libraryId, UUID organizationId) {
    KnowledgeLibrary library =
        libraryRepository
            .findById(libraryId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Bibliothek nicht gefunden"));
    if (!library.getOrganizationId().equals(organizationId)) {
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

  private Map<UUID, KnowledgeLibrary> loadLibraries(List<SpaceAssetAssociation> associations) {
    Map<UUID, KnowledgeLibrary> result = new LinkedHashMap<>();
    for (KnowledgeLibrary library :
        libraryRepository.findAllById(
            associations.stream().map(SpaceAssetAssociation::getLibraryId).toList())) {
      result.put(library.getId(), library);
    }
    return result;
  }

  private Map<UUID, String> resolveDisplayNames(List<UUID> userIds) {
    Map<UUID, String> result = new HashMap<>();
    for (User user : userRepository.findAllById(userIds)) {
      result.put(
          user.getId(), user.getDisplayName() != null ? user.getDisplayName() : user.getEmail());
    }
    return result;
  }

  private SpaceLibraryAssociationResponse toSpaceLibraryAssociationResponse(
      SpaceAssetAssociation association, KnowledgeLibrary library) {
    return new SpaceLibraryAssociationResponse(
            association.getLibraryId(),
            true,
            association.getCreatedByUserId(),
            association.getCreatedAt())
        .libraryName(library.getName())
        .createdByDisplayName(
            resolveDisplayNames(List.of(association.getCreatedByUserId()))
                .get(association.getCreatedByUserId()));
  }
}
