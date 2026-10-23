package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The period after a lock by the administration has a floor of 90 days no configuration undercuts:
 * a smaller value stops the start instead of being raised silently.
 */
class LockedAccountChatCleanupPropertiesTest {

  @Configuration
  @EnableConfigurationProperties(LockedAccountChatCleanupProperties.class)
  static class Binding {}

  private final ApplicationContextRunner context =
      new ApplicationContextRunner().withUserConfiguration(Binding.class);

  @Test
  void withoutAValueTheDeletionIsOnWith365Days() {
    context.run(
        started -> {
          LockedAccountChatCleanupProperties properties =
              started.getBean(LockedAccountChatCleanupProperties.class);
          assertThat(properties.enabled()).isTrue();
          assertThat(properties.period()).isEqualTo(Duration.ofDays(365));
        });
  }

  @Test
  void theFloorItselfIsAccepted() {
    assertThat(
            new LockedAccountChatCleanupProperties(
                    true, LockedAccountChatCleanupProperties.MIN_DELETE_AFTER_DAYS)
                .period())
        .isEqualTo(Duration.ofDays(90));
  }

  @Test
  void aValueBelowTheFloorStopsTheStart() {
    context
        .withPropertyValues("opaa.chat.locked-account-cleanup.delete-after-days=89")
        .run(
            started ->
                assertThat(started)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("opaa.chat.locked-account-cleanup.delete-after-days")
                    .hasMessageContaining("89"));
    assertThatThrownBy(() -> new LockedAccountChatCleanupProperties(false, 30))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
