package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Unit tests for {@link StoredLegacyCipherRetirement}: the switch, the interval and the latch. */
class StoredLegacyCipherRetirementTest {

  private final AtomicLong now = new AtomicLong();
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

  private StoredLegacyCipherRetirement retirement(boolean keyed) {
    return new StoredLegacyCipherRetirement(keyed, () -> jdbc, Duration.ofSeconds(15), now::get);
  }

  private void stored(boolean retired) {
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
        .thenReturn(retired);
  }

  @Test
  void aPurposeNotYetRetiredIsAskedAgainOncePerHeartbeatIntervalThenLatched() {
    stored(false);
    StoredLegacyCipherRetirement retirement = retirement(true);

    assertThat(retirement.refusesLegacy(EncryptionPurpose.CREDENTIALS)).isFalse();
    stored(true);
    now.addAndGet(Duration.ofSeconds(14).toNanos());
    assertThat(retirement.refusesLegacy(EncryptionPurpose.CREDENTIALS)).as("cached").isFalse();
    now.addAndGet(Duration.ofSeconds(1).toNanos());
    assertThat(retirement.refusesLegacy(EncryptionPurpose.CREDENTIALS)).isTrue();
    stored(false);
    now.addAndGet(Duration.ofMinutes(5).toNanos());
    assertThat(retirement.refusesLegacy(EncryptionPurpose.CREDENTIALS)).as("latched").isTrue();

    verify(jdbc, times(2)).queryForObject(anyString(), eq(Boolean.class), any(Object[].class));
  }

  @Test
  void aConfirmedMeasurementLatchesItsPurposeAlone() {
    stored(false);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    StoredLegacyCipherRetirement retirement = retirement(true);

    retirement.measured(EncryptionPurpose.SETTINGS, 0);

    assertThat(retirement.refusesLegacy(EncryptionPurpose.SETTINGS)).isTrue();
    assertThat(retirement.refusesLegacy(EncryptionPurpose.CREDENTIALS)).isFalse();
  }

  @Test
  void withTheKeyedFormatHeldBackNothingIsRefusedAskedOrMeasured() {
    StoredLegacyCipherRetirement retirement = retirement(false);

    assertThat(retirement.refusesLegacy(EncryptionPurpose.CREDENTIALS)).isFalse();
    retirement.measured(EncryptionPurpose.CREDENTIALS, 0);

    verifyNoInteractions(jdbc);
  }
}
