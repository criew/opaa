package io.opaa.group;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.permission.AssetOwnershipDirectory;
import io.opaa.permission.GroupMembershipHistoryCause;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSpaceMembershipDirectory;
import io.opaa.permission.PermissionHistoryService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deactivating an internal group for the time being, and lifting it again (ADR-0036, Entscheidung
 * 14). Who may do it and on which group is decided by {@link GroupService} before it gets here.
 *
 * <p>The switch itself is one column; what makes it take effect is {@link GroupMembershipResolver},
 * which leaves a deactivated group out of every resolution. This class owns the three things around
 * the switch: the refusal while the group is the only acting party of something, the membership
 * history - every interval of the group ends with the deactivation and starts again with the
 * reactivation, so the Stichtagsauskunft knows the group reached nobody meanwhile - and the cache
 * eviction after commit, so the change applies to running sessions at once.
 */
@Component
class GroupDeactivation {

  private final GroupRepository groupRepository;
  private final GroupEffectsService effectsService;
  private final List<AssetOwnershipDirectory> ownershipDirectories;
  private final GroupSpaceMembershipDirectory spaceMembershipDirectory;
  private final GroupMembershipResolver membershipResolver;
  private final PermissionHistoryService permissionHistoryService;
  private final AuditEventRecorder auditEventRecorder;
  private final Clock clock;

  GroupDeactivation(
      GroupRepository groupRepository,
      GroupEffectsService effectsService,
      List<AssetOwnershipDirectory> ownershipDirectories,
      GroupSpaceMembershipDirectory spaceMembershipDirectory,
      GroupMembershipResolver membershipResolver,
      PermissionHistoryService permissionHistoryService,
      AuditEventRecorder auditEventRecorder,
      Clock clock) {
    this.groupRepository = groupRepository;
    this.effectsService = effectsService;
    this.ownershipDirectories = ownershipDirectories;
    this.spaceMembershipDirectory = spaceMembershipDirectory;
    this.membershipResolver = membershipResolver;
    this.permissionHistoryService = permissionHistoryService;
    this.auditEventRecorder = auditEventRecorder;
    this.clock = clock;
  }

  /** The counted consequences, and the reason a deactivation would be refused. */
  GroupDeactivationPreview preview(Group group, CurrentUser caller) {
    GroupEffectsView effects =
        effectsService.listEffects(caller, null, List.of(group.getId())).get(0);
    return new GroupDeactivationPreview(
        group.getId(),
        group.isDeactivated(),
        effects.assetGrants(),
        effects.grantedAssets(),
        effects.spaceMemberships(),
        effects.spaces(),
        effects.capabilities(),
        membershipResolver.activeMemberCount(group.getId(), group.getOrganizationId()),
        group.isDeactivated() ? null : blockedReason(group));
  }

  /**
   * Deactivates the group, unless it is the only owner of an asset or the last capable {@code
   * ADMIN} of a space - either would leave an object without anybody able to act, through an
   * administrative act that ADR-0036, Entscheidung 6 rules out.
   */
  void deactivate(Group group, CurrentUser caller) {
    String blocked = blockedReason(group);
    if (blocked != null) {
      throw new ConflictException(blocked);
    }
    GroupDeactivationPreview consequences = preview(group, caller);
    group.deactivate(Instant.now(clock));
    groupRepository.save(group);
    for (GroupMembership membership : group.getMemberships()) {
      permissionHistoryService.recordMembershipRemoved(
          group.getId(),
          group.getOrganizationId(),
          membership.getUserId(),
          GroupMembershipHistoryCause.GROUP_DEACTIVATED,
          caller.id());
    }
    record(AuditEventType.GROUP_DEACTIVATED, group, consequences, caller);
    evictMembersAfterCommit(group);
  }

  /** Lifts the deactivation: everything the group holds takes effect again, unchanged. */
  void reactivate(Group group, CurrentUser caller) {
    group.liftDeactivation();
    groupRepository.save(group);
    for (GroupMembership membership : group.getMemberships()) {
      permissionHistoryService.recordMembershipAdded(
          group.getId(),
          group.getOrganizationId(),
          membership.getUserId(),
          GroupMembershipHistoryCause.GROUP_REACTIVATED,
          caller.id());
    }
    record(AuditEventType.GROUP_REACTIVATED, group, preview(group, caller), caller);
    evictMembersAfterCommit(group);
  }

  /** The German sentence of the refusal, or null when nothing is in the way. */
  private String blockedReason(Group group) {
    List<String> reasons = new ArrayList<>();
    List<String> owned = new ArrayList<>();
    for (AssetOwnershipDirectory directory : ownershipDirectories) {
      String summary = directory.ownedAssetSummary(group.getId(), group.getOrganizationId());
      if (!summary.isEmpty()) {
        owned.add(summary);
      }
    }
    if (!owned.isEmpty()) {
      reasons.add(
          "Die Gruppe ist einzige Eigentümerin von "
              + String.join(" und ", owned)
              + ". Übertragen Sie zuerst das Eigentum, etwa über „Rechte übertragen“.");
    }
    long spaces = spaceMembershipDirectory.countSpacesRelyingOnGroupAsLastAdmin(group.getId());
    if (spaces > 0) {
      reasons.add(
          "Die Gruppe ist das letzte handlungsfähige ADMIN-Mitglied von "
              + (spaces == 1 ? "1 Space" : spaces + " Spaces")
              + ". Bestimmen Sie dort zuerst eine weitere Person oder Gruppe als ADMIN.");
    }
    if (reasons.isEmpty()) {
      return null;
    }
    return "Die Gruppe kann nicht deaktiviert werden. " + String.join(" ", reasons);
  }

  /** The consequences as figures - never the members' names. */
  private void record(
      AuditEventType type, Group group, GroupDeactivationPreview consequences, CurrentUser caller) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("deactivated", group.isDeactivated());
    after.put("assetGrants", consequences.assetGrants());
    after.put("spaceMemberships", consequences.spaceMemberships());
    after.put("capabilities", consequences.capabilities());
    after.put("memberCount", group.getMemberships().size());
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(group.getOrganizationId())
            .actor(caller.id())
            .type(type)
            .object(AuditObjectType.GROUP, group.getId(), group.getName())
            .before(Map.of("deactivated", !group.isDeactivated()))
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private void evictMembersAfterCommit(Group group) {
    List<UUID> memberIds = group.getMemberships().stream().map(GroupMembership::getUserId).toList();
    GroupService.invalidateAfterCommit(() -> membershipResolver.invalidateUsers(memberIds));
  }
}
