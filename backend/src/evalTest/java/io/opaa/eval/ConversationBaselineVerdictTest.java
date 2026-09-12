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
   * Since #1490 the two Gesprächsgedächtnis fixed points are judged like any other: the named
   * tolerance that let them deviate without failing was removed together with the baseline it
   * described. A run against a baseline drawn before the search window and the Gesprächsnotiz is
   * incomparable, not merely unjudged.
   */
  @Test
  void aBaselineFromBeforeTheSearchWindowAndTheNoteIsIncomparableAndFails() {
    ConversationBaselineVerdict verdict =
        ConversationBaselineVerdict.of(
            incomparable(
                new BaselineComparator.FixedPointMismatch("searchWindowTurns", "0", "2"),
                new BaselineComparator.FixedPointMismatch("conversationNoteCap", "0", "10")));

    assertThat(verdict.kind()).isEqualTo(ConversationBaselineVerdict.Kind.INCOMPARABLE);
    assertThat(verdict.failing()).isTrue();
    assertThat(verdict.headline()).contains("ungültig");
    assertThat(verdict.headline() + verdict.detail()).doesNotContain("Regression im");
    assertThat(verdict.detail())
        .contains("searchWindowTurns: Baseline=0, aktuell=2")
        .contains("conversationNoteCap: Baseline=0, aktuell=10");
  }

  /**
   * The acceptance criterion of #1553, unchanged: an incomparable baseline is reported <b>as</b> a
   * fixed-point deviation, never as a regression. A moved corpus, model or golden dataset is red
   * exactly as on the pipeline path.
   */
  @Test
  void aMovedMeasurementBasisIsNamedAsSuchAndFailsTheRun() {
    ConversationBaselineVerdict verdict =
        ConversationBaselineVerdict.of(
            incomparable(
                new BaselineComparator.FixedPointMismatch(
                    "corpusManifestSha256", "e129bb86", "aaaaaaaa")));

    assertThat(verdict.kind()).isEqualTo(ConversationBaselineVerdict.Kind.INCOMPARABLE);
    assertThat(verdict.failing()).isTrue();
    assertThat(verdict.headline()).contains("ungültig");
    assertThat(verdict.detail())
        .contains("corpusManifestSha256: Baseline=e129bb86, aktuell=aaaaaaaa")
        .contains("Messgrundlage");
  }

  /**
   * An invalid baseline that names no deviating fixed point still says so in its detail rather than
   * rendering an empty deviation list — the result {@code ConversationBaselineComparator} calls "a
   * harness bug". No producer creates it today; the coupling that prevents it is an invariant of
   * one method, not of the public record.
   */
  @Test
  void anInvalidBaselineWithoutANamedDeviationSaysSoAndFails() {
    ConversationBaselineVerdict verdict = ConversationBaselineVerdict.of(incomparable());

    assertThat(verdict.kind()).isEqualTo(ConversationBaselineVerdict.Kind.INCOMPARABLE);
    assertThat(verdict.failing()).isTrue();
    assertThat(verdict.detail()).contains("ohne einen abweichenden Festpunkt zu nennen");
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
   * The Markdown the CI job renders carries the deviation table in the incomparable case too — a
   * summary that only said "ungültig" would leave the reader without the reason.
   */
  @Test
  void theIncomparableMarkdownCarriesTheDeviationTable() {
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
        .contains("ungültig")
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
