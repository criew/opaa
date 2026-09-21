package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DirectorySyncOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The print that decides whether a confirmed plan still describes what was shown (#1816, ADR-0036
 * Entscheidung 3). Two properties matter and neither is visible from a green confirmation: it must
 * not change for a plan that only looks different, and it must change for a plan that <em>is</em>
 * different - otherwise "bestätigt wird gegen einen frischen Schnappschuss" applies a diff nobody
 * saw.
 */
class DirectorySyncPlanFingerprintTest {

  private static final UUID ADA = UUID.randomUUID();
  private static final UUID GRACE = UUID.randomUUID();

  @Test
  void theOrderOfTheChangesDoesNotChangeThePrint() {
    SyncReport first =
        report(
            List.of(
                new MembershipChange(
                    "ext-1", "Referat 1", List.of(ref(ADA), ref(GRACE)), List.of())),
            List.of(
                new GroupChange("ext-a", "A", null, null, 0),
                new GroupChange("ext-b", "B", null, null, 0)));
    SyncReport reordered =
        report(
            List.of(
                new MembershipChange(
                    "ext-1", "Referat 1", List.of(ref(GRACE), ref(ADA)), List.of())),
            List.of(
                new GroupChange("ext-b", "B", null, null, 0),
                new GroupChange("ext-a", "A", null, null, 0)));

    assertThat(printOf(first)).isEqualTo(printOf(reordered));
  }

  /** The moment of the run is no part of the plan - two identical runs are the same plan. */
  @Test
  void theMomentOfTheRunDoesNotChangeThePrint() {
    List<MembershipChange> changes =
        List.of(new MembershipChange("ext-1", "Referat 1", List.of(ref(ADA)), List.of()));

    assertThat(printOf(report(changes, List.of()))).isEqualTo(printOf(report(changes, List.of())));
  }

  @Test
  void anotherUserLosingMembershipChangesThePrint() {
    SyncReport shown =
        report(
            List.of(new MembershipChange("ext-1", "Referat 1", List.of(), List.of(ref(ADA)))),
            List.of());
    SyncReport recomputed =
        report(
            List.of(new MembershipChange("ext-1", "Referat 1", List.of(), List.of(ref(GRACE)))),
            List.of());

    assertThat(printOf(shown)).isNotEqualTo(printOf(recomputed));
  }

  /** Adding and removing the same user are different changes and must not print the same. */
  @Test
  void theDirectionOfAMembershipChangeIsPartOfThePrint() {
    SyncReport added =
        report(
            List.of(new MembershipChange("ext-1", "Referat 1", List.of(ref(ADA)), List.of())),
            List.of());
    SyncReport removed =
        report(
            List.of(new MembershipChange("ext-1", "Referat 1", List.of(), List.of(ref(ADA)))),
            List.of());

    assertThat(printOf(added)).isNotEqualTo(printOf(removed));
  }

  @Test
  void aFurtherDissolvedGroupChangesThePrint() {
    SyncReport shown = report(List.of(), List.of());
    SyncReport recomputed =
        new SyncReport(
            DirectorySyncOutcome.PENDING_CONFIRMATION,
            Instant.now(),
            List.of(),
            List.of(),
            List.of(new GroupChange("ext-gone", "Weg", null, null, 0)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            0,
            0,
            0,
            0.9,
            0.3,
            "");

    assertThat(printOf(shown)).isNotEqualTo(printOf(recomputed));
  }

  /**
   * Reactivation, hierarchy and the reported path are the three facts the print carries beyond the
   * report; the cases above vary the report, the three below vary these.
   */
  @Test
  void aReactivatedGroupChangesThePrint() {
    SyncReport shown = report(List.of(), List.of());

    assertThat(printOf(shown))
        .isNotEqualTo(
            DirectorySyncPlanFingerprint.of(shown, List.of("ext-back"), Map.of(), Map.of()));
  }

  @Test
  void aChangedParentUnitChangesThePrint() {
    SyncReport shown = report(List.of(), List.of());

    assertThat(
            DirectorySyncPlanFingerprint.of(
                shown, List.of(), Map.of("ext-child", "ext-parent-a"), Map.of()))
        .isNotEqualTo(
            DirectorySyncPlanFingerprint.of(
                shown, List.of(), Map.of("ext-child", "ext-parent-b"), Map.of()));
  }

  /**
   * A path moves when a parent is renamed, without the group itself changing - the run writes it,
   * so a confirmation must not apply it unseen (#1817).
   */
  @Test
  void aChangedSourcePathChangesThePrint() {
    SyncReport shown = report(List.of(), List.of());

    assertThat(
            DirectorySyncPlanFingerprint.of(
                shown, List.of(), Map.of(), Map.of("ext-child", "/Haus/Referat 50")))
        .isNotEqualTo(
            DirectorySyncPlanFingerprint.of(
                shown, List.of(), Map.of(), Map.of("ext-child", "/Amt/Referat 50")));
  }

  /** The print of a plan that changes none of the three - what the cases above compare. */
  private static String printOf(SyncReport report) {
    return DirectorySyncPlanFingerprint.of(report, List.of(), Map.of(), Map.of());
  }

  private static UserRef ref(UUID id) {
    return new UserRef(id, "Test User");
  }

  private static SyncReport report(
      List<MembershipChange> membershipChanges, List<GroupChange> created) {
    return new SyncReport(
        DirectorySyncOutcome.PENDING_CONFIRMATION,
        Instant.now(),
        created,
        List.of(),
        List.of(),
        List.of(),
        membershipChanges,
        List.of(),
        List.of(),
        List.of(),
        0,
        0,
        0,
        0.9,
        0.3,
        "Bestätigung erforderlich.");
  }
}
