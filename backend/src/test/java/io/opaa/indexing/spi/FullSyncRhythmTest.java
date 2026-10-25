package io.opaa.indexing.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.knowledge.SourceType;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The rhythm of the full sync has one source per level: the library's own days, else the value the
 * administration set for the source type, else the factory default in the code.
 */
class FullSyncRhythmTest {

  private static final SourceType TYPE = SourceType.of("CONFLUENCE");
  private static final Duration FACTORY = Duration.ofDays(7);

  private final FullSyncRhythmSettingRepository repository =
      mock(FullSyncRhythmSettingRepository.class);
  private final FullSyncRhythm rhythm = new FullSyncRhythm(repository);

  @Test
  void withoutAnyValueTheFactoryDefaultApplies() {
    when(repository.findDays(TYPE.key())).thenReturn(Optional.empty());

    assertThat(rhythm.interval(TYPE, FACTORY, null)).isEqualTo(FACTORY);
    assertThat(rhythm.installationDefault(TYPE, FACTORY)).isEqualTo(FACTORY);
    assertThat(rhythm.administrationDays(TYPE)).isEmpty();
  }

  @Test
  void theValueOfTheAdministrationReplacesTheFactoryDefault() {
    when(repository.findDays(TYPE.key())).thenReturn(Optional.of(3));

    assertThat(rhythm.interval(TYPE, FACTORY, null)).isEqualTo(Duration.ofDays(3));
    assertThat(rhythm.installationDefault(TYPE, FACTORY)).isEqualTo(Duration.ofDays(3));
    assertThat(rhythm.administrationDays(TYPE)).contains(3);
  }

  @Test
  void theLibrarysOwnRhythmWinsOverBoth() {
    when(repository.findDays(TYPE.key())).thenReturn(Optional.of(3));

    assertThat(rhythm.interval(TYPE, FACTORY, 30)).isEqualTo(Duration.ofDays(30));
  }

  @Test
  void aTypeWithoutFactoryDefaultHasNoRhythm() {
    assertThat(rhythm.installationDefault(TYPE, null)).isNull();
    verify(repository, never()).findDays(anyString());
  }

  @Test
  void nullReturnsToTheFactoryDefaultAndAValueOutsideTheRangeIsRefused() {
    assertThatThrownBy(() -> rhythm.setDays(TYPE, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> rhythm.setDays(TYPE, 366))
        .isInstanceOf(IllegalArgumentException.class);
    verify(repository, never()).upsert(anyString(), anyInt());

    rhythm.setDays(TYPE, null);
    verify(repository).clear(TYPE.key());
    rhythm.setDays(TYPE, 365);
    verify(repository).upsert(TYPE.key(), 365);
  }
}
