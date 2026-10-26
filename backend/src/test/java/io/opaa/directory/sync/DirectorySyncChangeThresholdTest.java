package io.opaa.directory.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A safeguard against mass rights revocation never loosens itself quietly: a threshold of 0 or
 * below, or above 100 percent, is refused before anything is written, and without a value of its
 * own the factory default of 30 percent applies.
 */
class DirectorySyncChangeThresholdTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final DirectorySyncChangeThreshold threshold = new DirectorySyncChangeThreshold(jdbc);

  @Test
  void aThresholdOutsideOneToOneHundredPercentIsRefusedUnwritten() {
    for (int percent : new int[] {0, -30, 101}) {
      assertThatThrownBy(() -> threshold.set(percent)).isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(jdbc);
  }

  @Test
  void withoutAValueOfItsOwnTheFactoryDefaultOfThirtyPercentApplies() {
    assertThat(threshold.percent()).isEqualTo(30);
    assertThat(threshold.fraction()).isEqualTo(0.3);
  }
}
