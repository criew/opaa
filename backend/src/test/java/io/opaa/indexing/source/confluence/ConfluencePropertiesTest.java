package io.opaa.indexing.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConfluencePropertiesTest {

  @Test
  void theRequestBudgetRejectsNegativeValuesAndTreatsZeroAsUnbounded() {
    assertThatThrownBy(
            () -> new ConfluenceProperties(0, null, null, 0, null, 0, 0, 0, null, null, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requestBudgetPerRun");
    ConfluenceProperties unbounded =
        new ConfluenceProperties(0, null, null, 0, null, 0, 0, 0, null, null, 0);
    assertThat(unbounded.hasRequestBudget()).isFalse();
    assertThat(ConfluenceProperties.defaults().requestBudgetPerRun())
        .isEqualTo(ConfluenceProperties.DEFAULT_REQUEST_BUDGET_PER_RUN);
    assertThat(ConfluenceProperties.defaults().hasRequestBudget()).isTrue();
  }
}
