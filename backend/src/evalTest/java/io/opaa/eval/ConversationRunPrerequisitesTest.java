package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.query.QueryProperties;
import org.junit.jupiter.api.Test;

/**
 * Docker-free proof that a multi-turn run never measures quietly under a configuration that cannot
 * measure what it would report (issue #1484, acceptance criterion "Lauf ohne aktives Chat-Modell:
 * ‚nicht ausgeführt', nie stillschweigend ohne Zerlegung gemessen").
 */
class ConversationRunPrerequisitesTest {

  private static QueryProperties queryProperties(boolean decompositionEnabled) {
    return new QueryProperties(8, 25, 1.0, 0.3, decompositionEnabled, 3, 2, true, 0);
  }

  @Test
  void aRunWithoutDecompositionIsNotExecuted() {
    assertThat(
            ConversationRunPrerequisites.notExecutedReason(
                queryProperties(false), "qwen2.5:1.5b-instruct", 24))
        .get()
        .asString()
        .contains("query-decomposition-enabled");
  }

  @Test
  void aRunWithoutAnActiveChatModelIsNotExecuted() {
    assertThat(ConversationRunPrerequisites.notExecutedReason(queryProperties(true), null, 24))
        .get()
        .asString()
        .contains("kein systemweit aktives Chat-Modell");
  }

  @Test
  void aRunWithoutCuratedCasesIsNotExecuted() {
    assertThat(
            ConversationRunPrerequisites.notExecutedReason(
                queryProperties(true), "qwen2.5:1.5b-instruct", 0))
        .get()
        .asString()
        .contains("keine Fälle");
  }

  @Test
  void aFullyEquippedRunIsExecuted() {
    assertThat(
            ConversationRunPrerequisites.notExecutedReason(
                queryProperties(true), "qwen2.5:1.5b-instruct", 24))
        .isEmpty();
  }

  /** Three runs, the median by nDCG@8 - the Mehrfachlauf-Regel, not a nicety of this path. */
  @Test
  void theMedianRunRepresentsTheMeasurement() {
    ConversationEvaluationReport low = reportWithNdcg(0.10);
    ConversationEvaluationReport middle = reportWithNdcg(0.50);
    ConversationEvaluationReport high = reportWithNdcg(0.90);

    assertThat(ConversationHarnessSupport.medianRun(java.util.List.of(high, low, middle)))
        .isSameAs(middle);
  }

  private static ConversationEvaluationReport reportWithNdcg(double ndcg) {
    return new ConversationEvaluationReport(
        ConversationEvaluationReport.CONVERSATION_MEASUREMENT_CONTRACT_VERSION,
        PipelineMetricsAggregate.METRIC_WINDOW_NOTE,
        null,
        new PipelineMetricsAggregate(1, 1.0, ndcg, ndcg, ndcg, 1.0, 1, 1, 1, 1.0),
        java.util.Map.of(),
        java.util.Map.of(),
        new ConversationEvaluationReport.CaseOutcomeSummary(0, 0, java.util.Map.of()),
        null,
        null,
        java.util.List.of());
  }
}
