package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * A safeguard against mass rights revocation must not silently become more lenient on invalid
 * configuration. Review of PR #297 flagged that {@code changeThresholdFraction <= 0} used to fall
 * back to the 0.3 default instead of rejecting the value - an operator who deliberately sets 0
 * ("abort on any revocation at all") silently got 30% instead.
 */
class DirectorySyncPropertiesTest {

  @Test
  void rejectsAZeroThreshold() {
    assertThatThrownBy(() -> new DirectorySyncProperties(0.0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsANegativeThreshold() {
    assertThatThrownBy(() -> new DirectorySyncProperties(-0.3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAThresholdAboveOne() {
    assertThatThrownBy(() -> new DirectorySyncProperties(1.5))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsAValidThreshold() {
    DirectorySyncProperties properties = new DirectorySyncProperties(0.3);
    assertThat(properties.changeThresholdFraction()).isEqualTo(0.3);
  }
}
