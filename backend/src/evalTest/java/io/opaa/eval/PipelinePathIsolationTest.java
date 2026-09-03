package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the promise that adding the pipeline measurement path (issue #1039) left the raw-vector
 * path measuring exactly what it measured before: same windows, same metric definitions, same
 * measurement-contract version, same report file.
 *
 * <p>Docker-free by construction, and therefore the cheap half of the proof — the expensive half is
 * a real {@code evaluateRetrieval}/{@code checkRetrievalBaseline} run, whose committed baselines
 * stay valid only if everything asserted here still holds. Without these assertions, a later
 * "harmonization" of the two paths' constants would invalidate every committed baseline with no
 * failure until the next nightly Docker run.
 */
class PipelinePathIsolationTest {

  private static GoldenCase goldenCase(List<String> expected) {
    return new GoldenCase(
        "a", "test", "frage", expected, "cat", "easy", "de", "t", null, null, null, null, null);
  }

  @Test
  void rawVectorPathKeepsItsMeasurementContractVersion() {
    // Version 3 since issue #1144 made ingestionPipelineFingerprint a fixed point on both paths.
    assertThat(EvaluationReport.CURRENT_MEASUREMENT_CONTRACT_VERSION)
        .as(
            "both paths' fixed points move together for issue #1144 (both measure chunks the same "
                + "ingestion pipelines produced) — see PipelineEvaluationReport's own version for "
                + "the pipeline path's independent count")
        .isEqualTo(3);
  }

  @Test
  void pipelinePathCountsItsOwnContractVersionSeparately() {
    // Version 4: 3 from issue #1049 (the lexical path's switch and the measured library's
    // full-text backfill state), plus 1 from issue #1144 (ingestionPipelineFingerprint) — counted
    // independently of the raw-vector path above, whose own count (2 plus the same #1144 bump)
    // moves for unrelated reasons at unrelated points in its history.
    assertThat(PipelineEvaluationReport.PIPELINE_MEASUREMENT_CONTRACT_VERSION).isEqualTo(4);
  }

  @Test
  void rawVectorPathKeepsItsWindows() {
    assertThat(RetrievalMetrics.HIT_RATE_K).isEqualTo(5);
    assertThat(RetrievalMetrics.NDCG_K).isEqualTo(10);
    assertThat(RetrievalMetrics.RECALL_K).isEqualTo(10);
  }

  /**
   * The windowed evaluation added for the pipeline path must reproduce the raw-vector path's
   * numbers exactly when handed the raw-vector path's own windows — proof that no metric definition
   * was quietly altered while making the window configurable.
   */
  @Test
  void windowedEvaluationAtTheRawVectorWindowsReproducesTheRawVectorNumbers() {
    GoldenCase goldenCase = goldenCase(List.of("e1", "e2"));
    List<String> ranked = List.of("d1", "e1", "d3", "d4", "d5", "d6", "e2", "d8", "d9", "d10");

    RetrievalMetrics.QueryResult raw = RetrievalMetrics.evaluate(goldenCase, ranked);
    RetrievalMetrics.WindowedQueryResult windowed =
        RetrievalMetrics.evaluateAt(
            goldenCase, ranked, RetrievalMetrics.HIT_RATE_K, RetrievalMetrics.NDCG_K);

    assertThat(windowed.hitRate()).isEqualTo(raw.hitRateAt5());
    assertThat(windowed.reciprocalRank()).isEqualTo(raw.reciprocalRank());
    assertThat(windowed.ndcg()).isEqualTo(raw.ndcgAt10());
    assertThat(windowed.recall()).isEqualTo(raw.recallAt10());
    assertThat(windowed.allExpectedDocumentsHit()).isEqualTo(raw.allExpectedDocumentsHitAt10());
  }

  /** Two paths, two files — a pipeline run must never overwrite a raw-vector report. */
  @Test
  void pipelineReportsGoToTheirOwnFilePerDomain() {
    assertThat(PipelineHarnessSupport.reportFile(EvalDomainConfig.COMIC_CHARACTERS))
        .hasFileName("pipeline-metrics-comic-characters.json");
    assertThat(PipelineHarnessSupport.reportFile(EvalDomainConfig.CITY_LANDMARKS))
        .hasFileName("pipeline-metrics-city-landmarks.json");
    assertThat(PipelineHarnessSupport.reportFile(EvalDomainConfig.VERWALTUNG))
        .hasFileName("pipeline-metrics-verwaltung.json");
  }

  /**
   * Issue #1040's first acceptance criterion, as an assertion: one baseline file per path and
   * domain, and a pipeline baseline can never carry a raw-vector baseline's name — the committed
   * numbers from #228/#234 stay where they are.
   */
  @Test
  void pipelineBaselinesGoToTheirOwnFilePerDomain() {
    assertThat(EvalDomainConfig.COMIC_CHARACTERS.pipelineBaselineFileName())
        .isEqualTo("pipeline-comic-characters.json")
        .isNotEqualTo(EvalDomainConfig.COMIC_CHARACTERS.baselineFileName());
    assertThat(EvalDomainConfig.CITY_LANDMARKS.pipelineBaselineFileName())
        .isEqualTo("pipeline-city-landmarks.json")
        .isNotEqualTo(EvalDomainConfig.CITY_LANDMARKS.baselineFileName());
    assertThat(EvalDomainConfig.VERWALTUNG.pipelineBaselineFileName())
        .isEqualTo("pipeline-verwaltung.json")
        .isNotEqualTo(EvalDomainConfig.VERWALTUNG.baselineFileName());
    assertThat(
            List.of(
                EvalDomainConfig.COMIC_CHARACTERS.baselineFileName(),
                EvalDomainConfig.CITY_LANDMARKS.baselineFileName(),
                EvalDomainConfig.VERWALTUNG.baselineFileName(),
                EvalDomainConfig.COMIC_CHARACTERS.pipelineBaselineFileName(),
                EvalDomainConfig.CITY_LANDMARKS.pipelineBaselineFileName(),
                EvalDomainConfig.VERWALTUNG.pipelineBaselineFileName()))
        .doesNotHaveDuplicates();
  }

  /**
   * The committed raw-vector baselines of #228/#234 must stay loadable and valid under everything
   * this issue changed — the cheap, Docker-free half of "die bestehenden Baselines bleiben
   * unangetastet und gültig".
   */
  @Test
  void committedRawVectorBaselinesStayLoadableAndValid() throws java.io.IOException {
    for (EvalDomainConfig domain :
        List.of(
            EvalDomainConfig.COMIC_CHARACTERS,
            EvalDomainConfig.CITY_LANDMARKS,
            EvalDomainConfig.VERWALTUNG)) {
      Baseline baseline =
          Baseline.load(RepoPaths.evalDir().resolve("baseline").resolve(domain.baselineFileName()));
      assertThat(baseline.measurementContractVersion())
          .isEqualTo(EvaluationReport.CURRENT_MEASUREMENT_CONTRACT_VERSION);
      assertThat(baseline.groups()).containsKey(Baseline.OVERALL);
    }
  }

  /**
   * The committed pipeline baselines (issue #1040/#1081/#1043) must stay loadable and pass {@link
   * PipelineBaseline}'s load-time invariants — the Docker-free half of "the committed baseline is
   * internally consistent", run on every {@code evalUnitTest} instead of only inside the expensive,
   * label-gated regression job.
   */
  @Test
  void committedPipelineBaselinesStayLoadableAndValid() throws java.io.IOException {
    for (EvalDomainConfig domain :
        List.of(
            EvalDomainConfig.COMIC_CHARACTERS,
            EvalDomainConfig.CITY_LANDMARKS,
            EvalDomainConfig.VERWALTUNG)) {
      PipelineBaseline baseline =
          PipelineBaseline.load(
              RepoPaths.evalDir().resolve("baseline").resolve(domain.pipelineBaselineFileName()));
      assertThat(baseline.pipelineMeasurementContractVersion())
          .isEqualTo(PipelineEvaluationReport.PIPELINE_MEASUREMENT_CONTRACT_VERSION);
      assertThat(baseline.groups()).containsKey(Baseline.OVERALL);
    }
  }
}
