package io.opaa.eval;

import io.opaa.eval.PipelineEvaluationReport.PipelineQueryResult;
import io.opaa.eval.PipelineEvaluationReport.PipelineRunConfiguration;
import io.opaa.eval.PipelineEvaluationReport.SelectionCoverage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Runs a golden dataset through the pipeline measurement path and assembles a {@link
 * PipelineEvaluationReport} (issue #1039, docs/features/retrieval-benchmark.md §1).
 *
 * <p>Takes the pipeline itself as a function from a query to the selected chunks' file names in
 * selection order, rather than depending on {@code QueryService} directly: the harness supplies
 * {@code QueryService#retrieveRelevantChunks} (steps 2–6, no answer generation), while this class
 * stays a pure, Docker- and Spring-free unit and is exercised by {@code
 * PipelineRetrievalEvaluatorTest} in the {@code evalUnitTest} task.
 *
 * <p>The chunk list is deduplicated to documents by {@link DocumentRanking} exactly as the
 * raw-vector path does — a document's rank is the rank of its best-placed chunk — and truncated at
 * {@link PipelineMetricsAggregate#RANKING_K}. Truncation is a formality on this path: the pipeline
 * already caps its selection at {@code top-k}, so the deduplicated list can only be shorter, never
 * longer. That is deliberate rather than incidental: falling short of the window is a legitimate
 * outcome once the similarity threshold applies (see {@link SelectionCoverage}), not an error the
 * way it would be for the raw-vector path.
 */
public final class PipelineRetrievalEvaluator {

  private PipelineRetrievalEvaluator() {}

  /** One case's outcome: its windowed metrics plus what the pipeline actually returned. */
  public record CaseOutcome(
      RetrievalMetrics.WindowedQueryResult metrics,
      int chunksReturned,
      int distinctDocumentsReturned) {}

  /**
   * Evaluates a single case from the chunk file names the pipeline selected for it, in selection
   * order. {@code null} entries (a chunk without {@code file_name} metadata) are dropped by {@link
   * DocumentRanking#dedupeToDocuments}, the same handling the raw-vector path applies.
   */
  public static CaseOutcome evaluateCase(GoldenCase goldenCase, List<String> rankedChunkFileNames) {
    DocumentRanking.DocumentWindowResult window =
        DocumentRanking.applyDocumentWindow(
            rankedChunkFileNames, PipelineMetricsAggregate.RANKING_K);
    return new CaseOutcome(
        RetrievalMetrics.evaluateAt(
            goldenCase,
            window.rankedFileNames(),
            PipelineMetricsAggregate.HIT_RATE_K,
            PipelineMetricsAggregate.RANKING_K),
        rankedChunkFileNames.size(),
        window.distinctDocumentsReached());
  }

  /**
   * Runs every case through {@code pipeline}, in dataset order. Deliberately separate from {@link
   * #report}: the run configuration a report carries includes the measured duration, which can only
   * be determined after this method has returned.
   */
  public static List<CaseOutcome> evaluateAll(
      List<GoldenCase> goldenCases, Function<String, List<String>> pipeline) {
    List<CaseOutcome> outcomes = new ArrayList<>(goldenCases.size());
    for (GoldenCase goldenCase : goldenCases) {
      outcomes.add(evaluateCase(goldenCase, pipeline.apply(goldenCase.query())));
    }
    return List.copyOf(outcomes);
  }

  /** Assembles the report from already-computed outcomes. */
  public static PipelineEvaluationReport report(
      List<CaseOutcome> outcomes, PipelineRunConfiguration runConfiguration) {
    List<PipelineQueryResult> allQueryResults =
        outcomes.stream()
            .sorted(
                Comparator.comparingDouble((CaseOutcome o) -> o.metrics().ndcg())
                    .thenComparingDouble(o -> o.metrics().hitRate()))
            .map(PipelineRetrievalEvaluator::toQueryResult)
            .toList();

    return new PipelineEvaluationReport(
        PipelineEvaluationReport.PIPELINE_MEASUREMENT_CONTRACT_VERSION,
        PipelineMetricsAggregate.METRIC_WINDOW_NOTE,
        runConfiguration,
        selectionCoverage(outcomes),
        PipelineMetricsAggregate.of(outcomes),
        PipelineMetricsAggregate.groupBy(outcomes, GoldenCase::category),
        PipelineMetricsAggregate.groupBy(outcomes, GoldenCase::difficulty),
        PipelineMetricsAggregate.groupBy(outcomes, GoldenCase::language),
        allQueryResults.stream().limit(10).toList(),
        allQueryResults);
  }

  static SelectionCoverage selectionCoverage(List<CaseOutcome> outcomes) {
    if (outcomes.isEmpty()) {
      return new SelectionCoverage(0, 0, 0, 0, 0.0, 0.0);
    }
    int n = outcomes.size();
    return new SelectionCoverage(
        n,
        (int) outcomes.stream().filter(o -> o.chunksReturned() == 0).count(),
        outcomes.stream().mapToInt(CaseOutcome::chunksReturned).min().orElse(0),
        outcomes.stream().mapToInt(CaseOutcome::chunksReturned).max().orElse(0),
        outcomes.stream().mapToInt(CaseOutcome::chunksReturned).sum() / (double) n,
        outcomes.stream().mapToInt(CaseOutcome::distinctDocumentsReturned).sum() / (double) n);
  }

  private static PipelineQueryResult toQueryResult(CaseOutcome outcome) {
    RetrievalMetrics.WindowedQueryResult m = outcome.metrics();
    return new PipelineQueryResult(
        m.goldenCase().id(),
        m.goldenCase().query(),
        m.goldenCase().category(),
        m.goldenCase().difficulty(),
        m.goldenCase().language(),
        m.hitRate(),
        m.reciprocalRank(),
        m.ndcg(),
        m.recall(),
        m.allExpectedDocumentsHit(),
        outcome.chunksReturned(),
        outcome.distinctDocumentsReturned(),
        m.goldenCase().expectedDocuments(),
        m.rankedFileNames());
  }
}
