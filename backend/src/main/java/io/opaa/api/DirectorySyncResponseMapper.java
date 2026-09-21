package io.opaa.api;

import io.opaa.api.dto.DirectorySyncGroupChange;
import io.opaa.api.dto.DirectorySyncMembershipChange;
import io.opaa.api.dto.DirectorySyncPendingPlanResponse;
import io.opaa.api.dto.DirectorySyncPendingPlanSummary;
import io.opaa.api.dto.DirectorySyncReportResponse;
import io.opaa.api.dto.DirectorySyncStatusResponse;
import io.opaa.api.dto.DirectorySyncUserRef;
import io.opaa.group.sync.DirectorySyncPendingPlan;
import io.opaa.group.sync.DirectorySyncStatus;
import io.opaa.group.sync.DirectorySyncStatusView;
import io.opaa.group.sync.GroupChange;
import io.opaa.group.sync.MembershipChange;
import io.opaa.group.sync.PendingPlanView;
import io.opaa.group.sync.SyncReport;
import io.opaa.group.sync.UserRef;
import java.util.List;

/**
 * Maps {@link SyncReport}, {@link DirectorySyncStatusView} and {@link PendingPlanView} onto their
 * generated response counterparts (ADR-0006: API DTOs are generated from the specification, never
 * hand-written).
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
            toGroupChanges(report.unmaintainedTokenGroups()),
            toMembershipChanges(report.membershipChanges()),
            toUserRefs(report.accountsLocked()),
            toUserRefs(report.accountsUnlocked()),
            toUserRefs(report.accountLocksWithheld()),
            report.membershipsAdded(),
            report.membershipsRemoved(),
            report.changedFraction(),
            report.thresholdFraction(),
            report.message());
    response.unresolvedMemberCount(report.unresolvedMemberCount());
    return response;
  }

  static List<DirectorySyncStatusResponse> toStatusResponses(List<DirectorySyncStatusView> views) {
    return views.stream().map(DirectorySyncResponseMapper::toStatusResponse).toList();
  }

  static DirectorySyncStatusResponse toStatusResponse(DirectorySyncStatusView view) {
    DirectorySyncStatusResponse response =
        new DirectorySyncStatusResponse(
                view.providerId(),
                view.providerDisplayName(),
                view.providerEnabled(),
                view.enabled())
            .intervalMinutes(view.intervalMinutes())
            .pendingPlan(toPendingPlanSummary(view.pendingPlan()));
    DirectorySyncStatus status = view.status();
    if (status != null) {
      response
          .lastRunAt(status.getLastRunAt())
          .lastOutcome(status.getLastOutcome())
          .lastMessage(status.getLastMessage())
          .lastAppliedAt(status.getLastAppliedAt())
          .lastChangedFraction(status.getLastChangedFraction());
    }
    return response;
  }

  static DirectorySyncPendingPlanResponse toPendingPlanResponse(PendingPlanView view) {
    return new DirectorySyncPendingPlanResponse(
        view.id(), view.providerId(), view.createdAt(), toReportResponse(view.report()));
  }

  private static DirectorySyncPendingPlanSummary toPendingPlanSummary(
      DirectorySyncPendingPlan plan) {
    if (plan == null) {
      return null;
    }
    return new DirectorySyncPendingPlanSummary(
            plan.getId(),
            plan.getCreatedAt(),
            plan.getChangedFraction(),
            plan.getMembershipsRemoved())
        .accountsLocked(plan.getAccountsLocked());
  }

  private static DirectorySyncGroupChange toGroupChange(GroupChange change) {
    return new DirectorySyncGroupChange(change.externalId(), change.name(), change.memberCount())
        .previousName(change.previousName())
        .sourcePath(change.sourcePath());
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
