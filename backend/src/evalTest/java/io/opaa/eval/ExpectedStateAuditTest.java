package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.eval.ExpectedStateAudit.MeasurementPath;
import io.opaa.eval.GoldenCase.ExpectedState;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the declared-vs-measured case-state audit, one audit per measurement path. */
class ExpectedStateAuditTest {

  private static final String FIELD = MeasurementPath.RAW_VECTOR.stateField();

  private static ExpectedStateAudit.CaseState state(
      String id, String caseClass, ExpectedState declared, boolean solvedNow) {
    return new ExpectedStateAudit.CaseState(id, caseClass, declared, solvedNow);
  }

  private static GoldenCase.PathState pathState(ExpectedState state) {
    return new GoldenCase.PathState(state, "2026-09-14", "Testfixture");
  }

  private static GoldenCase goldenCase(
      String id, List<String> expected, ExpectedState rawVector, ExpectedState pipeline) {
    return new GoldenCase(
        id,
        "test",
        "frage " + id,
        expected,
        "multi_hop",
        "easy",
        "de",
        "t",
        null,
        new GoldenCase.ExpectedStateByPath(pathState(rawVector), pathState(pipeline)));
  }

  @Test
  void namesAKnownGapCaseThatHasBecomeSolved() {
    var result =
        ExpectedStateAudit.evaluate(
            FIELD,
            List.of(
                state("a", "multi_hop", ExpectedState.KNOWN_GAP, true),
                state("b", "multi_hop", ExpectedState.KNOWN_GAP, false)));

    assertThat(result.unexpectedlySolved()).containsExactly("a");
    assertThat(result.unexpectedlyUnsolved()).isEmpty();
    assertThat(result.matchesDeclaredStates()).isFalse();
    assertThat(result.measuredSolved()).isEqualTo(1);
    assertThat(result.declaredKnownGap()).isEqualTo(2);
  }

  @Test
  void namesASolvedCaseThatIsNoLongerSolved() {
    var result =
        ExpectedStateAudit.evaluate(
            FIELD, List.of(state("a", "exact_identifier", ExpectedState.SOLVED, false)));

    assertThat(result.unexpectedlyUnsolved()).containsExactly("a");
    assertThat(result.unexpectedlySolved()).isEmpty();
  }

  /**
   * A case that stays in its declared state produces no finding and no second list: a lasting path
   * asymmetry is expressed by the two declared states, not by an accepted deviation.
   */
  @Test
  void aCaseInItsDeclaredStateIsNoFinding() {
    var result =
        ExpectedStateAudit.evaluate(
            FIELD,
            List.of(
                state("gap", "compound_word", ExpectedState.KNOWN_GAP, false),
                state("solved", "compound_word", ExpectedState.SOLVED, true)));

    assertThat(result.matchesDeclaredStates()).isTrue();
    assertThat(ExpectedStateAudit.renderMarkdown(result))
        .contains("Keine Abweichung vom deklarierten Zustand")
        .doesNotContain("Ausnahme")
        .doesNotContain("Erwartete");
    assertThat(ExpectedStateAudit.renderSummary(result))
        .contains("Keine Abweichung")
        .doesNotContain("Ausnahme")
        .doesNotContain("Erwartete");
  }

  /**
   * The asymmetric case measured exactly as declared: known gap on the raw-vector path, solved on
   * the pipeline path. Both audits are clean — the regression guard for the 13 permanently
   * asymmetric verwaltung cases that used to sit in the finding list of every run.
   */
  @Test
  void anAsymmetricCaseMeasuredAsDeclaredIsCleanOnBothPaths() {
    GoldenCase asymmetric =
        goldenCase("asym", List.of("a.md"), ExpectedState.KNOWN_GAP, ExpectedState.SOLVED);

    var rawVector =
        ExpectedStateAudit.evaluate(
            MeasurementPath.RAW_VECTOR.stateField(),
            List.of(ExpectedStateAudit.caseState(MeasurementPath.RAW_VECTOR, asymmetric, false)));
    var pipeline =
        ExpectedStateAudit.evaluate(
            MeasurementPath.PIPELINE.stateField(),
            List.of(ExpectedStateAudit.caseState(MeasurementPath.PIPELINE, asymmetric, true)));

    assertThat(rawVector.matchesDeclaredStates()).isTrue();
    assertThat(pipeline.matchesDeclaredStates()).isTrue();
    assertThat(rawVector.declaredKnownGap()).isEqualTo(1);
    assertThat(pipeline.declaredSolved()).isEqualTo(1);
  }

