package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Docker-free tests for when {@link MehrfachlaufRule} resets the chat runner. The order of events
 * is the contract: a reset directly before every decomposing run, so no run measures against the
 * prompts a previous run or the readiness probe left cached in the runner.
 */
class MehrfachlaufRuleTest {

  private static final PipelineMetricsAggregate AGGREGATE =
      new PipelineMetricsAggregate(1, 1.0, 1.0, 1.0, 1.0, 1.0, 1, 1, 1, 1.0);

  // regression guard for #3814: a run that inherited the probe's cached prompts measured another
  // decomposition than one on a fresh runner, depending only on how long indexing took.
  @Test
  void everyDecomposingRunStartsOnAFreshChatRunner() {
    List<String> events = new ArrayList<>();

    MehrfachlaufRule.measure(
        true,
        () -> {
          events.add("run");
          return AGGREGATE;
        },
        aggregate -> new MultiRunAggregator.RunView(aggregate, Map.of()),
        () -> events.add("fresh"));

    assertThat(events).containsExactly("fresh", "run", "fresh", "run", "fresh", "run");
  }

  @Test
  void aRunWithoutDecompositionLeavesTheChatRunnerAlone() {
    List<String> events = new ArrayList<>();

    MehrfachlaufRule.Measurement<PipelineMetricsAggregate> measurement =
        MehrfachlaufRule.measure(
            false,
            () -> {
              events.add("run");
              return AGGREGATE;
            },
            aggregate -> new MultiRunAggregator.RunView(aggregate, Map.of()),
            () -> events.add("fresh"));

    assertThat(events).containsExactly("run");
    assertThat(measurement.multiRun()).isFalse();
  }
}
