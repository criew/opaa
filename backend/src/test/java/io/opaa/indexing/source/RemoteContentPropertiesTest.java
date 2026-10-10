package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RemoteContentPropertiesTest {

  @Test
  void unsetValuesTakeTheirDefaults() {
    RemoteContentProperties properties = new RemoteContentProperties(0, 0, 0);

    assertThat(properties.maxBytes()).isEqualTo(20L * 1024 * 1024);
    assertThat(properties.timeoutSeconds()).isEqualTo(20);
    assertThat(properties.transferTimeoutSeconds()).isEqualTo(120);
  }

  /** An existing longer hop timeout never fails the start once the new value is left unset. */
  @Test
  void anUnsetTransferTimeoutIsNeverShorterThanTheHopTimeout() {
    assertThat(new RemoteContentProperties(0, 180, 0).transferTimeoutSeconds()).isEqualTo(180);
  }

  @Test
  void theTransferMayTakeLongerThanAHop() {
    assertThat(new RemoteContentProperties(0, 30, 300).transferTimeoutSeconds()).isEqualTo(300);
  }

  /**
   * A whole transfer shorter than the wait for its answer is a misconfiguration: the start fails.
   */
  @Test
  void aTransferTimeoutShorterThanTheHopTimeoutIsRejected() {
    assertThatThrownBy(() -> new RemoteContentProperties(0, 60, 30))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("transfer-timeout-seconds");
  }
}
