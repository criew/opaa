package io.opaa.group;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.oidc.ProviderGroupDirectory;
import io.opaa.auth.oidc.ProviderGroupEffects;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.AssetOwnershipDirectory;
import io.opaa.permission.CapabilityGrantRepository;
import io.opaa.permission.GroupMembershipHistoryCause;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSpaceMembershipDirectory;
import io.opaa.permission.GroupSpaceMembershipRef;
import io.opaa.permission.PermissionHistoryService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Answers {@link ProviderGroupDirectory} from this package's repositories - the one place the
 * provider administration learns anything about groups, so {@code io.opaa.auth.oidc} holds no
 * {@link Group} and the dependency between the two packages stays one-way.
 *
 * <p>"Effect" is every reason a group must not silently disappear with its provider: a grant it
 * holds, an asset it owns, and a still-conferring diagnostic authorisation whose scope it is - that
 * last one because {@code fk_diagnostic_impersonation_grants_scope_organization} cascades, so the
 * row would go without the revocation event ADR-0016 requires - and, since #1815, a space
 * membership the group holds: {@code space_memberships.group_id} is {@code ON DELETE RESTRICT} as
 * well, so a group that is a space member would take {@link #deleteGroupsOfProvider} into a
 * foreign-key violation instead of into the 409.
 */
@Component
class ProviderGroupDirectoryAdapter implements ProviderGroupDirectory {

  private final GroupRepository groupRepository;
  private final AssetGrantRepository grantRepository;
  private final CapabilityGrantRepository capabilityGrantRepository;
  private final List<AssetOwnershipDirectory> assetOwnershipDirectories;
  private final GroupScopeUsageDirectory scopeUsageDirectory;
  private final GroupSpaceMembershipDirectory spaceMembershipDirectory;
  private final GroupMembershipResolver membershipResolver;
  private final PermissionHistoryService permissionHistoryService;
  private final AuditEventRecorder auditEventRecorder;

  ProviderGroupDirectoryAdapter(
      GroupRepository groupRepository,
      AssetGrantRepository grantRepository,
      CapabilityGrantRepository capabilityGrantRepository,
      List<AssetOwnershipDirectory> assetOwnershipDirectories,
      GroupScopeUsageDirectory scopeUsageDirectory,
      GroupSpaceMembershipDirectory spaceMembershipDirectory,
      GroupMembershipResolver membershipResolver,
      PermissionHistoryService permissionHistoryService,
      AuditEventRecorder auditEventRecorder) {
    this.groupRepository = groupRepository;
    this.grantRepository = grantRepository;
    this.capabilityGrantRepository = capabilityGrantRepository;
    this.assetOwnershipDirectories = assetOwnershipDirectories;
    this.scopeUsageDirectory = scopeUsageDirectory;
    this.spaceMembershipDirectory = spaceMembershipDirectory;
    this.membershipResolver = membershipResolver;
    this.permissionHistoryService = permissionHistoryService;
    this.auditEventRecorder = auditEventRecorder;
  }

  @Override
  public ProviderGroupEffects effectsOf(UUID providerId) {
    List<UUID> groupIds =
        groupRepository.findByProviderId(providerId).stream().map(Group::getId).toList();
    if (groupIds.isEmpty()) {
      return ProviderGroupEffects.NONE;
    }
    List<AssetGrant> grants = grantRepository.findBySubjectGroupIdIn(groupIds);
    Set<UUID> effective = new HashSet<>();
    Set<UUID> grantedAssets = new HashSet<>();
    for (AssetGrant grant : grants) {
      effective.add(grant.getSubjectGroupId());
      grantedAssets.add(grant.getAssetId());
    }
    long owningGroups = 0;
    for (UUID groupId : groupIds) {
      if (ownsAnything(groupId)) {
        owningGroups++;
        effective.add(groupId);
      }
    }
    List<GroupSpaceMembershipRef> spaceMemberships =
        spaceMembershipDirectory.spaceMembershipsOf(groupIds);
    Set<UUID> spaces = new HashSet<>();
    for (GroupSpaceMembershipRef membership : spaceMemberships) {
      effective.add(membership.groupId());
      spaces.add(membership.spaceId());
    }
    List<UUID> scopedAuthorizations =
        scopeUsageDirectory.scopeGroupsOfUnspentAuthorizations(groupIds);
    effective.addAll(scopedAuthorizations);
    // A group holding only a capability is effective too: its rows block
    // fk_capability_grants_subject_group_organization, and deleteGroupsOfProvider below deletes
    // every group of the provider once the caller confirms there is no effect left to decide about.
    effective.addAll(capabilityGrantRepository.findSubjectGroupIdsIn(groupIds));
    return new ProviderGroupEffects(
        effective.size(),
        grants.size(),
        grantedAssets.size(),
        owningGroups,
        spaceMemberships.size(),
        spaces.size(),
        scopedAuthorizations.size());
  }

  @Override
  public void deleteGroupsOfProvider(UUID providerId, UUID actorUserId) {
    List<UUID> affectedUserIds = new ArrayList<>();
    for (Group group : groupRepository.findByProviderId(providerId)) {
      // group_id carries no foreign key on group_membership_history (see
      // PermissionHistoryService): the cascade below never closes these intervals on its own.
      for (GroupMembership membership : group.getMemberships()) {
        affectedUserIds.add(membership.getUserId());
        permissionHistoryService.recordMembershipRemoved(
            group.getId(),
            group.getOrganizationId(),
            membership.getUserId(),
            GroupMembershipHistoryCause.GROUP_DELETED,
            actorUserId);
      }
      auditEventRecorder.recordUserAction(
          AuditEvent.builder()
              .organizationId(group.getOrganizationId())
              .actor(actorUserId)
              .type(AuditEventType.GROUP_DELETED)
              .object(AuditObjectType.GROUP, group.getId(), group.getName())
              .before(Map.of("name", group.getName(), "memberCount", group.getMemberships().size()))
              .reason("Der Identitätsanbieter dieser Gruppe wurde gelöscht.")
              .outcome(AuditOutcome.SUCCESS)
              .build());
      groupRepository.delete(group);
    }
    invalidateAfterCompletion(affectedUserIds);
  }

  /**
   * Defers the eviction until the enclosing transaction has finished - {@code afterCompletion}, not
   * {@code afterCommit}, so a rollback also evicts entries this transaction may have warmed with
   * its own uncommitted reads (same reasoning as {@code GroupService}).
   */
  private void invalidateAfterCompletion(List<UUID> userIds) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      membershipResolver.invalidateUsers(userIds);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            membershipResolver.invalidateUsers(userIds);
          }
        });
  }

  private boolean ownsAnything(UUID groupId) {
    for (AssetOwnershipDirectory ownership : assetOwnershipDirectories) {
      if (ownership.existsAssetOwnedByGroup(groupId)) {
        return true;
      }
    }
    return false;
  }
}
