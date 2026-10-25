package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The period after a lock by the administration lies between 90 days and ten years; the factory
 * default is the former default of the configuration.
 */
class LockedAccountChatCleanupPolicyTest {

  @Test
  void theFactoryDefaultIsOnWith365Days() {
    assertThat(LockedAccountChatCleanupPolicy.FACTORY_DEFAULTS.enabled()).isTrue();
    assertThat(LockedAccountChatCleanupPolicy.FACTORY_DEFAULTS.period())
        .isEqualTo(Duration.ofDays(365));
  }

  @Test
  void theBoundsThemselvesAreAccepted() {
    assertThat(
            new LockedAccountChatCleanupPolicy(
                    true, LockedAccountChatCleanupPolicy.MIN_DELETE_AFTER_DAYS)
                .period())
        .isEqualTo(Duration.ofDays(90));
    assertThat(new LockedAccountChatCleanupPolicy(false, 3650).deleteAfterDays()).isEqualTo(3650);
  }

  @Test
  void aValueOutsideTheBoundsIsRejected() {
    assertThatThrownBy(() -> new LockedAccountChatCleanupPolicy(false, 89))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("89");
    assertThatThrownBy(() -> new LockedAccountChatCleanupPolicy(true, 3651))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
