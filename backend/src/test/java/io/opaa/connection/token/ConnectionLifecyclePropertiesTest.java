package io.opaa.connection.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The inactivity threshold is a start setting whose bounds no configuration can stretch. */
class ConnectionLifecyclePropertiesTest {

  @Test
  void withoutAValueTheDeliveredNinetyDaysApply() {
    assertThat(ConnectionLifecycleProperties.defaults().inactivityThreshold())
        .isEqualTo(Duration.ofDays(90));
  }

  @Test
  void theBoundsThemselvesAreAccepted() {
    assertThat(new ConnectionLifecycleProperties(30).inactivityThreshold())
        .isEqualTo(Duration.ofDays(30));
    assertThat(new ConnectionLifecycleProperties(365).inactivityThreshold())
        .isEqualTo(Duration.ofDays(365));
  }

  @Test
  void aValueOutsideTheBoundsFailsTheStart() {
    assertThatThrownBy(() -> new ConnectionLifecycleProperties(29))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("opaa.connection.inactivity-threshold-days")
        .hasMessageContaining("29");
    assertThatThrownBy(() -> new ConnectionLifecycleProperties(366))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("366");
  }
}
