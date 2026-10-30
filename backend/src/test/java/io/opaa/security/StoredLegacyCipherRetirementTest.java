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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Unit tests for {@link StoredLegacyCipherRetirement}: the switch, the interval and the lapse. */
class StoredLegacyCipherRetirementTest {

  private static final Instant RETIRED_AT = Instant.parse("2026-10-30T10:00:00Z");

  private final AtomicLong now = new AtomicLong();
  private final AtomicBoolean anotherVersion = new AtomicBoolean();
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

  private StoredLegacyCipherRetirement retirement(boolean keyed) {
    return new StoredLegacyCipherRetirement(
        keyed, () -> jdbc, since -> anotherVersion.get(), Duration.ofSeconds(15), now::get);
  }

  private void stored(boolean retired) {
    when(jdbc.queryForList(anyString(), eq(Timestamp.class), any(Object[].class)))
        .thenReturn(retired ? List.of(Timestamp.from(RETIRED_AT)) : List.of());
  }

  @Test
  void eachAnswerIsKeptForOneHeartbeatIntervalBothWays() {
    stored(false);
    StoredLegacyCipherRetirement retirement = retirement(true);

    assertThat(retirement.refusesLegacy(EncryptionPurpose.CREDENTIALS)).isFalse();
    stored(true);
    now.addAndGet(Duration.ofSeconds(14).toNanos());
    assertThat(retirement.refusesLegacy(EncryptionPurpose.CREDENTIALS)).as("cached").isFalse();
    now.addAndGet(Duration.ofSeconds(1).toNanos());
    assertThat(retirement.refusesLegacy(EncryptionPurpose.CREDENTIALS)).isTrue();
    stored(false);
    now.addAndGet(Duration.ofSeconds(15).toNanos());
    assertThat(retirement.refusesLegacy(EncryptionPurpose.CREDENTIALS)).as("deleted").isFalse();

    verify(jdbc, times(3)).queryForList(anyString(), eq(Timestamp.class), any(Object[].class));
  }

  @Test
  void aRetirementLapsesOnceAnInstanceOfAnotherVersionRanSince() {
    stored(true);
    anotherVersion.set(true);

    assertThat(retirement(true).refusesLegacy(EncryptionPurpose.SETTINGS)).isFalse();
  }

  @Test
  void withTheKeyedFormatHeldBackNothingIsRefusedAskedOrMeasured() {
    StoredLegacyCipherRetirement retirement = retirement(false);

    assertThat(retirement.refusesLegacy(EncryptionPurpose.CREDENTIALS)).isFalse();
    retirement.measured(EncryptionPurpose.CREDENTIALS, 0);

    verifyNoInteractions(jdbc);
  }
}
