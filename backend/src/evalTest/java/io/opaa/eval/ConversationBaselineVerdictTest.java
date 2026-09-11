package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The verdict rules of the multi-turn baseline comparison (issue #1553), Docker-free: which
 * comparison result fails a run and which is merely reported. Without these assertions the only
 * place the distinction would show up is a measurement run of well over an hour.
 */
class ConversationBaselineVerdictTest {

  /**
   * The acceptance criterion of #1553: an incomparable baseline is reported <b>as</b> a fixed-point
   * deviation, never as a regression — and, while it deviates in exactly the two fields #1490
   * re-measures, it does not fail the run.
   */
  @Test
  void aBaselineIncomparableOnlyInTheFieldsAwaitingRemeasurementIsReportedNotFailed() {
    ConversationBaselineVerdict verdict =
        ConversationBaselineVerdict.of(
            incomparable(
                new BaselineComparator.FixedPointMismatch("searchWindowTurns", "0", "2"),
                new BaselineComparator.FixedPointMismatch("conversationNoteCap", "0", "10")));

    assertThat(verdict.kind()).isEqualTo(ConversationBaselineVerdict.Kind.NOT_JUDGED);
    assertThat(verdict.failing()).isFalse();
    assertThat(verdict.headline()).contains("nicht beurteilt");
    assertThat(verdict.headline() + verdict.detail()).doesNotContain("Regression");
    assertThat(verdict.detail())
        .contains("searchWindowTurns: Baseline=0, aktuell=2")
        .contains("conversationNoteCap: Baseline=0, aktuell=10")
        .contains("#1490");
  }

  /**
   * The other half of the same criterion: the tolerance is for the two declared fields, not for
   * incomparability as such. A moved corpus, model or golden dataset is red exactly as on the
   * pipeline path — otherwise the gate above would switch the whole comparison off for good.
   */
  @Test
  void aFixedPointDeviationBeyondTheDeclaredOnesFailsTheRun() {
    ConversationBaselineVerdict verdict =
        ConversationBaselineVerdict.of(
            incomparable(
                new BaselineComparator.FixedPointMismatch("searchWindowTurns", "0", "2"),
                new BaselineComparator.FixedPointMismatch(
                    "corpusManifestSha256", "e129bb86", "aaaaaaaa")));

    assertThat(verdict.kind()).isEqualTo(ConversationBaselineVerdict.Kind.INCOMPARABLE);
    assertThat(verdict.failing()).isTrue();
    assertThat(verdict.headline()).contains("ungültig");
    assertThat(verdict.detail())
        .contains("corpusManifestSha256: Baseline=e129bb86, aktuell=aaaaaaaa")
        .contains("Messgrundlage");
  }

  /** A comparable baseline whose metrics moved is a regression, and is named one. */
  @Test
  void aFailedMetricCheckOnAComparableBaselineIsARegression() {
    ConversationBaselineVerdict verdict =
        ConversationBaselineVerdict.of(
            new ConversationBaselineComparator.ComparisonResult(
                true, List.of(), List.of(failedCheck())));

    assertThat(verdict.kind()).isEqualTo(ConversationBaselineVerdict.Kind.REGRESSION);
    assertThat(verdict.failing()).isTrue();
    assertThat(verdict.headline()).contains("Regression");
    assertThat(verdict.detail()).contains("turn:2");
  }

  @Test
  void aComparableBaselineWithinTolerancePasses() {
    ConversationBaselineVerdict verdict =
        ConversationBaselineVerdict.of(
            new ConversationBaselineComparator.ComparisonResult(
                true, List.of(), List.of(passedCheck())));

    assertThat(verdict.kind()).isEqualTo(ConversationBaselineVerdict.Kind.NO_REGRESSION);
    assertThat(verdict.failing()).isFalse();
  }

  /**
   * The Markdown the CI job renders carries the deviation table in the not-judged case too — a
   * summary that only said "nicht beurteilt" would leave the reader without the reason.
   */
  @Test
  void theNotJudgedMarkdownCarriesTheDeviationTable() {
    ConversationBaselineComparator.ComparisonResult result =
        incomparable(new BaselineComparator.FixedPointMismatch("searchWindowTurns", "0", "2"));

    String markdown =
        ConversationBaselineMarkdownWriter.render(
            result,
            ConversationBaselineVerdict.of(result),
            "pipeline-verwaltung-conversations.json",
            null);

    assertThat(markdown)
        .contains(
            "## Mehrrunden-Messpfad gegen Baseline (`eval/baseline/"
                + "pipeline-verwaltung-conversations.json`)")
        .contains("nicht beurteilt")
        .contains("| `searchWindowTurns` | `0` | `2` |");
  }

  private static ConversationBaselineComparator.ComparisonResult incomparable(
      BaselineComparator.FixedPointMismatch... mismatches) {
    return new ConversationBaselineComparator.ComparisonResult(
        false, List.of(mismatches), List.of());
  }

  private static BaselineComparator.MetricCheck failedCheck() {
    return new BaselineComparator.MetricCheck(
        "turn:2", "nDCG@8", 27, 0.649, 0.500, -0.149, 0.030, false, 0.2, true, false);
  }

  private static BaselineComparator.MetricCheck passedCheck() {
    return new BaselineComparator.MetricCheck(
        "turn:2", "nDCG@8", 27, 0.649, 0.648, -0.001, 0.030, true, 0.2, true, false);
  }
}
