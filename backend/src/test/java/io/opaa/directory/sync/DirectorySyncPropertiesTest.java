package io.opaa.directory.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * A safeguard against mass rights revocation must not silently become more lenient on invalid
 * configuration: {@code changeThresholdFraction <= 0} is rejected, not replaced by the 0.3 default
 * - an operator who deliberately sets 0 ("abort on any revocation at all") must not silently get
 * 30%.
 */
class DirectorySyncPropertiesTest {

  @Test
  void rejectsAZeroThreshold() {
    assertThatThrownBy(() -> new DirectorySyncProperties(0.0, true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsANegativeThreshold() {
    assertThatThrownBy(() -> new DirectorySyncProperties(-0.3, true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAThresholdAboveOne() {
    assertThatThrownBy(() -> new DirectorySyncProperties(1.5, true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsAValidThreshold() {
    DirectorySyncProperties properties = new DirectorySyncProperties(0.3, true);
    assertThat(properties.changeThresholdFraction()).isEqualTo(0.3);
  }
}
