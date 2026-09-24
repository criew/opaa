package io.opaa.space;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.NotificationType;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.asset.Asset;
import io.opaa.asset.AssetAuthorization;
import io.opaa.asset.AssetHeader;
import io.opaa.asset.AssetRepository;
import io.opaa.asset.AssetTypeDefinition;
import io.opaa.asset.AssetTypes;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.OrganizationScopedLoader;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.notification.NotificationService;
import io.opaa.permission.AssetAccessService;
import io.opaa.permission.AssetType;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.PermissionSubject;
import io.opaa.permission.SuccessionReachGuard;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages {@link SpaceAssetAssociation}s - the pure-curation link between a {@link Space} and an
 * asset of any type (#203/#686, #1900, docs/features/spaces-and-assets.md#assets-in-einen-space-
 * assoziieren). Every mutating method reads {@link Space#getMemberships()} through {@link
 * SpaceRepository#findByIdWithMemberships}, mirroring {@code SpaceService}'s own loading pattern.
 *
 * <p><b>The association changes no one's effective permissions</b> - this class never writes an
 * {@code AssetGrant}; it only reads the rights formula ({@link AssetAccessService}, {@link
 * AssetAuthorization}) to check whether a caller or a space member already has access.
 */
@Service
@Transactional(readOnly = true)
public class SpaceAssetAssociationService {

  private final SpaceAssetAssociationRepository associationRepository;
  private final SpaceRepository spaceRepository;
  private final AssetRepository assetRepository;
  private final AssetAuthorization assetAuthorization;
  private final AssetAccessService assetAccessService;
  private final AssetTypes assetTypes;
  private final UserRepository userRepository;
  private final GroupMembershipResolver groupMembershipResolver;
  private final SpaceAccessPolicy accessPolicy;
  private final AuditEventRecorder auditEventRecorder;
  private final NotificationService notificationService;
  private final SuccessionReachGuard successionGuard;

  public SpaceAssetAssociationService(
      SuccessionReachGuard successionGuard,
      SpaceAssetAssociationRepository associationRepository,
      SpaceRepository spaceRepository,
      AssetRepository assetRepository,
      AssetAuthorization assetAuthorization,
      AssetAccessService assetAccessService,
      AssetTypes assetTypes,
      UserRepository userRepository,
      GroupMembershipResolver groupMembershipResolver,
      SpaceAccessPolicy accessPolicy,
      AuditEventRecorder auditEventRecorder,
      NotificationService notificationService) {
    this.successionGuard = successionGuard;
    this.associationRepository = associationRepository;
    this.spaceRepository = spaceRepository;
    this.assetRepository = assetRepository;
    this.assetAuthorization = assetAuthorization;
    this.assetAccessService = assetAccessService;
    this.assetTypes = assetTypes;
    this.userRepository = userRepository;
    this.groupMembershipResolver = groupMembershipResolver;
    this.accessPolicy = accessPolicy;
    this.auditEventRecorder = auditEventRecorder;
    this.notificationService = notificationService;
  }

  /**
   * Number of assets each of the given spaces shows the caller (#682) - the overview card's
   * "Quellen" figure. Mirrors {@link #listForSpace}'s rule: CURATOR/ADMIN, the owner and a system
   * admin count every association, a plain MEMBER only the assets they may read - otherwise the
   * figure next to a filtered list would give away how many are withheld, which
   * docs/features/spaces-and-assets.md forbids. One query for all associations, one for their types
   * and one readable-set lookup per type, never a query per space. Expects all spaces to belong to
   * one organization, as {@code SpaceService#listSpaces} guarantees.
   */
  public Map<UUID, Long> countVisibleBySpace(List<Space> spaces, CurrentUser caller) {
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
        caller.isSystemAdmin()
            ? Set.of()
            : readableAmong(
                headersOf(associations).values(),
                caller.id(),
                spaces.getFirst().getOrganizationId());
    return associations.stream()
        .filter(
            association -> {
              Space space = spacesById.get(association.getSpaceId());
              return caller.isSystemAdmin()
                  || accessPolicy.hasAtLeast(space, caller.id(), SpaceRole.CURATOR)
                  || readable.contains(association.getAssetId());
            })
        .collect(Collectors.groupingBy(SpaceAssetAssociation::getSpaceId, Collectors.counting()));
  }

  /**
   * The space's associated assets. For a plain {@code MEMBER}, filtered to what the caller may
   * themselves read (#203: two members of the same space with different grants see different
   * lists). For a {@code CURATOR}, {@code ADMIN} or the space owner, unfiltered - every association
   * is returned, one they cannot read with {@code readableByCaller=false} and neither name nor
   * description, so a manager can also see and detach an over-broad association.
   *
   * <p>{@link SpaceAssetLinks#hasAssociations()} is computed unfiltered, independently of the
   * (possibly filtered) item list: "no association at all" and "curated, but nothing the viewer may
   * read" need different messages (#706 review).
   */
  public SpaceAssetLinks listForSpace(UUID spaceId, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);
    accessPolicy.requireMember(space, caller);

    List<SpaceAssetAssociation> associations =
        associationRepository.findBySpaceIdOrderByCreatedAtAsc(space.getId());
    if (associations.isEmpty()) {
      return new SpaceAssetLinks(false, List.of());
    }
    boolean unfiltered =
        accessPolicy.hasAtLeast(space, caller.id(), SpaceRole.CURATOR) || caller.isSystemAdmin();
    Map<UUID, AssetHeader> headers = headersOf(associations);
    Set<UUID> readable = readableAmong(headers.values(), caller.id(), space.getOrganizationId());
    Map<UUID, String> displayNames =
        resolveDisplayNames(
            associations.stream().map(SpaceAssetAssociation::getCreatedByUserId).toList());

    List<SpaceAssetLink> items =
        associations.stream()
            .filter(association -> headers.containsKey(association.getAssetId()))
            .filter(association -> unfiltered || readable.contains(association.getAssetId()))
            .map(
                association -> {
                  AssetHeader asset = headers.get(association.getAssetId());
                  boolean readableByCaller = readable.contains(association.getAssetId());
                  return new SpaceAssetLink(
                      association,
                      asset.assetType(),
                      readableByCaller,
                      readableByCaller ? asset.name() : null,
                      readableByCaller ? asset.description() : null,
                      displayNames.get(association.getCreatedByUserId()));
                })
            .toList();
    return new SpaceAssetLinks(true, items);
  }

  /**
   * Associates the asset with {@code spaceId} (#203/#686). Idempotent per (space, asset): an
   * already-existing association is returned unchanged rather than duplicated or rejected.
   */
  @Transactional
  public SpaceAssetLink associate(
      UUID spaceId, AssetType assetType, UUID assetId, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);
    accessPolicy.requireCurator(space, caller);

    Asset asset = assetAuthorization.load(assetType, assetId, space.getOrganizationId());
    // A CURATOR may only associate an asset they can themselves access, checked through their own
    // grants - never a system-admin bypass - so "may associate" and "may search" never diverge. No
    // access answers 404 like an unknown asset, never a 403 that would confirm its existence.
    assetAuthorization.requireRole(asset, caller.id(), false, AssetRole.VIEWER);

    var existing = associationRepository.findBySpaceIdAndAssetId(space.getId(), asset.getId());
    if (existing.isPresent()) {
      return toSpaceAssetLink(existing.get(), asset);
    }

    // ADR-0036, Entscheidung 6: neither side gains reach while its succession is open - a space
    // without a capable ADMIN takes no new provisioning, and an asset without a capable owner is
    // not newly provided anywhere. An association that already exists is returned above.
    successionGuard.requireReachNotFrozen(
        SuccessionObjectType.SPACE, space.getId(), "Eine neue Bereitstellung");
    successionGuard.requireAssetReachNotFrozen(
        asset.getAssetType(), asset.getId(), "Eine neue Bereitstellung");

    SpaceAssetAssociation saved =
        associationRepository.save(
            new SpaceAssetAssociation(
                space.getId(), asset.getId(), space.getOrganizationId(), caller.id()));

    // A space is no rights subject - its id travels in the payload, like ownerId does in
    // ASSET_OWNER_CHANGED.
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(space.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.ASSET_SHARED_TO_SPACE)
            .object(definitionOf(asset).auditObjectType(), asset.getId(), asset.getName())
            .after(Map.of("spaceId", space.getId().toString()))
            .outcome(AuditOutcome.SUCCESS)
            .build());

    notifyOwnerIfMixedAudience(space, asset, caller.id());

    return toSpaceAssetLink(saved, asset);
  }

  /**
   * Removes an association - allowed for a CURATOR or above on the space, or unilaterally for a
   * MANAGER or above on the asset itself, regardless of the caller's own space membership (#203:
   * "Der Eigentümer des Assets ... kann jede davon jederzeit einseitig lösen"). A no-op if no such
   * association exists.
   */
  @Transactional
  public void detach(UUID spaceId, UUID assetId, CurrentUser caller) {
    Space space = loadSpace(spaceId, caller);
    Asset asset = requireAsset(assetId, space.getOrganizationId());
    AssetTypeDefinition definition = definitionOf(asset);

    boolean spaceCurator =
        accessPolicy.hasAtLeast(space, caller.id(), SpaceRole.CURATOR) || caller.isSystemAdmin();
    boolean assetManager = assetAuthorization.canManage(asset, caller.id(), caller.isSystemAdmin());
    if (!spaceCurator && !assetManager) {
      throw new AccessDeniedException(
          "Nur Kuratoren dieses Space oder Verwaltende der "
              + definition.singular()
              + " können die Zuordnung lösen");
    }

    associationRepository
        .findBySpaceIdAndAssetId(space.getId(), asset.getId())
        .ifPresent(
            association -> {
              associationRepository.delete(association);
              auditEventRecorder.recordUserAction(
                  AuditEvent.builder()
                      .organizationId(space.getOrganizationId())
                      .actor(caller.id())
                      .type(AuditEventType.ASSET_DETACHED_FROM_SPACE)
                      .object(definition.auditObjectType(), asset.getId(), asset.getName())
                      .before(Map.of("spaceId", space.getId().toString()))
                      .outcome(AuditOutcome.SUCCESS)
                      .build());
            });
  }

  /**
   * Every space the asset is associated with - the owner-facing view (#203), requiring MANAGER or
   * above on the asset. Never filtered by the caller's own space membership: the owner sees every
   * association, including in spaces they do not belong to.
   */
  public List<AssetSpaceLink> listForAsset(AssetType assetType, UUID assetId, CurrentUser caller) {
    Asset asset = assetAuthorization.load(assetType, assetId, caller.organizationId());
    assetAuthorization.requireRole(asset, caller.id(), caller.isSystemAdmin(), AssetRole.MANAGER);

    List<SpaceAssetAssociation> associations =
        associationRepository.findByAssetIdOrderByCreatedAtAsc(asset.getId());
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
              return new AssetSpaceLink(
                  association,
                  space != null ? space.getName() : "",
                  space != null && !allMembersCanRead(space, asset),
                  displayNames.get(association.getCreatedByUserId()));
            })
        .toList();
  }

  /**
   * Every knowledge library associated with the space, without a rights filter of its own - the
   * Suchbereich a Rechteprofil-Lauf intersects with the libraries that profile may read (#1835).
   * The intersection is the rights decision, and it happens at the caller.
   */
  public Set<UUID> libraryIdsInSpace(UUID spaceId) {
    return associationRepository.findAssetIdsBySpaceIdAndAssetType(
        spaceId, KnowledgeLibrary.ASSET_TYPE);
  }

  /**
   * Notifies the asset's owner (every member, if group-owned) when the space just associated has at
   * least one member without read access to it (#203: "Benachrichtigung statt Zustimmung"). The
   * caller who created the association is never among the recipients - they know what they did.
   */
  private void notifyOwnerIfMixedAudience(Space space, Asset asset, UUID triggeringUserId) {
    if (allMembersCanRead(space, asset)) {
      return;
    }
    AssetTypeDefinition definition = definitionOf(asset);
    Set<UUID> recipients =
        switch (asset.getOwnerType()) {
          case USER -> Set.of(asset.getOwnerUserId());
          case GROUP ->
              groupMembershipResolver.resolveUserIds(
                  PermissionSubject.group(asset.getOwnerGroupId(), asset.getOrganizationId()));
        };
    String title = "Ihre " + definition.singular() + " wurde in einem Space bereitgestellt";
    String body =
        "Die "
            + definition.singular()
            + " \""
            + asset.getName()
            + "\" wurde im Space \""
            + space.getName()
            + "\" bereitgestellt, dessen Mitglieder nicht alle Lesezugriff darauf haben.";
    for (UUID recipientId : recipients) {
      if (recipientId.equals(triggeringUserId)) {
        continue;
      }
      notificationService.notify(
          asset.getOrganizationId(),
          recipientId,
          NotificationType.LIBRARY_ASSOCIATED_TO_MIXED_SPACE,
          definition.auditObjectType(),
          asset.getId(),
          title,
          body);
    }
  }

  /** Whether every current member of {@code space} already has at least VIEWER on the asset. */
  private boolean allMembersCanRead(Space space, Asset asset) {
    // A space that reaches nobody answers true: there is no member who cannot read, so nobody the
    // mixed-audience notification would be about.
    for (UUID memberId : personalMembersOf(space)) {
      // Deliberately not systemAdmin-bypassed: an admin member would trivially satisfy "can read"
      // and mask whether the ordinary members have a real grant.
      if (!assetAuthorization.canRead(asset, memberId, false)) {
        return false;
      }
    }
    return true;
  }

  /**
   * The people a space actually reaches: its own member rows plus the members of every group that
   * is a member (#1815).
   */
  private Set<UUID> personalMembersOf(Space space) {
    Set<UUID> members = new LinkedHashSet<>();
    for (SpaceMembership membership : space.getMemberships()) {
      if (membership.isUserSubject()) {
        members.add(membership.getUserId());
      } else {
        members.addAll(
            groupMembershipResolver.resolveUserIds(
                PermissionSubject.group(membership.getGroupId(), membership.getOrganizationId())));
      }
    }
    return members;
  }

  /** The asset ids among {@code assets} the user may read, one readable-set lookup per type. */
  private Set<UUID> readableAmong(
      Collection<AssetHeader> assets, UUID userId, UUID organizationId) {
    Set<UUID> readable = new LinkedHashSet<>();
    for (AssetType assetType :
        assets.stream().map(AssetHeader::assetType).collect(Collectors.toSet())) {
      readable.addAll(assetAccessService.readableAssetIds(assetType, userId, organizationId));
    }
    return readable;
  }

  private Map<UUID, AssetHeader> headersOf(List<SpaceAssetAssociation> associations) {
    Map<UUID, AssetHeader> headers = new HashMap<>();
    for (AssetHeader header :
        assetRepository.findHeadersByIdIn(
            associations.stream().map(SpaceAssetAssociation::getAssetId).toList())) {
      headers.put(header.id(), header);
    }
    return headers;
  }

  private Space loadSpace(UUID spaceId, CurrentUser caller) {
    return OrganizationScopedLoader.load(
        () -> spaceRepository.findByIdWithMemberships(spaceId),
        Space::getOrganizationId,
        caller.organizationId(),
        "Space nicht gefunden");
  }

  /** An asset of any type in the organization; an unknown one answers like an unknown library. */
  private Asset requireAsset(UUID assetId, UUID organizationId) {
    Asset asset =
        assetRepository
            .findById(assetId)
            .filter(found -> found.getOrganizationId().equals(organizationId))
            .orElseThrow(() -> new NotFoundException("Objekt nicht gefunden"));
    return assetAuthorization.load(asset.getAssetType(), asset.getId(), organizationId);
  }

  private AssetTypeDefinition definitionOf(Asset asset) {
    return assetTypes.require(asset.getAssetType());
  }

  private Map<UUID, String> resolveDisplayNames(List<UUID> userIds) {
    Map<UUID, String> result = new HashMap<>();
    for (User user : userRepository.findAllById(userIds)) {
      result.put(
          user.getId(), user.getDisplayName() != null ? user.getDisplayName() : user.getEmail());
    }
    return result;
  }

  private SpaceAssetLink toSpaceAssetLink(SpaceAssetAssociation association, Asset asset) {
    return new SpaceAssetLink(
        association,
        asset.getAssetType(),
        true,
        asset.getName(),
        asset.getDescription(),
        resolveDisplayNames(List.of(association.getCreatedByUserId()))
            .get(association.getCreatedByUserId()));
  }
}
