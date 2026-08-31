package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.eval.GoldenCase.ExpectedState;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the declared-vs-measured case-state audit (issue #1043). */
class ExpectedStateAuditTest {

  private static ExpectedStateAudit.CaseState state(
      String id, String caseClass, ExpectedState declared, boolean solvedNow) {
    return new ExpectedStateAudit.CaseState(id, caseClass, declared, null, solvedNow);
  }

  private static ExpectedStateAudit.CaseState stateWithException(
      String id, String caseClass, ExpectedState declared, boolean solvedNow, String reason) {
    return new ExpectedStateAudit.CaseState(id, caseClass, declared, reason, solvedNow);
  }

  @Test
  void namesAKnownGapCaseThatHasBecomeSolved() {
    var result =
        ExpectedStateAudit.evaluate(
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
            List.of(state("a", "exact_identifier", ExpectedState.SOLVED, false)));

    assertThat(result.unexpectedlyUnsolved()).containsExactly("a");
    assertThat(result.unexpectedlySolved()).isEmpty();
  }

  /**
   * A deviation the dataset itself declares as expected is reported separately and does not count
   * as a finding — otherwise a permanently expected deviation would sit in every run's finding list
   * and train readers to ignore the section.
   */
  @Test
  void separatesAcceptedDeviationsFromFindings() {
    var result =
        ExpectedStateAudit.evaluate(
            List.of(
                stateWithException(
                    "known-but-solved",
                    "metadata_filter",
                    ExpectedState.KNOWN_GAP,
                    true,
                    "rankt heute zufällig oben, die geprüfte Fähigkeit fehlt"),
                state("plain-gap", "metadata_filter", ExpectedState.KNOWN_GAP, false)));

    assertThat(result.unexpectedlySolved()).isEmpty();
    assertThat(result.unexpectedlyUnsolved()).isEmpty();
    assertThat(result.matchesDeclaredStates()).isTrue();
    assertThat(result.acceptedDeviations())
        .extracting(ExpectedStateAudit.AcceptedDeviation::id)
        .containsExactly("known-but-solved");
    assertThat(ExpectedStateAudit.renderMarkdown(result))
        .contains("Keine unerwartete Abweichung")
        .contains("Erwartete, im Datensatz begründete Abweichungen")
        .contains("known-but-solved");
  }

  /** An exception only applies while the case actually deviates — it never hides agreement. */
  @Test
  void doesNotListAnExceptionWhenTheCaseBehavesAsDeclared() {
    var result =
        ExpectedStateAudit.evaluate(
            List.of(
                stateWithException(
                    "gap", "metadata_filter", ExpectedState.KNOWN_GAP, false, "Ausnahmegrund")));

    assertThat(result.acceptedDeviations()).isEmpty();
    assertThat(result.matchesDeclaredStates()).isTrue();
  }

  /** The Markdown block is what reaches the job summary, PR comment and alert issue (§5). */
  @Test
  void markdownCarriesThePerClassTableAndBothDeviationDirections() {
    var result =
        ExpectedStateAudit.evaluate(
            List.of(
                state("gap-now-solved", "multi_hop", ExpectedState.KNOWN_GAP, true),
                state("was-solved", "compound_word", ExpectedState.SOLVED, false)));

    String markdown = ExpectedStateAudit.renderMarkdown(result);

    assertThat(markdown)
        .contains("### Zustandsfelder")
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
    assertThat(ExpectedStateAudit.evaluate(List.of(state("a", "cat", null, true)))).isNull();
    assertThat(ExpectedStateAudit.renderSummary(null))
        .contains("nicht deklariert")
        .doesNotContain("Keine unerwartete Abweichung");
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
  void summaryNamesBothDeviationDirections() {
    var result =
        ExpectedStateAudit.evaluate(
            List.of(
                state("gap-now-solved", "multi_hop", ExpectedState.KNOWN_GAP, true),
                state("was-solved", "multi_hop", ExpectedState.SOLVED, false)));

    String summary = ExpectedStateAudit.renderSummary(result);

    assertThat(summary).contains("gap-now-solved").contains("was-solved");
    assertThat(summary).contains("ALS known_gap GEFÜHRT, ABER GELÖST");
    assertThat(summary).contains("ALS solved GEFÜHRT, ABER NICHT GELÖST");
  }
}
