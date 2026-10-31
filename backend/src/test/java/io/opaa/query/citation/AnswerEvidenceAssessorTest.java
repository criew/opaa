package io.opaa.query.citation;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.EvidenceLevel;
import io.opaa.chat.AnswerEvidence;
import io.opaa.llm.AnswerCompleteness;
import io.opaa.query.citation.CitationValidator.ValidatedCitation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnswerEvidenceAssessorTest {

  private static ValidatedCitation valid(String doc, int chunk) {
    return new ValidatedCitation(doc, chunk, doc + ".md", true);
  }

  private static ValidatedCitation invalid(String doc, int chunk) {
    return new ValidatedCitation(doc, chunk, doc + ".md", false);
  }

  private static AnswerEvidence assess(
      List<ValidatedCitation> citations,
      List<String> queries,
      Map<String, Set<Integer>> attribution,
      AnswerCompleteness completeness) {
    return AnswerEvidenceAssessor.assess(
        citations, true, true, true, queries, attribution, completeness);
  }

  @Test
  void confirmedCitationsCoveringEveryQueryAreSupportedAndCountedDistinctly() {
    AnswerEvidence evidence =
        assess(
            List.of(valid("a", 1), valid("a", 1), valid("a", 2), valid("b", 0)),
            List.of("Kosten Anwohnerparkausweis"),
            Map.of(),
            AnswerCompleteness.COMPLETE);

    assertThat(evidence.level()).isEqualTo(EvidenceLevel.SUPPORTED);
    assertThat(evidence.confirmedPassages()).isEqualTo(3);
    assertThat(evidence.confirmedDocuments()).isEqualTo(2);
    assertThat(evidence.unconfirmedCitations()).isZero();
    assertThat(evidence.uncoveredSubQueries()).isEmpty();
  }

  @Test
  void anUnconfirmedCitationBesideAConfirmedOneIsPartial() {
    AnswerEvidence evidence =
        assess(
            List.of(valid("a", 1), invalid("x", 3), invalid("x", 3)),
            List.of("q"),
            Map.of(),
            AnswerCompleteness.COMPLETE);

    assertThat(evidence.level()).isEqualTo(EvidenceLevel.PARTIALLY_SUPPORTED);
    assertThat(evidence.unconfirmedCitations()).isEqualTo(1);
  }

  @Test
  void aSubQueryWithoutConfirmedCitationIsNamedAndMakesTheAnswerPartial() {
    AnswerEvidence evidence =
        assess(
            List.of(valid("a", 1)),
            List.of("Kosten Anwohnerparkausweis", "Gültigkeit Anwohnerparkausweis"),
            Map.of("a", Set.of(1), "b", Set.of(2)),
            AnswerCompleteness.COMPLETE);

    assertThat(evidence.level()).isEqualTo(EvidenceLevel.PARTIALLY_SUPPORTED);
    assertThat(evidence.uncoveredSubQueries()).containsExactly("Gültigkeit Anwohnerparkausweis");
  }

  @Test
  void aDocumentSelectedForBothSubQueriesCoversBoth() {
    AnswerEvidence evidence =
        assess(
            List.of(valid("a", 1)),
            List.of("q1", "q2"),
            Map.of("a", Set.of(1, 2)),
            AnswerCompleteness.COMPLETE);

    assertThat(evidence.level()).isEqualTo(EvidenceLevel.SUPPORTED);
  }

  @Test
  void aCutOffAnswerIsAtMostPartial() {
    AnswerEvidence evidence =
        assess(List.of(valid("a", 1)), List.of("q"), Map.of(), AnswerCompleteness.TRUNCATED);

    assertThat(evidence.level()).isEqualTo(EvidenceLevel.PARTIALLY_SUPPORTED);
    assertThat(evidence.truncated()).isTrue();
  }

  @Test
  void noConfirmedCitationIsUnsupportedEvenWithUnconfirmedOnes() {
    AnswerEvidence evidence =
        assess(List.of(invalid("x", 1)), List.of("q"), Map.of(), AnswerCompleteness.COMPLETE);

    assertThat(evidence.level()).isEqualTo(EvidenceLevel.UNSUPPORTED);
    assertThat(evidence.uncoveredSubQueries()).isEmpty();
  }

  @Test
  void anAnswerWithoutSearchedKnowledgeIsUnsupportedAndSaysSo() {
    AnswerEvidence evidence =
        AnswerEvidenceAssessor.assess(
            List.of(), false, false, true, List.of(), Map.of(), AnswerCompleteness.COMPLETE);

    assertThat(evidence.level()).isEqualTo(EvidenceLevel.UNSUPPORTED);
    assertThat(evidence.knowledgeSearched()).isFalse();
    assertThat(evidence.passagesFound()).isFalse();
  }

  @Test
  void nothingIsAssessedForAMessageWithoutAnythingToSearchOrForAnEmptyAnswer() {
    assertThat(
            AnswerEvidenceAssessor.assess(
                List.of(), true, true, false, List.of("q"), Map.of(), AnswerCompleteness.COMPLETE))
        .isNull();
    assertThat(assess(List.of(valid("a", 1)), List.of("q"), Map.of(), AnswerCompleteness.EMPTY))
        .isNull();
  }
}
