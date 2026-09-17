package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.eval.GoldenCaseCuration.Violation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Docker-free guard for the committed multi-turn dataset and for the rules themselves (issue #1484)
 * - part of {@code evalUnitTest} and therefore of {@code check}, so a hand-edited dataset that
 * drops an answer, a state field or a whole class fails on an ordinary build instead of an hour
 * into a Testcontainers run.
 */
class ConversationCaseCurationTest {

  private static final String DOMAIN = EvalDomainConfig.VERWALTUNG.name();

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
            null,
            GoldenCase.ExpectedState.KNOWN_GAP,
            "2026-09-11",
            "Grund");

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
            null,
            GoldenCase.ExpectedState.SOLVED,
            "2026-09-11",
            "Grund");
    ConversationCase tooShort =
        new ConversationCase(
            "verw-conv-002",
            DOMAIN,
            "constraint_carryover",
            List.of(
                new ConversationCase.Turn("Frage 1?", "Antwort 1.", List.of("a.md"), "b.md"),
                new ConversationCase.Turn("Frage 2?", "Antwort 2.", List.of("b.md"), null)),
            null,
            GoldenCase.ExpectedState.SOLVED,
            "2026-09-11",
            "Grund");

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
            null,
            "   ");

    List<Violation> violations =
        ConversationCaseCuration.validate(List.of(stateless), DOMAIN, Set.of("a.md"));

    assertThat(violations)
        .extracting(Violation::rule)
        .contains(
            "expected_state is missing",
            "expected_state_since is missing",
            "expected_state_reason is missing or blank");
  }

  /**
   * This path measures one path only, so its flat state fields are that path's state and there is
   * no deviation to excuse. {@link ConversationCase} ignores unknown keys; a retired {@code
   * expected_state_exception} left in the dataset would be a statement nobody reads.
   */
  @Test
  void theCommittedDatasetCarriesNoRetiredExceptionField() throws IOException {
    JsonNode root =
        JsonMapper.builder()
            .build()
            .readTree(Files.readAllBytes(ConversationDataset.file(EvalDomainConfig.VERWALTUNG)));

    for (JsonNode conversationCase : root) {
      assertThat(conversationCase.has("expected_state_exception"))
          .as("%s carries expected_state_exception", conversationCase.get("id").asString())
          .isFalse();
    }
  }

  @Test
  void aTopicSwitchCaseNamesItsChangeTurnAndNoOtherClassCarriesOne() {
    ConversationCase withoutSwitchTurn =
        new ConversationCase(
            "verw-conv-001",
            DOMAIN,
            "topic_switch",
            List.of(
                new ConversationCase.Turn("Frage 1?", "Antwort 1.", List.of("a.md"), null),
                new ConversationCase.Turn("Frage 2?", "Antwort 2.", List.of("a.md"), null),
                new ConversationCase.Turn("Frage 3?", "Antwort 3.", List.of("b.md"), null)),
            null,
            GoldenCase.ExpectedState.KNOWN_GAP,
            "2026-09-11",
            "Grund");
    ConversationCase outsideItsScript =
        new ConversationCase(
            "verw-conv-002",
            DOMAIN,
            "topic_switch",
            withoutSwitchTurn.turns(),
            4,
            GoldenCase.ExpectedState.KNOWN_GAP,
            "2026-09-11",
            "Grund");
    ConversationCase wrongClassWithSwitchTurn =
        new ConversationCase(
            "verw-conv-003",
            DOMAIN,
            "anaphora_resolution",
            List.of(
                new ConversationCase.Turn("Frage 1?", "Antwort 1.", List.of("a.md"), null),
                new ConversationCase.Turn("Frage 2?", "Antwort 2.", List.of("b.md"), null)),
            2,
            GoldenCase.ExpectedState.KNOWN_GAP,
            "2026-09-11",
            "Grund");

    List<Violation> violations =
        ConversationCaseCuration.validate(
            List.of(withoutSwitchTurn, outsideItsScript, wrongClassWithSwitchTurn),
            DOMAIN,
            Set.of("a.md", "b.md"));

    assertThat(violations)
        .anyMatch(
            v ->
                "verw-conv-001".equals(v.caseId())
                    && ConversationCaseCuration.TOPIC_SWITCH_TURN_RULE.equals(v.rule()));
    assertThat(violations)
        .anyMatch(v -> "verw-conv-002".equals(v.caseId()) && v.rule().contains("outside [2, 3]"));
    assertThat(violations)
        .anyMatch(
            v ->
                "verw-conv-003".equals(v.caseId())
                    && v.rule().contains("only defined for the topic_switch class"));
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
            null,
            GoldenCase.ExpectedState.KNOWN_GAP,
            "2026-09-11",
            "Grund");

    assertThat(
            ConversationCaseCuration.validate(
                List.of(withoutConfusable), DOMAIN, Set.of("a.md", "b.md")))
        .extracting(Violation::rule)
        .contains(ConversationCaseCuration.CONFUSABLE_DOCUMENT_RULE);
  }

  private static final String LONG_ANSWER =
      "Die Gebühr beträgt 33,00 Euro. 【source: 3fa85f64-5717-4562-b3fc-2c963f66afa6#0 | a.md】 "
          + "x".repeat(ConversationCaseCuration.LONG_ANSWER_MINIMUM_LENGTH);

  private static ConversationCase continuationCase(String id, List<ConversationCase.Turn> turns) {
    return new ConversationCase(
        id,
        DOMAIN,
        "answer_continuation",
        turns,
        null,
        GoldenCase.ExpectedState.KNOWN_GAP,
        "2026-09-17",
        "Grund");
  }

  @Test
  void anAnswerContinuationCaseCarriesLongAnswersWithCitationMarkers() {
    ConversationCase shortAnswers =
        continuationCase(
            "verw-conv-ac-001",
            List.of(
                new ConversationCase.Turn("Frage 1?", "Antwort 1.", List.of("a.md"), null),
                new ConversationCase.Turn("Antworte kurz.", "Gern.", List.of(), null, false),
                new ConversationCase.Turn("Frage 3?", "x".repeat(600), List.of("a.md"), null)));
    ConversationCase longAnswers =
        continuationCase(
            "verw-conv-ac-002",
            List.of(
                new ConversationCase.Turn("Frage 1?", LONG_ANSWER, List.of("a.md"), null),
                new ConversationCase.Turn("Antworte kurz.", "Gern.", List.of(), null, false),
                new ConversationCase.Turn("Frage 3?", LONG_ANSWER, List.of("a.md"), null)));

    List<Violation> violations =
        ConversationCaseCuration.validate(
            List.of(shortAnswers, longAnswers), DOMAIN, Set.of("a.md"));

    assertThat(violations)
        .filteredOn(v -> ConversationCaseCuration.LONG_ANSWER_RULE.equals(v.rule()))
        .extracting(Violation::caseId)
        .as("too short, and long enough but without a citation marker")
        .containsExactly("verw-conv-ac-001#1", "verw-conv-ac-001#3");
  }

  @Test
  void aTurnWithoutSearchBelongsToAnAnswerContinuationCaseAndFollowsAWindow() {
    ConversationCase inOtherClass =
        new ConversationCase(
            "verw-conv-001",
            DOMAIN,
            "anaphora_resolution",
            List.of(
                new ConversationCase.Turn("Frage 1?", "Antwort 1.", List.of("a.md"), null),
                new ConversationCase.Turn("Danke.", "Gern.", List.of(), null, false)),
            null,
            GoldenCase.ExpectedState.KNOWN_GAP,
            "2026-09-11",
            "Grund");
    ConversationCase firstTurn =
        continuationCase(
            "verw-conv-ac-001",
            List.of(
                new ConversationCase.Turn(
                    "Ich bin Sachbearbeiterin.", "Gern.", List.of(), null, false),
                new ConversationCase.Turn("Frage 2?", LONG_ANSWER, List.of("a.md"), null),
                new ConversationCase.Turn("Frage 3?", LONG_ANSWER, List.of("a.md"), null)));
    ConversationCase withDocuments =
        continuationCase(
            "verw-conv-ac-002",
            List.of(
                new ConversationCase.Turn("Frage 1?", LONG_ANSWER, List.of("a.md"), null),
                new ConversationCase.Turn("Danke.", "Gern.", List.of("a.md"), null, false),
                new ConversationCase.Turn("Frage 3?", LONG_ANSWER, List.of("a.md"), null)));
    ConversationCase withoutAny =
        continuationCase(
            "verw-conv-ac-003",
            List.of(
                new ConversationCase.Turn("Frage 1?", LONG_ANSWER, List.of("a.md"), null),
                new ConversationCase.Turn("Frage 2?", LONG_ANSWER, List.of("a.md"), null),
                new ConversationCase.Turn("Frage 3?", LONG_ANSWER, List.of("a.md"), null)));

    List<Violation> violations =
        ConversationCaseCuration.validate(
            List.of(inOtherClass, firstTurn, withDocuments, withoutAny), DOMAIN, Set.of("a.md"));

    assertThat(violations)
        .filteredOn(v -> ConversationCaseCuration.NO_SEARCH_TURN_RULE.equals(v.rule()))
        .extracting(Violation::caseId)
        .containsExactlyInAnyOrder(
            "verw-conv-001#2", "verw-conv-ac-001#1", "verw-conv-ac-002#2", "verw-conv-ac-003");
    assertThat(violations)
        .as("a turn without search needs no expected documents")
        .noneMatch(
            v ->
                "verw-conv-001#2".equals(v.caseId())
                    && "expected_documents must not be empty".equals(v.rule()));
  }

  /**
   * Regression guard for #1684: a follow-up without a question mark - a condition, a noun phrase,
   * "und für ..." - is the question a decomposition most readily takes for a remark. Without such
   * turns that misjudgement cannot be measured.
   */
  @Test
  void theAnswerContinuationClassNeedsFollowUpsWithoutAQuestionMark() {
    ConversationCase onlyQuestionMarks =
        continuationCase(
            "verw-conv-ac-001",
            List.of(
                new ConversationCase.Turn("Frage 1?", LONG_ANSWER, List.of("a.md"), null),
                new ConversationCase.Turn("Danke.", "Gern.", List.of(), null, false),
                new ConversationCase.Turn(
                    "und wenn ich über 60 bin?", LONG_ANSWER, List.of("a.md"), null)));
    ConversationCase twoWithout =
        continuationCase(
            "verw-conv-ac-002",
            List.of(
                new ConversationCase.Turn("Frage ohne Zeichen", LONG_ANSWER, List.of("a.md"), null),
                new ConversationCase.Turn(
                    "wenn ich über 60 bin", LONG_ANSWER, List.of("a.md"), null),
                new ConversationCase.Turn("Danke.", "Gern.", List.of(), null, false),
                new ConversationCase.Turn("und für Rentner", LONG_ANSWER, List.of("a.md"), null)));

    List<Violation> violations =
        ConversationCaseCuration.validate(
            List.of(onlyQuestionMarks, twoWithout), DOMAIN, Set.of("a.md"));

    assertThat(violations)
        .as("the first turn is no follow-up, so two remain - one short of the minimum")
        .anyMatch(
            v ->
                v.caseId() == null
                    && ConversationCaseCuration.FOLLOW_UP_WITHOUT_QUESTION_MARK_RULE.equals(
                        v.rule()));
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
            null,
            GoldenCase.ExpectedState.KNOWN_GAP,
            "2026-09-11",
            "Grund");

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
