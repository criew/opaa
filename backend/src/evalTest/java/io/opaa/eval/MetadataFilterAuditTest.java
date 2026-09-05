package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Docker-free unit tests for the two filter error directions (issue #1070, Teil 2). */
class MetadataFilterAuditTest {

  private static MetadataFilterAudit.CaseInput filtered(
      String id, String confusable, String noValueField, List<String> ranked) {
    return new MetadataFilterAudit.CaseInput(
        id, true, confusable, noValueField, List.of(id + ".md"), ranked);
  }

  @Test
  void aConfusableDocumentStillInTheWindowIsFilterNotApplied() {
    var result =
        MetadataFilterAudit.evaluate(
            List.of(
                filtered("a", "a-2023.md", null, List.of("a.md", "a-2023.md")),
                filtered("b", "b-2023.md", null, List.of("b.md", "x.md"))));

    assertThat(result.filteredCases()).isEqualTo(2);
    assertThat(result.casesWithConfusable()).isEqualTo(2);
    assertThat(result.filterNotApplied()).containsExactly("a");
    assertThat(result.filterTooStrong()).isEmpty();
    assertThat(result.clean()).isFalse();
  }

  @Test
  void aMissingExpectedDocumentOfANoValueCaseIsFilterTooStrong() {
    var result =
        MetadataFilterAudit.evaluate(
            List.of(
                filtered("a", null, "documentDate", List.of("x.md")),
                filtered("b", null, "documentType", List.of("b.md"))));

    assertThat(result.noValueCases()).isEqualTo(2);
    assertThat(result.filterTooStrong()).containsExactly("a");
    assertThat(result.filterNotApplied()).isEmpty();
  }

  /** The two directions are separate counts: one cannot hide the other in a mean. */
  @Test
  void theTwoDirectionsAreReportedSeparately() {
    var result =
        MetadataFilterAudit.evaluate(
            List.of(
                filtered("a", "a-2023.md", null, List.of("a-2023.md", "a.md")),
                filtered("b", null, "documentDate", List.of("x.md"))));

    assertThat(result.filterNotApplied()).containsExactly("a");
    assertThat(result.filterTooStrong()).containsExactly("b");
    assertThat(MetadataFilterAudit.renderSummary(result))
        .contains("Filter greift nicht (Verwechslungspartner im Fenster): 1")
        .contains("Filter greift zu stark (erwartetes Dokument mit leerem Feld fehlt): 1");
    assertThat(MetadataFilterAudit.renderMarkdown(result)).contains("`a`").contains("`b`");
  }

  /** A case without a filter contributes nothing; a dataset without one has no audit at all. */
  @Test
  void unfilteredCasesAreIgnoredAndADatasetWithoutFiltersHasNoAudit() {
    var unfiltered =
        new MetadataFilterAudit.CaseInput(
            "a", false, "a-2023.md", null, List.of("a.md"), List.of("a-2023.md"));

    assertThat(MetadataFilterAudit.evaluate(List.of(unfiltered))).isNull();
    assertThat(MetadataFilterAudit.renderMarkdown(null)).isEmpty();
    assertThat(MetadataFilterAudit.renderSummary(null)).contains("kein Fall");
  }
}
