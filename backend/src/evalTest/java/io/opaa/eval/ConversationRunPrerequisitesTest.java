package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.query.QueryProperties;
import java.util.List;
import java.util.Map;
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

  /**
   * The Mehrfachlauf-Regel, through the shared implementation: three runs, the median by nDCG@8,
   * <b>and</b> the spread plus the count of turns whose decomposition deviated across the runs.
   * This path calls the decomposition once per turn, so that count is its statement about how
   * stable the measurement is at all - a median alone would throw it away.
   */
  @Test
  void theRepeatedRunsAreRepresentedByTheirMedianAndReportTheirSpread() {
    List<ConversationEvaluationReport> runs =
        new java.util.ArrayList<>(
            List.of(
                reportWithNdcg(0.90, List.of("Teilfrage A")),
                reportWithNdcg(0.10, List.of("Teilfrage A")),
                reportWithNdcg(0.50, List.of("Teilfrage B"))));
    java.util.Iterator<ConversationEvaluationReport> scripted = runs.iterator();

    MehrfachlaufRule.Measurement<ConversationEvaluationReport> measurement =
        MehrfachlaufRule.measure(true, scripted::next, ConversationHarnessSupport::runView);

    assertThat(measurement.report().overall().ndcgAt8()).isEqualTo(0.50);
    assertThat(measurement.multiRun()).isTrue();
    assertThat(measurement.summary().ndcgAt8().min()).isEqualTo(0.10);
    assertThat(measurement.summary().ndcgAt8().max()).isEqualTo(0.90);
    assertThat(measurement.summary().decompositionDeviatingCaseIds())
        .as("the turn whose decomposition differed between runs is named, not only counted")
        .containsExactly("verw-conv-001#1");
  }

  private static ConversationEvaluationReport reportWithNdcg(double ndcg, List<String> subQueries) {
    return new ConversationEvaluationReport(
        ConversationEvaluationReport.CONVERSATION_MEASUREMENT_CONTRACT_VERSION,
        PipelineMetricsAggregate.METRIC_WINDOW_NOTE,
        ConversationEvaluationReport.SINGLE_PATH_NOTE,
        null,
        new PipelineMetricsAggregate(1, 1.0, ndcg, ndcg, ndcg, 1.0, 1, 1, 1, 1.0),
        Map.of(),
        Map.of(),
        new ConversationEvaluationReport.CaseOutcomeSummary(0, 0, Map.of()),
        null,
        null,
        List.of(
            new ConversationEvaluationReport.ConversationCaseResult(
                "verw-conv-001",
                "anaphora_resolution",
                GoldenCase.ExpectedState.KNOWN_GAP,
                false,
                List.of(
                    new ConversationEvaluationReport.TurnResult(
                        "verw-conv-001#1",
                        0,
                        "Frage?",
                        List.of("a.md"),
                        List.of("a.md"),
                        1.0,
                        1.0,
                        1.0,
                        1.0,
                        1.0,
                        4,
                        7,
                        true,
                        0,
                        1,
                        1,
                        subQueries,
                        List.of())))));
  }
}