  /**
   * A case that changes its state on one path only is a finding on exactly that path — measured
   * through both paths' real wiring ({@link ExpectedStateAudit#fromRawVectorResults} and {@link
   * PipelineRetrievalEvaluator#report}), not only through {@code evaluate}.
   */
  @Test
  void aStateChangeOnOnePathIsAFindingOnlyOnThatPath() {
    // Declared: known gap on the raw-vector path, solved on the pipeline path. Measured: solved on
    // both — the raw-vector path has gained the case, the pipeline path is unchanged.
    GoldenCase gainedOnRawVector =
        goldenCase("gained", List.of("a.md"), ExpectedState.KNOWN_GAP, ExpectedState.SOLVED);

    var rawVectorAudit =
        ExpectedStateAudit.fromRawVectorResults(
            List.of(RetrievalMetrics.evaluate(gainedOnRawVector, List.of("a.md", "x.md"))));
    var pipelineAudit =
        PipelineRetrievalEvaluator.report(
                PipelineRetrievalEvaluator.evaluateAll(
                    List.of(gainedOnRawVector),
                    query ->
                        new PipelineRetrievalEvaluator.PipelineInvocationResult(
                            List.of("a.md", "x.md"), List.of(query))),
                PipelineRetrievalEvaluatorTest.runConfiguration())
            .expectedStateAudit();

    assertThat(rawVectorAudit.stateField()).isEqualTo("expected_state.raw_vector");
    assertThat(rawVectorAudit.unexpectedlySolved()).containsExactly("gained");
    assertThat(rawVectorAudit.unexpectedlyUnsolved()).isEmpty();
    assertThat(ExpectedStateAudit.renderMarkdown(rawVectorAudit))
        .contains("`expected_state.raw_vector`")
        .contains("`gained`");

    assertThat(pipelineAudit.stateField()).isEqualTo("expected_state.pipeline");
    assertThat(pipelineAudit.matchesDeclaredStates()).isTrue();
  }

  /** The reverse direction: a solved pipeline state lost while the raw-vector path is unchanged. */
  @Test
  void aLossOnThePipelinePathIsNotReportedOnTheRawVectorPath() {
    GoldenCase lostOnPipeline =
        goldenCase("lost", List.of("a.md"), ExpectedState.SOLVED, ExpectedState.SOLVED);

    var rawVectorAudit =
        ExpectedStateAudit.evaluate(
            MeasurementPath.RAW_VECTOR.stateField(),
            List.of(
                ExpectedStateAudit.caseState(MeasurementPath.RAW_VECTOR, lostOnPipeline, true)));
    var pipelineAudit =
        ExpectedStateAudit.evaluate(
            MeasurementPath.PIPELINE.stateField(),
            List.of(ExpectedStateAudit.caseState(MeasurementPath.PIPELINE, lostOnPipeline, false)));

    assertThat(rawVectorAudit.matchesDeclaredStates()).isTrue();
    assertThat(pipelineAudit.unexpectedlyUnsolved()).containsExactly("lost");
    assertThat(ExpectedStateAudit.renderSummary(pipelineAudit))
        .contains("ALS solved GEFÜHRT, ABER NICHT GELÖST")
        .contains("expected_state.pipeline");
  }

  @Test
  void eachPathReadsItsOwnDeclaredState() {
    GoldenCase asymmetric =
        goldenCase("asym", List.of("a.md"), ExpectedState.KNOWN_GAP, ExpectedState.SOLVED);

    assertThat(MeasurementPath.RAW_VECTOR.declaredState(asymmetric))
        .isEqualTo(ExpectedState.KNOWN_GAP);
    assertThat(MeasurementPath.PIPELINE.declaredState(asymmetric)).isEqualTo(ExpectedState.SOLVED);
    GoldenCase withoutStates =
        new GoldenCase("n", "test", "q", List.of("a.md"), "c", "easy", "de", "t", null, null);
    assertThat(MeasurementPath.PIPELINE.declaredState(withoutStates)).isNull();
  }

