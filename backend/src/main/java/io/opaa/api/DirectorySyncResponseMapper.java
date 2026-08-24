package io.opaa.api;

import io.opaa.api.dto.DirectorySyncGroupChange;
import io.opaa.api.dto.DirectorySyncMembershipChange;
import io.opaa.api.dto.DirectorySyncReportResponse;
import io.opaa.api.dto.DirectorySyncStatusResponse;
import io.opaa.api.dto.DirectorySyncUserRef;
import io.opaa.group.sync.DirectorySyncStatus;
import io.opaa.group.sync.GroupChange;
import io.opaa.group.sync.MembershipChange;
import io.opaa.group.sync.SyncReport;
import io.opaa.group.sync.UserRef;
import java.util.List;
import java.util.Optional;

/**
 * Maps {@link SyncReport} and {@link DirectorySyncStatus} onto their generated response
 * counterparts (ADR-0006: API DTOs are generated from the specification, never hand-written).
 */
final class DirectorySyncResponseMapper {

  private DirectorySyncResponseMapper() {}

  static DirectorySyncReportResponse toReportResponse(SyncReport report) {
    DirectorySyncReportResponse response =
        new DirectorySyncReportResponse(
            report.outcome(),
            report.generatedAt(),
            toGroupChanges(report.groupsCreated()),
            toGroupChanges(report.groupsRenamed()),
            toGroupChanges(report.groupsDissolved()),
            toMembershipChanges(report.membershipChanges()),
            report.membershipsAdded(),
            report.membershipsRemoved(),
            report.changedFraction(),
            report.thresholdFraction(),
            report.message());
    response.unresolvedMemberCount(report.unresolvedMemberCount());
    return response;
  }

  static DirectorySyncStatusResponse toStatusResponse(Optional<DirectorySyncStatus> status) {
    return status
        .map(
            s ->
                new DirectorySyncStatusResponse()
                    .lastRunAt(s.getLastRunAt())
                    .lastOutcome(s.getLastOutcome())
                    .lastMessage(s.getLastMessage())
                    .lastAppliedAt(s.getLastAppliedAt())
                    .lastChangedFraction(s.getLastChangedFraction()))
        .orElseGet(DirectorySyncStatusResponse::new);
  }

  private static DirectorySyncGroupChange toGroupChange(GroupChange change) {
    return new DirectorySyncGroupChange(change.externalId(), change.name())
        .previousName(change.previousName());
  }

  private static List<DirectorySyncGroupChange> toGroupChanges(List<GroupChange> changes) {
    return changes.stream().map(DirectorySyncResponseMapper::toGroupChange).toList();
  }

  private static DirectorySyncMembershipChange toMembershipChange(MembershipChange change) {
    return new DirectorySyncMembershipChange(
        change.externalId(),
        change.name(),
        toUserRefs(change.added()),
        toUserRefs(change.removed()));
  }

  private static List<DirectorySyncMembershipChange> toMembershipChanges(
      List<MembershipChange> changes) {
    return changes.stream().map(DirectorySyncResponseMapper::toMembershipChange).toList();
  }

  private static DirectorySyncUserRef toUserRef(UserRef userRef) {
    return new DirectorySyncUserRef(userRef.id()).displayName(userRef.displayName());
  }

  private static List<DirectorySyncUserRef> toUserRefs(List<UserRef> userRefs) {
    return userRefs.stream().map(DirectorySyncResponseMapper::toUserRef).toList();
  }
}
