package io.opaa.group;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.permission.GroupStewardshipDirectory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Answers {@link GroupStewardshipDirectory} from this package's repository - the one place the
 * transfer operation learns who is responsible for which internal group, without {@code
 * io.opaa.permission} knowing {@code io.opaa.group} (ADR-0036, Entscheidung 12).
 *
 * <p>Handing responsibility over produces two audit events per group, an appointment and a
 * dismissal, each naming the transfer - and no history rows: responsibility carries no read access
 * (ADR-0036, Entscheidung 8).
 */
@Component
class GroupStewardshipDirectoryAdapter implements GroupStewardshipDirectory {

  private final GroupStewardRepository stewardRepository;
  private final GroupRepository groupRepository;
  private final AuditEventRecorder auditEventRecorder;

  GroupStewardshipDirectoryAdapter(
      GroupStewardRepository stewardRepository,
      GroupRepository groupRepository,
      AuditEventRecorder auditEventRecorder) {
    this.stewardRepository = stewardRepository;
    this.groupRepository = groupRepository;
    this.auditEventRecorder = auditEventRecorder;
  }

  @Override
  public long countStewardedGroups(UUID userId, UUID organizationId) {
    return stewardedGroupIds(userId, organizationId).size();
  }

  @Override
  public List<UUID> stewardedGroupIds(UUID userId, UUID organizationId) {
    return groupRepository.findAllById(stewardRepository.findGroupIdsByUserId(userId)).stream()
        .filter(group -> group.getOrganizationId().equals(organizationId))
        .map(Group::getId)
        .toList();
  }

  @Override
  public List<UUID> transferStewardships(
      UUID sourceUserId,
      UUID targetUserId,
      UUID organizationId,
      UUID actorUserId,
      UUID transferId) {
    List<UUID> touched = new ArrayList<>();
    for (UUID groupId : stewardedGroupIds(sourceUserId, organizationId)) {
      Group group = groupRepository.findById(groupId).orElseThrow();
      if (!stewardRepository.existsByGroupIdAndUserId(groupId, targetUserId)) {
        stewardRepository.save(
            new GroupSteward(groupId, targetUserId, organizationId, actorUserId));
        record(
            AuditEventType.GROUP_STEWARD_APPOINTED, group, targetUserId, actorUserId, transferId);
      }
      stewardRepository
          .findByGroupIdAndUserId(groupId, sourceUserId)
          .ifPresent(stewardRepository::delete);
      record(AuditEventType.GROUP_STEWARD_DISMISSED, group, sourceUserId, actorUserId, transferId);
      touched.add(groupId);
    }
    return touched;
  }

  private void record(
      AuditEventType type, Group group, UUID subjectUserId, UUID actorUserId, UUID transferId) {
    auditEventRecorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(group.getOrganizationId())
            .actor(actorUserId)
            .type(type)
            .object(AuditObjectType.GROUP, group.getId(), group.getName())
            .subject(AuditSubjectKind.USER, subjectUserId)
            .after(Map.of("transferId", transferId.toString()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
