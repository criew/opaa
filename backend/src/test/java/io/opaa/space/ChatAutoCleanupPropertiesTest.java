package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The floors of the automatic chat cleanup's periods (#1923) and the deletion date it shows. */
class ChatAutoCleanupPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Binding.class);

  @Test
  void theDefaultsAreNinetyAndThreeHundredSixtyFiveDays() {
    runner.run(
        context -> {
          ChatAutoCleanupProperties properties = context.getBean(ChatAutoCleanupProperties.class);
          assertThat(properties.archiveAfterDays()).isEqualTo(90);
          assertThat(properties.deleteAfterDays()).isEqualTo(365);
        });
  }

  @Test
  void theFloorsThemselvesAreAccepted() {
    runner
        .withPropertyValues(
            "opaa.chat.auto-cleanup.archive-after-days=90",
            "opaa.chat.auto-cleanup.delete-after-days=30")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void anArchivePeriodBelowNinetyDaysStopsTheStart() {
    runner
        .withPropertyValues("opaa.chat.auto-cleanup.archive-after-days=89")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("archive-after-days must be at least 90"));
  }

  @Test
  void aDeletePeriodBelowThirtyDaysStopsTheStart() {
    runner
        .withPropertyValues("opaa.chat.auto-cleanup.delete-after-days=29")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("delete-after-days must be at least 30"));
  }

  @Test
  void theDeletePeriodNeverStartsBeforeSwitchingOn() {
    ChatAutoCleanupProperties properties = new ChatAutoCleanupProperties(90, 365);
    Instant enabledAt = Instant.parse("2026-10-01T00:00:00Z");

    assertThat(properties.deletionDueAt(Instant.parse("2024-01-01T00:00:00Z"), enabledAt))
        .isEqualTo(enabledAt.plus(Duration.ofDays(365)));
    assertThat(properties.deletionDueAt(Instant.parse("2026-12-01T00:00:00Z"), enabledAt))
        .isEqualTo(Instant.parse("2026-12-01T00:00:00Z").plus(Duration.ofDays(365)));
    assertThat(properties.deletionDueAt(null, enabledAt)).isNull();
    assertThat(properties.deletionDueAt(enabledAt, null)).isNull();
  }

  @Test
  void aValueBelowTheFloorIsRejectedOnConstruction() {
    assertThatThrownBy(() -> new ChatAutoCleanupProperties(30, 365))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Configuration
  @EnableConfigurationProperties(ChatAutoCleanupProperties.class)
  static class Binding {}
}
