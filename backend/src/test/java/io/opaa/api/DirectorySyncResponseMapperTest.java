package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.DirectorySyncGroupChange;
import io.opaa.api.dto.DirectorySyncMembershipChange;
import io.opaa.api.dto.DirectorySyncReportResponse;
import io.opaa.api.dto.DirectorySyncStatusResponse;
import io.opaa.group.sync.DirectorySyncOutcome;
import io.opaa.group.sync.DirectorySyncStatus;
import io.opaa.group.sync.GroupChange;
import io.opaa.group.sync.MembershipChange;
import io.opaa.group.sync.SyncReport;
import io.opaa.group.sync.UserRef;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit tests (no Spring context) against directly constructed domain records - pins the
 * mapper's field-by-field behaviour, since {@code DirectorySyncServiceIntegrationTest} now asserts
 * against {@link SyncReport}/{@link DirectorySyncStatus} directly rather than against the response
 * shape.
 */
class DirectorySyncResponseMapperTest {

  @Test
  void toReportResponseCopiesEveryFieldAndTheUnresolvedMemberCount() {
    Instant now = Instant.now();
    SyncReport report =
        new SyncReport(
            DirectorySyncOutcome.APPLIED,
            now,
            List.of(new GroupChange("ext-created", "Neu", null)),
            List.of(new GroupChange("ext-renamed", "Umbenannt", "Alt")),
            List.of(new GroupChange("ext-dissolved", "Aufgelöst", null)),
            List.of(
                new MembershipChange(
                    "ext-1",
                    "Referat 1",
                    List.of(new UserRef(UUID.randomUUID(), "Ada Lovelace")),
                    List.of())),
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
    assertThat(response.getGroupsRenamed().get(0).getPreviousName()).isEqualTo("Alt");
    assertThat(response.getGroupsDissolved()).hasSize(1);
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
    SyncReport report =
        new SyncReport(
            DirectorySyncOutcome.DRY_RUN,
            Instant.now(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            0,
            0,
            0,
            0.0,
            0.3,
            "Trockenlauf - keine Änderung.");

    DirectorySyncReportResponse response = DirectorySyncResponseMapper.toReportResponse(report);

    assertThat(response.getGroupsCreated()).isEmpty();
    assertThat(response.getGroupsRenamed()).isEmpty();
    assertThat(response.getGroupsDissolved()).isEmpty();
    assertThat(response.getMembershipChanges()).isEmpty();
  }

  @Test
  void toGroupChangePreservesTheDistinctionBetweenNoPreviousNameAndOne() {
    DirectorySyncGroupChange withoutPreviousName =
        DirectorySyncResponseMapper.toReportResponse(
                reportWithOneChange(new GroupChange("ext-1", "Name", null)))
            .getGroupsCreated()
            .get(0);
    assertThat(withoutPreviousName.getPreviousName()).isNull();
  }

  private SyncReport reportWithOneChange(GroupChange change) {
    return new SyncReport(
        DirectorySyncOutcome.DRY_RUN,
        Instant.now(),
        List.of(change),
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

  @Test
  void toStatusResponseReturnsAllNullFieldsForAnOrganizationThatNeverRan() {
    DirectorySyncStatusResponse response =
        DirectorySyncResponseMapper.toStatusResponse(Optional.empty());

    assertThat(response.getLastRunAt()).isNull();
    assertThat(response.getLastOutcome()).isNull();
    assertThat(response.getLastMessage()).isNull();
    assertThat(response.getLastAppliedAt()).isNull();
    assertThat(response.getLastChangedFraction()).isNull();
  }

  @Test
  void toStatusResponseCopiesEveryFieldFromTheEntity() {
    DirectorySyncStatus status = new DirectorySyncStatus(UUID.randomUUID());
    Instant runAt = Instant.now();
    status.recordRun(runAt, DirectorySyncOutcome.APPLIED, "Synchronisation angewendet.", 0.1);

    DirectorySyncStatusResponse response =
        DirectorySyncResponseMapper.toStatusResponse(Optional.of(status));

    assertThat(response.getLastRunAt()).isEqualTo(runAt);
    assertThat(response.getLastOutcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(response.getLastMessage()).isEqualTo("Synchronisation angewendet.");
    assertThat(response.getLastAppliedAt()).isEqualTo(runAt);
    assertThat(response.getLastChangedFraction()).isEqualTo(0.1);
  }

  @Test
  void toStatusResponseLeavesLastAppliedAtNullWhenTheLastRunDidNotApplyAnything() {
    DirectorySyncStatus status = new DirectorySyncStatus(UUID.randomUUID());
    status.recordRun(Instant.now(), DirectorySyncOutcome.DRY_RUN, "Trockenlauf.", 0.0);

    DirectorySyncStatusResponse response =
        DirectorySyncResponseMapper.toStatusResponse(Optional.of(status));

    assertThat(response.getLastOutcome()).isEqualTo(DirectorySyncOutcome.DRY_RUN);
    assertThat(response.getLastAppliedAt()).isNull();
  }
}
