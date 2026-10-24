package io.opaa.succession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opaa.api.types.SuccessionAddressee;
import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.permission.SuccessionFinding;
import io.opaa.permission.SuccessionFindingReading;
import io.opaa.permission.SuccessionFindingSource;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The figures of the tabs: every source of its own is asked once, a reading several tabs share is
 * asked once for all of them - and none of its sources is asked on its own.
 */
class SuccessionServiceCountsTest {

  private static final UUID ORGANIZATION = UUID.randomUUID();

  @Test
  void asksASharedReadingOnceAndCountsEveryTab() {
    AtomicInteger readings = new AtomicInteger();
    SuccessionFindingReading groups =
        organizationId -> {
          readings.incrementAndGet();
          return Map.of(
              SuccessionKind.GRANTS_WITHOUT_RECIPIENT, findings(1),
              SuccessionKind.GROUP_WITHOUT_EFFECT, findings(3));
        };
    SuccessionService service =
        new SuccessionService(
            List.of(
                own(SuccessionKind.OPEN_SUCCESSION, 2),
                shared(SuccessionKind.GRANTS_WITHOUT_RECIPIENT, groups),
                own(SuccessionKind.OPEN_SUCCESSION, 1),
                shared(SuccessionKind.GROUP_WITHOUT_EFFECT, groups)),
            mock(SuccessionCaseRepository.class),
            new SuccessionProperties(12, null),
            Clock.systemUTC());

    assertThat(service.counts(ORGANIZATION))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                SuccessionKind.OPEN_SUCCESSION, 3,
                SuccessionKind.GRANTS_WITHOUT_RECIPIENT, 1,
                SuccessionKind.GROUP_WITHOUT_EFFECT, 3));
    assertThat(readings).as("one reading for both group tabs").hasValue(1);
  }

  @Test
  void aTabWithoutSourceCountsZero() {
    SuccessionService service =
        new SuccessionService(
            List.of(own(SuccessionKind.OPEN_SUCCESSION, 1)),
            mock(SuccessionCaseRepository.class),
            new SuccessionProperties(12, null),
            Clock.systemUTC());

    assertThat(service.counts(ORGANIZATION))
        .containsEntry(SuccessionKind.GRANTS_WITHOUT_RECIPIENT, 0)
        .containsEntry(SuccessionKind.GROUP_WITHOUT_EFFECT, 0);
  }

  private static List<SuccessionFinding> findings(int count) {
    return java.util.stream.IntStream.range(0, count)
        .mapToObj(
            i ->
                SuccessionFinding.of(
                    SuccessionObjectType.GROUP,
                    UUID.randomUUID(),
                    "Gruppe " + i,
                    SuccessionAddressee.SYSTEM_ADMINISTRATION))
        .toList();
  }

  private static SuccessionFindingSource own(SuccessionKind kind, int count) {
    return new Source(kind, Optional.empty(), findings(count));
  }

  /** A source of a shared reading must not be asked on its own: its own list would throw. */
  private static SuccessionFindingSource shared(
      SuccessionKind kind, SuccessionFindingReading reading) {
    return new Source(kind, Optional.of(reading), null);
  }

  private record Source(
      SuccessionKind kind,
      Optional<SuccessionFindingReading> sharedReading,
      List<SuccessionFinding> findings)
      implements SuccessionFindingSource {

    @Override
    public boolean answersFor(SuccessionObjectType objectType) {
      return true;
    }

    @Override
    public List<SuccessionFinding> findingsOf(UUID organizationId) {
      if (findings == null) {
        throw new AssertionError("a source of a shared reading is asked on its own");
      }
      return findings;
    }
  }
}
