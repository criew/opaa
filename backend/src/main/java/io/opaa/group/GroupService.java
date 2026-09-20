package io.opaa.group;

import static java.util.stream.Collectors.toSet;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.Capability;
import io.opaa.api.types.GroupKind;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.OrganizationScopedLoader;
import io.opaa.common.ValidationException;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.AssetOwnershipDirectory;
import io.opaa.permission.CapabilityGrantRepository;
import io.opaa.permission.CapabilityService;
import io.opaa.permission.GroupMembershipHistoryCause;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.PermissionHistoryService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manages groups as permission subjects. All endpoints are system-admin only (enforced at the
 * controller via {@code @PreAuthorize}); this service still resolves the caller's organization and
 * enforces the organization boundary the same way {@code SpaceService} does, because a system admin
 * exists per organization and must never see or touch another organization's groups.
 *
 * <p>Only {@link GroupKind#AD_HOC} groups can be created, renamed, deleted or have their membership
 * managed here. {@link GroupKind#ORG_UNIT} groups are synchronised from the directory (#237) and
 * are read-only through this service.
 *
 * <p>Deleting a group that owns an asset is blocked until ownership is transferred (see the feature
 * spec's "Eigentuemerschaft und Verwaisung" and issue #200's acceptance criteria). Which asset
 * types exist is not this class's business: every one of them contributes an {@link
 * AssetOwnershipDirectory}, and {@link #deleteGroup} asks all of them.
 *
 * <p>Deleting a group that merely <em>holds a grant</em> - not necessarily owns anything - is
 * blocked too, and independently of the ownership check above. {@code
 * fk_asset_grants_subject_group_organization} is RESTRICT, exactly like the owner keys; without the
 * check in {@link #deleteGroup}, the everyday case the feature spec's "Freigabestufen und
 * Auffindbarkeit" describes - "an Abteilung 5 freigeben" is a grant to the group representing
 * Abteilung 5, not ownership - would surface as an unhandled {@code
 * DataIntegrityViolationException} (HTTP 500) the first time anyone tried to delete such a group.
 */
@Service
@Transactional(readOnly = true)
public class GroupService {

  private static final int MAX_NAME_LENGTH = 255;
  private static final int MAX_DESCRIPTION_LENGTH = 2000;

  private final GroupRepository groupRepository;
  private final UserRepository userRepository;
  private final OidcProviderRepository providerRepository;
  private final GroupMembershipResolver membershipResolver;
  private final List<AssetOwnershipDirectory> assetOwnershipDirectories;
  private final AssetGrantRepository grantRepository;
  private final CapabilityGrantRepository capabilityGrantRepository;
  private final CapabilityService capabilityService;
  private final PermissionHistoryService permissionHistoryService;
  private final AuditEventRecorder auditEventRecorder;

  public GroupService(
      GroupRepository groupRepository,
      UserRepository userRepository,
      OidcProviderRepository providerRepository,
      GroupMembershipResolver membershipResolver,
      List<AssetOwnershipDirectory> assetOwnershipDirectories,
      AssetGrantRepository grantRepository,
      CapabilityGrantRepository capabilityGrantRepository,
      CapabilityService capabilityService,
      PermissionHistoryService permissionHistoryService,
      AuditEventRecorder auditEventRecorder) {
    this.groupRepository = groupRepository;
    this.userRepository = userRepository;
    this.providerRepository = providerRepository;
    this.membershipResolver = membershipResolver;
    this.assetOwnershipDirectories = assetOwnershipDirectories;
    this.grantRepository = grantRepository;
    this.capabilityGrantRepository = capabilityGrantRepository;
    this.capabilityService = capabilityService;
    this.permissionHistoryService = permissionHistoryService;
    this.auditEventRecorder = auditEventRecorder;
  }

  @Transactional
  public GroupDetail createGroup(GroupCreation creation, CurrentUser caller) {
    capabilityService.requireCapability(caller, Capability.CREATE_INTERNAL_GROUP);
    String normalizedName = validateName(creation.name());
    validateDescription(creation.description());

    Group group =
        Group.internal(caller.organizationId(), normalizedName, creation.description(), null);
    Group saved = groupRepository.save(group);
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(saved.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.GROUP_CREATED)
            .object(AuditObjectType.GROUP, saved.getId(), saved.getName())
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return toGroupDetail(saved);
  }

  public List<GroupOverview> listGroups(CurrentUser caller) {
    return toOverviews(
        groupRepository.findByOrganizationIdWithMemberships(caller.organizationId()));
  }

  /**
   * Lists the groups the given user is a direct member of - not admin-restricted, unlike {@link
   * #listGroups}. Backs {@code GET /api/v1/me/groups}, which the frontend's library-creation dialog
   * uses to offer only groups the caller can actually own a library through (see {@code
   * KnowledgeLibraryService#createLibrary}, which rejects a GROUP owner the caller is not a member
   * of).
   *
   * <p>Excludes dissolved groups: a dissolved group's membership is frozen rather than cleared (see
   * {@link Group#isDissolved()}), so it would otherwise still surface here. {@code
   * KnowledgeLibraryService#createLibrary} does not currently check {@code isDissolved()} itself
   * before writing the owner grant (see #201/#202) - so today, offering a dissolved group here is
   * the only thing standing between the picker and a library owned by a group that no longer
   * organisationally exists.
   *
   * <p>Also filters to the caller's organization, mirroring {@link #listGroups}: as of migration
   * 047 this filter is structurally unreachable, not merely unexercised - {@code
   * fk_group_memberships_user_organization} (composite on {@code user_id, organization_id}) and
   * {@code fk_group_memberships_group_organization} (migration 009, composite on {@code group_id,
   * organization_id}) together force a membership row's {@code organization_id} to match both the
   * member's and the group's actual organization, so no row this filter would ever reject can exist
   * in the first place - see {@code
   * GroupServiceIntegrationTest#aMembershipRowCanNeverCrossAnOrganizationBoundaryAtTheDatabaseLevel}
   * (#308), which proves the database rejects constructing one directly. Left in place anyway as a
   * second, independent defense line the class Javadoc's philosophy calls for - one that does not
   * rely on the schema invariant above continuing to hold, in case a future migration ever loosens
   * it.
   */
  public List<GroupOverview> listMyGroups(CurrentUser caller) {
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(caller.id());
    if (groupIds.isEmpty()) {
      return List.of();
    }
    return toOverviews(
        groupRepository.findAllByIdWithMemberships(groupIds).stream()
            .filter(group -> !group.isDissolved())
            .filter(group -> group.getOrganizationId().equals(caller.organizationId()))
            .toList());
  }

  /**
   * Resolves the origin of a whole list in one read of {@code oidc_providers} - a table with a
   * handful of rows, read once instead of once per group.
   */
  private List<GroupOverview> toOverviews(List<Group> groups) {
    Set<UUID> providerIds =
        groups.stream().map(Group::getProviderId).filter(Objects::nonNull).collect(toSet());
    Map<UUID, GroupProviderView> byId = new HashMap<>();
    if (!providerIds.isEmpty()) {
      providerRepository
          .findAllById(providerIds)
          .forEach(provider -> byId.put(provider.getId(), toProviderView(provider)));
    }
    return groups.stream()
        .map(group -> new GroupOverview(group, byId.get(group.getProviderId())))
        .toList();
  }

  private GroupProviderView providerOf(Group group) {
    if (group.getProviderId() == null) {
      return null;
    }
    return providerRepository
        .findById(group.getProviderId())
        .map(GroupService::toProviderView)
        .orElse(null);
  }

  private static GroupProviderView toProviderView(OidcProvider provider) {
    return new GroupProviderView(
        provider.getId(), provider.getDisplayName(), provider.isExternal(), provider.isEnabled());
  }

  public GroupDetail getGroup(UUID groupId, CurrentUser caller) {
    Group group = loadGroup(groupId, caller);
    return toGroupDetail(group);
  }

  @Transactional
  public GroupDetail updateGroup(UUID groupId, GroupUpdate update, CurrentUser caller) {
    Group group = loadGroup(groupId, caller);
    rejectOrgUnit(group);

    String normalizedName = validateName(update.name());
    validateDescription(update.description());
    String previousName = group.getName();
    String previousDescription = group.getDescription();
    group.updateDetails(normalizedName, update.description());
    Group updated = groupRepository.save(group);
    boolean nameChanged = !Objects.equals(previousName, updated.getName());
    boolean descriptionChanged = !Objects.equals(previousDescription, updated.getDescription());
    if (nameChanged || descriptionChanged) {
      // #392 code review, finding 4: changedFields names which fields changed without carrying the
      // free-text description content itself into the append-only log - see
      // KnowledgeLibraryService#updateLibrary's identical treatment.
      List<String> changedFields = new ArrayList<>();
      if (nameChanged) {
        changedFields.add("name");
      }
      if (descriptionChanged) {
        changedFields.add("description");
      }
      auditEventRecorder.recordUserAction(
          AuditEvent.builder()
              .organizationId(updated.getOrganizationId())
              .actor(caller.id())
              .type(AuditEventType.GROUP_CHANGED)
              .object(AuditObjectType.GROUP, updated.getId(), updated.getName())
              .before(Map.of("changedFields", changedFields))
              .after(Map.of("changedFields", changedFields))
              .outcome(AuditOutcome.SUCCESS)
              .build());
    }
    return toGroupDetail(updated);
  }

  @Transactional
  public void deleteGroup(UUID groupId, CurrentUser caller) {
    Group group = loadGroup(groupId, caller);
    rejectOrgUnit(group);
    // The owner foreign keys of the asset tables are RESTRICT: without this check, deleting a
    // group that still owns an asset would surface as an unhandled DataIntegrityViolationException
    // -> HTTP 500 with no indication of the actual cause. Every asset type answers for itself
    // through AssetOwnershipDirectory, so a further type extends this check by adding a bean.
    for (AssetOwnershipDirectory ownership : assetOwnershipDirectories) {
      if (ownership.existsAssetOwnedByGroup(groupId)) {
        throw new ConflictException(ownership.ownedAssetConflictMessage());
      }
    }
    // A group that merely holds a grant (never owns anything) hits the same RESTRICT constraint
    // via fk_asset_grants_subject_group_organization - see the class Javadoc.
    if (grantRepository.existsBySubjectGroupId(groupId)) {
      throw new ConflictException(
          "Die Gruppe hat noch Berechtigungen auf Bibliotheken und kann nicht gelöscht werden");
    }
    // The same RESTRICT pattern one table further:
    // fk_capability_grants_subject_group_organization.
    if (capabilityGrantRepository.existsBySubjectGroupId(groupId)) {
      throw new ConflictException(
          "Die Gruppe hat noch Anlegerechte und kann nicht gelöscht werden");
    }

    List<UUID> affectedUserIds =
        group.getMemberships().stream().map(GroupMembership::getUserId).toList();
    // group_id carries no foreign key on group_membership_history (deliberately - see
    // PermissionHistoryService's class Javadoc), so the CASCADE delete below
    // (fk_group_memberships_group_organization) never closes these intervals on its own. Without
    // this, a deleted group's still-open membership intervals kept reporting "currently a member"
    // of a group that no longer exists. Read the live memberships before the delete cascades them
    // away - deleteGroup is only reachable once the guards above confirm no live grant remains, so
    // there is nothing to close on the asset_grant_history side.
    for (GroupMembership membership : group.getMemberships()) {
      permissionHistoryService.recordMembershipRemoved(
          group.getId(),
          group.getOrganizationId(),
          membership.getUserId(),
          GroupMembershipHistoryCause.GROUP_DELETED,
          caller.id());
    }
    // #392: GROUP_DELETED also covers the group's dissolution ("Auflösung einer Gruppe") - one
    // entry for the group itself, not one per member removed above (those are already covered by
    // the group's own deletion, not a separate membership-removal action).
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(group.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.GROUP_DELETED)
            .object(AuditObjectType.GROUP, group.getId(), group.getName())
            .before(Map.of("name", group.getName(), "memberCount", affectedUserIds.size()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    groupRepository.delete(group);
    invalidateAfterCommit(() -> membershipResolver.invalidateUsers(affectedUserIds));
  }

  public List<GroupMemberView> listMembers(UUID groupId, CurrentUser caller) {
    Group group = loadGroup(groupId, caller);
    return toGroupMemberViews(group);
  }

  @Transactional
  public GroupMemberView addMember(UUID groupId, UUID memberUserId, CurrentUser caller) {
    Group group = loadGroup(groupId, caller);
    rejectOrgUnit(group);
    // Resolving the target user first also turns a non-existent userId into a clean 404 instead
    // of a raw foreign-key violation from the membership insert below.
    requireUserInOrganization(memberUserId, group.getOrganizationId());

    if (userMembership(group, memberUserId) != null) {
      throw new ConflictException("Der Benutzer ist bereits Mitglied dieser Gruppe");
    }

    GroupMembership membership = new GroupMembership(memberUserId, group.getOrganizationId());
    group.addMembership(membership);
    groupRepository.save(group);
    permissionHistoryService.recordMembershipAdded(
        group.getId(),
        group.getOrganizationId(),
        memberUserId,
        GroupMembershipHistoryCause.ADDED,
        caller.id());
    auditEventRecorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(group.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.GROUP_MEMBER_ADDED)
            .object(AuditObjectType.GROUP, group.getId(), group.getName())
            .subject(AuditSubjectKind.USER, memberUserId)
            .outcome(AuditOutcome.SUCCESS)
            .build());
    invalidateAfterCommit(() -> membershipResolver.invalidateUser(memberUserId));

    return new GroupMemberView(membership, resolveDisplayName(membership.getUserId()));
  }

  @Transactional
  public void removeMember(UUID groupId, UUID memberUserId, CurrentUser caller) {
    Group group = loadGroup(groupId, caller);
    rejectOrgUnit(group);

    GroupMembership target = userMembership(group, memberUserId);
    if (target == null) {
      throw new NotFoundException("Mitglied der Gruppe nicht gefunden");
    }

    group.removeMembership(target);
    groupRepository.save(group);
    permissionHistoryService.recordMembershipRemoved(
        group.getId(),
        group.getOrganizationId(),
        memberUserId,
        GroupMembershipHistoryCause.REMOVED,
        caller.id());
    auditEventRecorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(group.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.GROUP_MEMBER_REMOVED)
            .object(AuditObjectType.GROUP, group.getId(), group.getName())
            .subject(AuditSubjectKind.USER, memberUserId)
            .outcome(AuditOutcome.SUCCESS)
            .build());
    invalidateAfterCommit(() -> membershipResolver.invalidateUser(memberUserId));
  }

  private String validateName(String name) {
    if (name == null || name.isBlank()) {
      throw new ValidationException("name ist erforderlich");
    }
    String trimmed = name.trim();
    if (trimmed.length() > MAX_NAME_LENGTH) {
      throw new ValidationException("name darf höchstens " + MAX_NAME_LENGTH + " Zeichen umfassen");
    }
    return trimmed;
  }

  private void validateDescription(String description) {
    if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
      throw new ValidationException(
          "description darf höchstens " + MAX_DESCRIPTION_LENGTH + " Zeichen umfassen");
    }
  }

  /**
   * Defers a cache invalidation until the enclosing transaction has finished, instead of running it
   * immediately at the point of the call. Every {@code @Transactional} method here commits through
   * the Spring proxy only after it returns, so invalidating inline (as an earlier version of this
   * class did) can race a concurrent reader: it can observe the pre-image under {@code READ
   * COMMITTED}, repopulate the cache with it, and then have this transaction commit - leaving a
   * revoked membership readable from the cache for up to the cache's expiry (see {@link
   * GroupMembershipResolver}).
   *
   * <p>Registered as {@code afterCompletion} rather than {@code afterCommit} so a rollback also
   * evicts the entry the transaction may have touched - a stale hit is the wrong failure mode
   * either way, so there is no reason to skip cleanup on the rollback path.
   *
   * <p>Falls back to running immediately when no transaction is active (e.g. called directly in a
   * test), so the invalidation is never silently dropped.
   */
  private void invalidateAfterCommit(Runnable invalidation) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      invalidation.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            invalidation.run();
          }
        });
  }

  /** Only {@link GroupKind#AD_HOC} groups are managed here; the other kinds have their source. */
  private void rejectOrgUnit(Group group) {
    if (group.isOrgUnit()) {
      throw new ValidationException(
          "Organisationseinheiten werden aus dem Verzeichnis synchronisiert und können hier"
              + " nicht bearbeitet werden");
    }
    if (group.getKind() == GroupKind.IDENTITY_PROVIDER) {
      throw new ValidationException(
          "Diese Gruppe stammt aus dem Identitätsanbieter und wird bei jeder Anmeldung abgeglichen;"
              + " sie kann hier nicht bearbeitet werden");
    }
  }

  /**
   * Resolves a user and enforces the organization boundary for it via {@link
   * OrganizationScopedLoader} - mirrors {@code SpaceService#requireUserInOrganization}. Returns 404
   * rather than 403 both when the user does not exist and when it belongs to a different
   * organization, so a caller cannot distinguish "no such user" from "user in another
   * organization".
   */
  private User requireUserInOrganization(UUID userId, UUID organizationId) {
    return OrganizationScopedLoader.load(
        () -> userRepository.findById(userId),
        User::getOrganizationId,
        organizationId,
        "Benutzer nicht gefunden");
  }

  /**
   * Loads a group and enforces the organization boundary via {@link OrganizationScopedLoader},
   * treating a group from another organization as not found. Applies to system admins as well; the
   * boundary is not overstepped even to reveal existence.
   */
  private Group loadGroup(UUID groupId, CurrentUser caller) {
    return OrganizationScopedLoader.load(
        () -> groupRepository.findByIdWithMemberships(groupId),
        Group::getOrganizationId,
        caller.organizationId(),
        "Gruppe nicht gefunden");
  }

  private GroupMembership userMembership(Group group, UUID userId) {
    return group.getMemberships().stream()
        .filter(membership -> membership.getUserId().equals(userId))
        .findFirst()
        .orElse(null);
  }

  private String resolveDisplayName(UUID userId) {
    return userRepository
        .findById(userId)
        .map(u -> u.getDisplayName() != null ? u.getDisplayName() : u.getEmail())
        .orElse(null);
  }

  private Map<UUID, String> resolveDisplayNames(List<UUID> userIds) {
    Map<UUID, String> result = new HashMap<>();
    for (User user : userRepository.findAllById(userIds)) {
      result.put(
          user.getId(), user.getDisplayName() != null ? user.getDisplayName() : user.getEmail());
    }
    return result;
  }

  private List<GroupMemberView> toGroupMemberViews(Group group) {
    List<UUID> memberIds = group.getMemberships().stream().map(GroupMembership::getUserId).toList();
    Map<UUID, String> displayNames = resolveDisplayNames(memberIds);

    return group.getMemberships().stream()
        .map(m -> new GroupMemberView(m, displayNames.get(m.getUserId())))
        .toList();
  }

  private GroupDetail toGroupDetail(Group group) {
    return new GroupDetail(group, toGroupMemberViews(group), providerOf(group));
  }
}
