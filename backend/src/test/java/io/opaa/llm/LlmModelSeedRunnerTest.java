package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for {@link LlmModelSeedRunner} (#756, PR #763 review) - a mocked {@link
 * LlmModelSeeder} lets these focus purely on which exceptions this thin wrapper swallows and which
 * it lets propagate.
 */
class LlmModelSeedRunnerTest {

  private final LlmModelSeeder seeder = mock(LlmModelSeeder.class);
  private final LlmModelSeedRunner runner = new LlmModelSeedRunner(seeder);

  @Test
  void delegatesToTheSeeder() {
    assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();

    verify(seeder).seedIfNeeded();
  }

  @Test
  void swallowsADataIntegrityViolationAsTheExpectedLosingReplicaCase() {
    doThrow(new DataIntegrityViolationException("duplicate key")).when(seeder).seedIfNeeded();

    assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
  }

  @Test
  void letsEveryOtherExceptionPropagateAsARealFailure() {
    doThrow(new IllegalStateException("OPAA_SETTINGS_ENCRYPTION_KEY ist nicht gesetzt"))
        .when(seeder)
        .seedIfNeeded();

    assertThatThrownBy(() -> runner.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OPAA_SETTINGS_ENCRYPTION_KEY");
  }
}
