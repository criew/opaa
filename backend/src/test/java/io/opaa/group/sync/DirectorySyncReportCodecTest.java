package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DirectorySyncOutcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The pending plan shows the report the run presented, read back from {@code
 * directory_sync_pending_plans.report} (#1816). Round-trip fidelity is the whole contract of the
 * codec, and a field lost in it would show up as a plan that silently understates what it does.
 */
class DirectorySyncReportCodecTest {

  @Test
  void everyFieldOfAReportSurvivesTheRoundTrip() {
    UUID added = UUID.randomUUID();
    UUID removed = UUID.randomUUID();
    Instant generatedAt = Instant.parse("2026-09-20T04:00:00Z");
    SyncReport report =
        new SyncReport(
            DirectorySyncOutcome.PENDING_CONFIRMATION,
            generatedAt,
            List.of(new GroupChange("ext-created", "Neu", null)),
            List.of(new GroupChange("ext-renamed", "Umbenannt", "Alt")),
            List.of(new GroupChange("ext-dissolved", "Aufgelöst", null)),
            List.of(new GroupChange("ext-token", "Token-Gruppe", null)),
            List.of(
                new MembershipChange(
                    "ext-1",
                    "Referat 1",
                    List.of(new UserRef(added, "Ada Lovelace")),
                    List.of(new UserRef(removed, "Grace Hopper")))),
            3,
            1,
            2,
            0.67,
            0.3,
            "Bestätigung erforderlich.");

    SyncReport restored = DirectorySyncReportCodec.read(DirectorySyncReportCodec.write(report));

    assertThat(restored).isEqualTo(report);
    assertThat(restored.generatedAt()).isEqualTo(generatedAt);
    assertThat(restored.membershipChanges().get(0).removed().get(0).displayName())
        .isEqualTo("Grace Hopper");
  }

  @Test
  void anEmptyReportSurvivesTooWithoutTurningItsListsIntoNull() {
    SyncReport report =
        new SyncReport(
            DirectorySyncOutcome.DRY_RUN,
            Instant.now(),
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
            "Trockenlauf - keine Änderung.");

    SyncReport restored = DirectorySyncReportCodec.read(DirectorySyncReportCodec.write(report));

    assertThat(restored.groupsCreated()).isEmpty();
    assertThat(restored.unmaintainedTokenGroups()).isEmpty();
    assertThat(restored.membershipChanges()).isEmpty();
  }
}