  /** The Markdown block is what reaches the job summary, PR comment and alert issue (§5). */
  @Test
  void markdownCarriesThePerClassTableAndBothDeviationDirections() {
    var result =
        ExpectedStateAudit.evaluate(
            MeasurementPath.PIPELINE.stateField(),
            List.of(
                state("gap-now-solved", "multi_hop", ExpectedState.KNOWN_GAP, true),
                state("was-solved", "compound_word", ExpectedState.SOLVED, false)));

    String markdown = ExpectedStateAudit.renderMarkdown(result);

    assertThat(markdown)
        .contains("### Zustandsfelder (`expected_state.pipeline`)")
        .contains("| Fallklasse | n |")
        .contains("`multi_hop`")
        .contains("gap-now-solved")
        .contains("was-solved");
    assertThat(ExpectedStateAudit.renderMarkdown(null)).isEmpty();
  }

  @Test
  void reportsPerCaseClass() {
    var result =
        ExpectedStateAudit.evaluate(
            FIELD,
            List.of(
                state("a", "compound_word", ExpectedState.SOLVED, true),
                state("b", "compound_word", ExpectedState.KNOWN_GAP, false),
                state("c", "metadata_filter", ExpectedState.KNOWN_GAP, false)));

    assertThat(result.byCaseClass()).containsOnlyKeys("compound_word", "metadata_filter");
    assertThat(result.byCaseClass().get("compound_word"))
        .isEqualTo(new ExpectedStateAudit.ClassResult(2, 1, 1, 1));
    assertThat(result.byCaseClass().get("metadata_filter"))
        .isEqualTo(new ExpectedStateAudit.ClassResult(1, 0, 1, 0));
    assertThat(result.matchesDeclaredStates()).isTrue();
  }

  /** A dataset without state fields must be "absent", never "audited and clean". */
  @Test
  void isNullWhenNoCaseDeclaresAState() {
    assertThat(ExpectedStateAudit.evaluate(FIELD, List.of(state("a", "cat", null, true)))).isNull();
    assertThat(ExpectedStateAudit.renderSummary(null))
        .contains("nicht deklariert")
        .doesNotContain("Keine Abweichung");
  }

  /** "Solved" is every expected document in the window, not merely a hit somewhere. */
  @Test
  void solvedMeansEveryExpectedDocumentInTheWindow() {
    List<String> expected = List.of("a.md", "b.md");
    assertThat(ExpectedStateAudit.isSolved(1.0, List.of("a.md", "b.md"), expected)).isTrue();
    assertThat(ExpectedStateAudit.isSolved(0.5, List.of("a.md", "x.md"), expected)).isFalse();
    assertThat(ExpectedStateAudit.isSolved(0.0, List.of("x.md"), expected)).isFalse();
    assertThat(ExpectedStateAudit.isSolved(1.0, List.of(), expected)).isFalse();
  }

  /**
   * The rank-1 half of the criterion: a metadata_filter case whose confusion partner (the wrong
   * Fassung) sits above the right document is not solved, even though both are in the window.
   */
  @Test
  void solvedRequiresAnExpectedDocumentAtRankOne() {
    assertThat(
            ExpectedStateAudit.isSolved(
                1.0, List.of("fassung-2023.md", "fassung-2024.md"), List.of("fassung-2024.md")))
        .isFalse();
    assertThat(
            ExpectedStateAudit.isSolved(
                1.0, List.of("fassung-2024.md", "fassung-2023.md"), List.of("fassung-2024.md")))
        .isTrue();
  }

  @Test
  void summaryNamesBothDeviationDirectionsAndTheFieldToMaintain() {
    var result =
        ExpectedStateAudit.evaluate(
            FIELD,
            List.of(
                state("gap-now-solved", "multi_hop", ExpectedState.KNOWN_GAP, true),
                state("was-solved", "multi_hop", ExpectedState.SOLVED, false)));

    String summary = ExpectedStateAudit.renderSummary(result);

    assertThat(summary).contains("gap-now-solved").contains("was-solved");
    assertThat(summary).contains("ALS known_gap GEFÜHRT, ABER GELÖST");
    assertThat(summary).contains("ALS solved GEFÜHRT, ABER NICHT GELÖST");
    assertThat(summary).contains("Zustandsfelder (expected_state.raw_vector)");
  }

  /** The JSON report carries no list for accepted deviations any more — only the two directions. */
  @Test
  void resultHasNoAcceptedDeviationComponents() {
    assertThat(
            java.util.Arrays.stream(ExpectedStateAudit.Result.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
        .containsExactly(
            "stateField",
            "casesWithDeclaredState",
            "declaredSolved",
            "declaredKnownGap",
            "measuredSolved",
            "unexpectedlySolved",
            "unexpectedlyUnsolved",
            "byCaseClass");
  }
}
