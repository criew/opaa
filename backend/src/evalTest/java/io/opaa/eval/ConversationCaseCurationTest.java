package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.eval.GoldenCaseCuration.Violation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Docker-free guard for the committed multi-turn dataset and for the rules themselves (issue #1484)
 * - part of {@code evalUnitTest} and therefore of {@code check}, so a hand-edited dataset that
 * drops an answer, a state field or a whole class fails on an ordinary build instead of an hour
 * into a Testcontainers run.
 */
class ConversationCaseCurationTest {

  private static final String DOMAIN = EvalDomainConfig.VERWALTUNG.name();

  /** The single-pathedness every multi-turn case has to record (Einpfad-Regel). */
  private static final String EINPFAD =
      "Mehrrunden-Fall: läuft konstruktionsbedingt nur auf dem Pipeline-Messpfad.";

  private static Path corpusDir() {
    return RepoPaths.evalDir().resolve("corpus").resolve(DOMAIN);
  }

  private static Set<String> corpusFileNames() throws IOException {
    return Set.copyOf(
        CorpusManifest.verify(corpusDir(), corpusDir().resolve("MANIFEST.sha256")).fileNames());
  }

  private static List<ConversationCase> committedCases() throws IOException {
    return ConversationDataset.load(ConversationDataset.file(EvalDomainConfig.VERWALTUNG));
  }

  /**
   * The committed dataset satisfies every rule as soon as it holds a case. It is empty until the
   * curation issue (#1485) fills it, and an empty file cannot satisfy the class-size rules - so an
   * empty dataset is asserted to be exactly that, empty and parseable, rather than run through
   * checks it is guaranteed to fail.
   */
  @Test
  void committedVerwaltungConversationDatasetSatisfiesEveryCurationRule() throws IOException {
    List<ConversationCase> cases = committedCases();
    if (cases.isEmpty()) {
      return;
    }
    List<Violation> violations =
        ConversationCaseCuration.validate(cases, DOMAIN, corpusFileNames());
    assertThat(violations)
        .as(
            "eval/golden/%s violates curation rules of docs/features/conversation-memory.md: %s",
            EvalDomainConfig.VERWALTUNG.conversationDatasetFileName(), violations)
        .isEmpty();
  }

  @Test
  void everyTurnMustCarryQueryAnswerAndExpectedDocuments() {
    ConversationCase incomplete =
        new ConversationCase(
            "verw-conv-001",
            DOMAIN,
            "anaphora_resolution",
            List.of(
                new ConversationCase.Turn("Frage 1?", "Antwort 1.", List.of("a.md"), null),
                new ConversationCase.Turn("Frage 2?", "  ", List.of(), null)),
            GoldenCase.ExpectedState.KNOWN_GAP,
            "2026-09-11",
            "Grund",
            EINPFAD);

    List<Violation> violations =
        ConversationCaseCuration.validate(List.of(incomplete), DOMAIN, Set.of("a.md"));

    assertThat(violations)
        .anyMatch(
            v ->
                "verw-conv-001#2".equals(v.caseId())
                    && "expected_documents must not be empty".equals(v.rule()));
    assertThat(violations)
        .anyMatch(
            v ->
                "verw-conv-001#2".equals(v.caseId())
                    && v.rule().startsWith("answer is missing or blank"));
  }

  @Test
  void theClassMustComeFromTheThreeNamedOnesAndBringItsOwnTurnCount() {
    ConversationCase wrongClass =
        new ConversationCase(
            "verw-conv-001",
            DOMAIN,
            "multi_hop",
            List.of(new ConversationCase.Turn("Frage?", "Antwort.", List.of("a.md"), null)),
            GoldenCase.ExpectedState.SOLVED,
            "2026-09-11",
            "Grund",
            EINPFAD);
    ConversationCase tooShort =
        new ConversationCase(
            "verw-conv-002",
            DOMAIN,
            "constraint_carryover",
            List.of(
                new ConversationCase.Turn("Frage 1?", "Antwort 1.", List.of("a.md"), "b.md"),
                new ConversationCase.Turn("Frage 2?", "Antwort 2.", List.of("b.md"), null)),
            GoldenCase.ExpectedState.SOLVED,
            "2026-09-11",
            "Grund",
            EINPFAD);

    List<Violation> violations =
        ConversationCaseCuration.validate(
            List.of(wrongClass, tooShort), DOMAIN, Set.of("a.md", "b.md"));

    assertThat(violations)
        .anyMatch(v -> "verw-conv-001".equals(v.caseId()) && v.rule().contains("is not one of"));
    assertThat(violations)
        .anyMatch(
            v ->
                "verw-conv-002".equals(v.caseId())
                    && v.rule().contains("is defined over 3 to 5 turns"));
  }

  @Test
  void everyCaseMustDeclareItsState() {
    ConversationCase stateless =
        new ConversationCase(
            "verw-conv-001",
            DOMAIN,
            "anaphora_resolution",
            List.of(
                new ConversationCase.Turn("Frage 1?", "Antwort 1.", List.of("a.md"), null),
                new ConversationCase.Turn("Frage 2?", "Antwort 2.", List.of("a.md"), null)),
            null,
            null,
            "   ",
            null);

    List<Violation> violations =
        ConversationCaseCuration.validate(List.of(stateless), DOMAIN, Set.of("a.md"));

    assertThat(violations)
        .extracting(Violation::rule)
        .contains(
            "expected_state is missing",
            "expected_state_since is missing",
            "expected_state_reason is missing or blank",
            ConversationCaseCuration.SINGLE_PATH_EXCEPTION_RULE);
  }

  /**
   * The Einpfad-Regel makes the exception mandatory even on a {@code solved} case - the opposite of
   * the single-question dataset's rule, where an exception on a solved case could only ever excuse
   * a regression.
   */
  @Test
  void aSolvedCaseAlsoHasToRecordItsSinglePathedness() {
    ConversationCase solvedWithException =
        new ConversationCase(
            "verw-conv-001",
            DOMAIN,
            "anaphora_resolution",
            List.of(
                new ConversationCase.Turn("Frage 1?", "Antwort 1.", List.of("a.md"), null),
                new ConversationCase.Turn("Frage 2?", "Antwort 2.", List.of("a.md"), null)),
            GoldenCase.ExpectedState.SOLVED,
            "2026-09-11",
            "Grund",
            EINPFAD);

    assertThat(
            ConversationCaseCuration.validate(List.of(solvedWithException), DOMAIN, Set.of("a.md")))
        .extracting(Violation::rule)
        .doesNotContain(
            ConversationCaseCuration.SINGLE_PATH_EXCEPTION_RULE,
            GoldenCaseCuration.EXCEPTION_ONLY_ON_KNOWN_GAP_RULE);
  }

  @Test
  void aConstraintCarryoverCaseWithoutAConfusableDocumentMeasuresNothing() {
    ConversationCase withoutConfusable =
        new ConversationCase(
            "verw-conv-001",
            DOMAIN,
            "constraint_carryover",
            List.of(
                new ConversationCase.Turn("Frage 1?", "Antwort 1.", List.of("a.md"), null),
                new ConversationCase.Turn("Frage 2?", "Antwort 2.", List.of("b.md"), null),
                new ConversationCase.Turn("Frage 3?", "Antwort 3.", List.of("a.md"), null)),
            GoldenCase.ExpectedState.KNOWN_GAP,
            "2026-09-11",
            "Grund",
            EINPFAD);

    assertThat(
            ConversationCaseCuration.validate(
                List.of(withoutConfusable), DOMAIN, Set.of("a.md", "b.md")))
        .extracting(Violation::rule)
        .contains(ConversationCaseCuration.CONFUSABLE_DOCUMENT_RULE);
  }

  @Test
  void everyClassNeedsItsMinimumOfCasesAndOfDistinctExpectedSets() {
    ConversationCase onlyCase =
        new ConversationCase(
            "verw-conv-001",
            DOMAIN,
            "anaphora_resolution",
            List.of(
                new ConversationCase.Turn("Frage 1?", "Antwort 1.", List.of("a.md"), null),
                new ConversationCase.Turn("Frage 2?", "Antwort 2.", List.of("a.md"), null)),
            GoldenCase.ExpectedState.KNOWN_GAP,
            "2026-09-11",
            "Grund",
            EINPFAD);

    List<Violation> violations =
        ConversationCaseCuration.validate(List.of(onlyCase), DOMAIN, Set.of("a.md"));

    assertThat(violations)
        .anyMatch(
            v -> v.caseId() == null && v.rule().contains("'anaphora_resolution' has 1 cases"));
    assertThat(violations)
        .anyMatch(v -> v.caseId() == null && v.rule().contains("'topic_switch' has 0 cases"));
    assertThat(violations)
        .anyMatch(
            v ->
                v.caseId() == null
                    && v.rule().contains("spans only 1 distinct expected_documents sets"));
  }
}
