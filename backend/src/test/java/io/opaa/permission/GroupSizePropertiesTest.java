package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * ADR-0036 sets the Mindestgruppengröße to 5 and makes that value at once the enforced lower bound
 * and the delivered default: a house may raise it, nobody may lower it, and a start with a smaller
 * value fails with a message that says so.
 */
class GroupSizePropertiesTest {

  @Test
  void theDeliveredValueIsTheEnforcedMinimum() {
    assertThat(new GroupSizeProperties(null).minimumGroupSize())
        .isEqualTo(GroupSizeProperties.ENFORCED_MINIMUM)
        .isEqualTo(5);
  }

  @Test
  void theValueIsRaisable() {
    assertThat(new GroupSizeProperties(12).minimumGroupSize()).isEqualTo(12);
  }

  @Test
  void aSmallerValueFailsTheStartWithAClearMessage() {
    assertThatThrownBy(() -> new GroupSizeProperties(1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("opaa.permission.minimum-group-size")
        .hasMessageContaining("5");
    assertThatThrownBy(() -> new GroupSizeProperties(4))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
