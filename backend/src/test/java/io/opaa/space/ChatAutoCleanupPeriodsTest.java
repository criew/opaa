package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The bounds of the automatic chat cleanup's periods and the deletion date it shows. */
class ChatAutoCleanupPeriodsTest {

  @Test
  void theFactoryDefaultsAreTheFormerDefaultsOfNinetyAndThreeHundredSixtyFiveDays() {
    assertThat(ChatAutoCleanupPeriods.FACTORY_DEFAULTS.archiveAfterDays()).isEqualTo(90);
    assertThat(ChatAutoCleanupPeriods.FACTORY_DEFAULTS.deleteAfterDays()).isEqualTo(365);
  }

  @Test
  void theBoundsThemselvesAreAccepted() {
    assertThat(new ChatAutoCleanupPeriods(90, 30).deleteAfterDays()).isEqualTo(30);
    assertThat(new ChatAutoCleanupPeriods(3650, 3650).archiveAfterDays()).isEqualTo(3650);
  }

  @Test
  void aValueOutsideTheBoundsIsRejectedOnConstruction() {
    assertThatThrownBy(() -> new ChatAutoCleanupPeriods(89, 365))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("archiveAfterDays");
    assertThatThrownBy(() -> new ChatAutoCleanupPeriods(90, 29))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deleteAfterDays");
    assertThatThrownBy(() -> new ChatAutoCleanupPeriods(3651, 365))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theDeletePeriodNeverStartsBeforeSwitchingOn() {
    ChatAutoCleanupPeriods periods = new ChatAutoCleanupPeriods(90, 365);
    Instant enabledAt = Instant.parse("2026-10-01T00:00:00Z");

    assertThat(periods.deletionDueAt(Instant.parse("2024-01-01T00:00:00Z"), enabledAt))
        .isEqualTo(enabledAt.plus(Duration.ofDays(365)));
    assertThat(periods.deletionDueAt(Instant.parse("2026-12-01T00:00:00Z"), enabledAt))
        .isEqualTo(Instant.parse("2026-12-01T00:00:00Z").plus(Duration.ofDays(365)));
    assertThat(periods.deletionDueAt(null, enabledAt)).isNull();
    assertThat(periods.deletionDueAt(enabledAt, null)).isNull();
  }
}
