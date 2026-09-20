package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DirectorySyncOutcome;
import java.time.Instant;
import java.util.List;
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
            List.of(new GroupChange("ext-a", "A", null), new GroupChange("ext-b", "B", null)));
    SyncReport reordered =
        report(
            List.of(
                new MembershipChange(
                    "ext-1", "Referat 1", List.of(ref(GRACE), ref(ADA)), List.of())),
            List.of(new GroupChange("ext-b", "B", null), new GroupChange("ext-a", "A", null)));

    assertThat(DirectorySyncPlanFingerprint.of(first))
        .isEqualTo(DirectorySyncPlanFingerprint.of(reordered));
  }

  /** The moment of the run is no part of the plan - two identical runs are the same plan. */
  @Test
  void theMomentOfTheRunDoesNotChangeThePrint() {
    List<MembershipChange> changes =
        List.of(new MembershipChange("ext-1", "Referat 1", List.of(ref(ADA)), List.of()));

    assertThat(DirectorySyncPlanFingerprint.of(report(changes, List.of())))
        .isEqualTo(DirectorySyncPlanFingerprint.of(report(changes, List.of())));
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

    assertThat(DirectorySyncPlanFingerprint.of(shown))
        .isNotEqualTo(DirectorySyncPlanFingerprint.of(recomputed));
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

    assertThat(DirectorySyncPlanFingerprint.of(added))
        .isNotEqualTo(DirectorySyncPlanFingerprint.of(removed));
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
            List.of(new GroupChange("ext-gone", "Weg", null)),
            List.of(),
            List.of(),
            0,
            0,
            0,
            0.9,
            0.3,
            "");

    assertThat(DirectorySyncPlanFingerprint.of(shown))
        .isNotEqualTo(DirectorySyncPlanFingerprint.of(recomputed));
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
        0,
        0,
        0,
        0.9,
        0.3,
        "Bestätigung erforderlich.");
  }
}
