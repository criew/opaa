package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.DirectorySyncGroupChange;
import io.opaa.api.dto.DirectorySyncMembershipChange;
import io.opaa.api.dto.DirectorySyncPendingPlanResponse;
import io.opaa.api.dto.DirectorySyncReportResponse;
import io.opaa.api.dto.DirectorySyncStatusResponse;
import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.group.sync.DirectorySyncPendingPlan;
import io.opaa.group.sync.DirectorySyncStatus;
import io.opaa.group.sync.DirectorySyncStatusView;
import io.opaa.group.sync.GroupChange;
import io.opaa.group.sync.MembershipChange;
import io.opaa.group.sync.PendingPlanView;
import io.opaa.group.sync.SyncReport;
import io.opaa.group.sync.UserRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit tests (no Spring context) against directly constructed domain records - pins the
 * mapper's field-by-field behaviour, since {@code DirectorySyncServiceIntegrationTest} asserts
 * against {@link SyncReport}/{@link DirectorySyncStatus} directly rather than against the response
 * shape.
 */
class DirectorySyncResponseMapperTest {

  private static final UUID PROVIDER_ID = UUID.randomUUID();

  @Test
  void toReportResponseCopiesEveryFieldAndTheUnresolvedMemberCount() {
    Instant now = Instant.now();
    SyncReport report =
        new SyncReport(
            DirectorySyncOutcome.APPLIED,
            now,
            List.of(new GroupChange("ext-created", "Neu", null, "/Haus/Neu", 7)),
            List.of(new GroupChange("ext-renamed", "Umbenannt", "Alt", null, 0)),
            List.of(new GroupChange("ext-dissolved", "Aufgelöst", null, null, 0)),
            List.of(new GroupChange("ext-token", "Token-Gruppe", null, null, 0)),
            List.of(
                new MembershipChange(
                    "ext-1",
                    "Referat 1",
                    List.of(new UserRef(UUID.randomUUID(), "Ada Lovelace")),
                    List.of())),
            List.of(),
            List.of(),
            List.of(),
            3,
            1,
            2,
            0.5,
            0.3,
            "Synchronisation angewendet.");

    DirectorySyncReportResponse response = DirectorySyncResponseMapper.toReportResponse(report);

    assertThat(response.getOutcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(response.getGeneratedAt()).isEqualTo(now);
    assertThat(response.getGroupsCreated()).hasSize(1);
    assertThat(response.getGroupsCreated().get(0).getExternalId()).isEqualTo("ext-created");
    assertThat(response.getGroupsCreated().get(0).getPreviousName()).isNull();
    assertThat(response.getGroupsCreated().get(0).getSourcePath()).isEqualTo("/Haus/Neu");
    assertThat(response.getGroupsCreated().get(0).getMemberCount()).isEqualTo(7);
    assertThat(response.getGroupsRenamed().get(0).getPreviousName()).isEqualTo("Alt");
    assertThat(response.getGroupsDissolved()).hasSize(1);
    assertThat(response.getUnmaintainedTokenGroups()).hasSize(1);
    assertThat(response.getUnmaintainedTokenGroups().get(0).getName()).isEqualTo("Token-Gruppe");
    assertThat(response.getMembershipsAdded()).isEqualTo(3);
    assertThat(response.getMembershipsRemoved()).isEqualTo(1);
    assertThat(response.getUnresolvedMemberCount()).isEqualTo(2);
    assertThat(response.getChangedFraction()).isEqualTo(0.5);
    assertThat(response.getThresholdFraction()).isEqualTo(0.3);
    assertThat(response.getMessage()).isEqualTo("Synchronisation angewendet.");

    DirectorySyncMembershipChange membershipChange = response.getMembershipChanges().get(0);
    assertThat(membershipChange.getExternalId()).isEqualTo("ext-1");
    assertThat(membershipChange.getAdded()).hasSize(1);
    assertThat(membershipChange.getAdded().get(0).getDisplayName()).isEqualTo("Ada Lovelace");
    assertThat(membershipChange.getRemoved()).isEmpty();
  }

  @Test
  void toReportResponseReturnsEmptyListsInsteadOfNullWhenNothingChanged() {
    DirectorySyncReportResponse response =
        DirectorySyncResponseMapper.toReportResponse(
            emptyReport(DirectorySyncOutcome.DRY_RUN, "Trockenlauf - keine Änderung."));

    assertThat(response.getGroupsCreated()).isEmpty();
    assertThat(response.getGroupsRenamed()).isEmpty();
    assertThat(response.getGroupsDissolved()).isEmpty();
    assertThat(response.getUnmaintainedTokenGroups()).isEmpty();
    assertThat(response.getMembershipChanges()).isEmpty();
  }

  /**
   * #1817: a department that only holds subgroups arrives as a zero here - the number an operator
   * decides on before applying, not one the mapper may drop.
   */
  @Test
  void toGroupChangeCarriesAMemberCountOfZero() {
    DirectorySyncGroupChange change =
        DirectorySyncResponseMapper.toReportResponse(
                reportWithOneChange(new GroupChange("ext-1", "Abteilung", null, "/Abteilung", 0)))
            .getGroupsCreated()
            .get(0);

    assertThat(change.getMemberCount()).isZero();
    assertThat(change.getSourcePath()).isEqualTo("/Abteilung");
  }

  @Test
  void toGroupChangePreservesTheDistinctionBetweenNoPreviousNameAndOne() {
    DirectorySyncGroupChange withoutPreviousName =
        DirectorySyncResponseMapper.toReportResponse(
                reportWithOneChange(new GroupChange("ext-1", "Name", null, null, 0)))
            .getGroupsCreated()
            .get(0);
    assertThat(withoutPreviousName.getPreviousName()).isNull();
  }

  @Test
  void toStatusResponseLeavesTheRunFieldsNullForAProviderThatNeverRan() {
    DirectorySyncStatusResponse response =
        DirectorySyncResponseMapper.toStatusResponse(
            new DirectorySyncStatusView(PROVIDER_ID, "Haus A", true, true, 360, null, null));

    assertThat(response.getProviderId()).isEqualTo(PROVIDER_ID);
    assertThat(response.getProviderDisplayName()).isEqualTo("Haus A");
    assertThat(response.getProviderEnabled()).isTrue();
    assertThat(response.getEnabled()).isTrue();
    assertThat(response.getIntervalMinutes()).isEqualTo(360);
    assertThat(response.getLastRunAt()).isNull();
    assertThat(response.getLastOutcome()).isNull();
    assertThat(response.getLastMessage()).isNull();
    assertThat(response.getLastAppliedAt()).isNull();
    assertThat(response.getLastChangedFraction()).isNull();
    assertThat(response.getPendingPlan()).isNull();
  }

  @Test
  void toStatusResponseCopiesEveryFieldFromTheEntityAndThePendingPlan() {
    DirectorySyncStatus status = new DirectorySyncStatus(UUID.randomUUID(), PROVIDER_ID);
    Instant runAt = Instant.now();
    status.recordRun(runAt, DirectorySyncOutcome.APPLIED, "Synchronisation angewendet.", 0.1);
    Instant planCreatedAt = runAt.minusSeconds(600);
    DirectorySyncPendingPlan plan =
        new DirectorySyncPendingPlan(
            UUID.randomUUID(), PROVIDER_ID, planCreatedAt, "fingerprint", 0.67, 12, 3, "{}");

    DirectorySyncStatusResponse response =
        DirectorySyncResponseMapper.toStatusResponse(
            new DirectorySyncStatusView(PROVIDER_ID, "Haus A", true, true, 360, status, plan));

    assertThat(response.getLastRunAt()).isEqualTo(runAt);
    assertThat(response.getLastOutcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(response.getLastMessage()).isEqualTo("Synchronisation angewendet.");
    assertThat(response.getLastAppliedAt()).isEqualTo(runAt);
    assertThat(response.getLastChangedFraction()).isEqualTo(0.1);
    // The age of a pending plan belongs in the status line itself (ADR-0036, Entscheidung 3).
    assertThat(response.getPendingPlan().getId()).isEqualTo(plan.getId());
    assertThat(response.getPendingPlan().getCreatedAt()).isEqualTo(planCreatedAt);
    assertThat(response.getPendingPlan().getChangedFraction()).isEqualTo(0.67);
    assertThat(response.getPendingPlan().getMembershipsRemoved()).isEqualTo(12);
    assertThat(response.getPendingPlan().getAccountsLocked()).isEqualTo(3);
  }

  @Test
  void toStatusResponseLeavesLastAppliedAtNullWhenTheLastRunDidNotApplyAnything() {
    DirectorySyncStatus status = new DirectorySyncStatus(UUID.randomUUID(), PROVIDER_ID);
    status.recordRun(Instant.now(), DirectorySyncOutcome.DRY_RUN, "Trockenlauf.", 0.0);

    DirectorySyncStatusResponse response =
        DirectorySyncResponseMapper.toStatusResponse(
            new DirectorySyncStatusView(PROVIDER_ID, "Haus A", true, false, null, status, null));

    assertThat(response.getLastOutcome()).isEqualTo(DirectorySyncOutcome.DRY_RUN);
    assertThat(response.getLastAppliedAt()).isNull();
    assertThat(response.getEnabled()).isFalse();
    assertThat(response.getIntervalMinutes()).isNull();
  }

  @Test
  void toPendingPlanResponseCarriesTheStoredReportUnchanged() {
    UUID planId = UUID.randomUUID();
    Instant createdAt = Instant.now();
    SyncReport report =
        emptyReport(DirectorySyncOutcome.PENDING_CONFIRMATION, "Bestätigung erforderlich.");

    DirectorySyncPendingPlanResponse response =
        DirectorySyncResponseMapper.toPendingPlanResponse(
            new PendingPlanView(planId, PROVIDER_ID, createdAt, 0.67, 12, 3, report));

    assertThat(response.getId()).isEqualTo(planId);
    assertThat(response.getProviderId()).isEqualTo(PROVIDER_ID);
    assertThat(response.getCreatedAt()).isEqualTo(createdAt);
    assertThat(response.getReport().getOutcome())
        .isEqualTo(DirectorySyncOutcome.PENDING_CONFIRMATION);
    assertThat(response.getReport().getMessage()).isEqualTo("Bestätigung erforderlich.");
  }

  private SyncReport reportWithOneChange(GroupChange change) {
    return new SyncReport(
        DirectorySyncOutcome.DRY_RUN,
        Instant.now(),
        List.of(change),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        0,
        0,
        0,
        0.0,
        0.3,
        "message");
  }

  private SyncReport emptyReport(DirectorySyncOutcome outcome, String message) {
    return new SyncReport(
        outcome,
        Instant.now(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        0,
        0,
        0,
        0.0,
        0.3,
        message);
  }
}
