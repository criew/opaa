package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link InstanceVersionCipherFormatGate}: the switch and the cached register. */
class InstanceVersionCipherFormatGateTest {

  private final AtomicLong now = new AtomicLong();
  private final AtomicInteger queries = new AtomicInteger();
  private final AtomicBoolean allOnThisVersion = new AtomicBoolean(true);

  private InstanceVersionCipherFormatGate gate(boolean keyed) {
    return new InstanceVersionCipherFormatGate(
        keyed,
        () -> {
          queries.incrementAndGet();
          return allOnThisVersion.get();
        },
        Duration.ofSeconds(15),
        now::get);
  }

  @Test
  void theRegisterIsAskedOncePerHeartbeatInterval() {
    InstanceVersionCipherFormatGate gate = gate(true);

    assertThat(gate.writesKeyedFormat()).isTrue();
    allOnThisVersion.set(false);
    now.addAndGet(Duration.ofSeconds(14).toNanos());
    assertThat(gate.writesKeyedFormat()).as("still cached").isTrue();
    now.addAndGet(Duration.ofSeconds(1).toNanos());
    assertThat(gate.writesKeyedFormat()).as("asked again").isFalse();
    assertThat(queries).hasValue(2);
  }

  @Test
  void theSwitchKeepsTheGateClosedWithoutAskingTheRegister() {
    InstanceVersionCipherFormatGate gate = gate(false);

    assertThat(gate.writesKeyedFormat()).isFalse();
    assertThat(queries).hasValue(0);
  }
}
