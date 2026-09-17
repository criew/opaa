package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.types.ChatNoteItemKind;
import io.opaa.chat.ChatNoteCandidate;
import io.opaa.chat.ChatNoteList;
import io.opaa.eval.ConversationEvaluationReport.ConversationRunConfiguration;
import io.opaa.query.answer.CaffeineChatMemoryRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Docker-free proof of the two properties the multi-turn path is built on (issue #1484): a turn is
 * run against the window the preceding turns produced, and a case counts as solved only when every
 * one of its turns is.
 *
 * <p>The conversation memory here is the production {@link MessageWindowChatMemory} over the
 * production {@link CaffeineChatMemoryRepository} - the same pair the application wires - with a
 * deliberately small window in the eviction test, so the window <b>logic</b> is production's while
 * the test does not need twenty scripted turns to reach its edge.
 */
class ConversationRetrievalEvaluatorTest {

  private static final String DOC_A = "verwaltung-0001_a.md";
  private static final String DOC_B = "verwaltung-0002_b.md";
  private static final String DOC_C = "verwaltung-0003_c.md";

  private static ChatMemory chatMemory(int maxMessages) {
    return MessageWindowChatMemory.builder()
        .chatMemoryRepository(new CaffeineChatMemoryRepository(new SimpleMeterRegistry()))
        .maxMessages(maxMessages)
        .build();
  }

  private static ConversationCase twoTurnCase(String category) {
    return new ConversationCase(
        "verw-conv-001",
        "verwaltung",
        category,
        List.of(
            new ConversationCase.Turn(
                "Was kostet ein Anwohnerparkausweis?",
                "Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr.",
                List.of(DOC_A),
                null),
            new ConversationCase.Turn(
                "Und bei Bedürftigkeit?", "Dann entfällt die Gebühr.", List.of(DOC_B), null)),
        null,
        GoldenCase.ExpectedState.KNOWN_GAP,
        "2026-09-11",
        "Der Folgefragen-Pfad der Zerlegung ist noch ungemessen.");
  }

  /** Records the window every turn was invoked with, and answers with a scripted ranking. */
  private static final class RecordingPipeline
      implements ConversationRetrievalEvaluator.TurnInvocation {

    private final Map<String, List<Message>> windows = new LinkedHashMap<>();
    private final Map<String, List<String>> notes = new LinkedHashMap<>();
    private final List<List<String>> rankings;
    private int call;

    private RecordingPipeline(List<List<String>> rankings) {
      this.rankings = rankings;
    }

    @Override
    public ConversationRetrievalEvaluator.TurnInvocationResult invoke(
        ConversationCase conversationCase,
        int turnIndex,
        List<Message> conversationWindow,
        List<String> conversationNote) {
      windows.put(conversationCase.turnId(turnIndex), conversationWindow);
      notes.put(conversationCase.turnId(turnIndex), conversationNote);
      return new ConversationRetrievalEvaluator.TurnInvocationResult(
          rankings.get(call++), List.of("Teilfrage " + (turnIndex + 1)));
    }
  }

  @Test
  void secondTurnIsRunAgainstTheWindowTheFirstTurnProduced() {
    ConversationCase conversationCase = twoTurnCase("anaphora_resolution");
    RecordingPipeline pipeline = new RecordingPipeline(List.of(List.of(DOC_A), List.of(DOC_B)));

    ConversationRetrievalEvaluator.evaluateCase(conversationCase, chatMemory(20), pipeline);

    assertThat(pipeline.windows.get("verw-conv-001#1"))
        .as("the first turn of a case has no conversation yet")
        .isEmpty();
    List<Message> secondWindow = pipeline.windows.get("verw-conv-001#2");
    assertThat(secondWindow).hasSize(2);
    assertThat(secondWindow.get(0).getMessageType()).isEqualTo(MessageType.USER);
    assertThat(secondWindow.get(0).getText()).isEqualTo("Was kostet ein Anwohnerparkausweis?");
    assertThat(secondWindow.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
    assertThat(secondWindow.get(1).getText())
        .as("the hand-written short answer is the assistant half of the window")
        .isEqualTo("Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr.");
  }

  /** The production window evicts; the harness never re-implements that, it only observes it. */
  @Test
  void theWindowIsBoundedByTheProductionMemoryNotByTheHarness() {
    ConversationCase threeTurns =
        new ConversationCase(
            "verw-conv-003",
            "verwaltung",
            "topic_switch",
            List.of(
                new ConversationCase.Turn("Frage 1?", "Antwort 1.", List.of(DOC_A), null),
                new ConversationCase.Turn("Frage 2?", "Antwort 2.", List.of(DOC_B), null),
                new ConversationCase.Turn("Frage 3?", "Antwort 3.", List.of(DOC_C), null)),
            3,
            GoldenCase.ExpectedState.KNOWN_GAP,
            "2026-09-11",
            "Grund");
    RecordingPipeline pipeline =
        new RecordingPipeline(List.of(List.of(DOC_A), List.of(DOC_B), List.of(DOC_C)));

    ConversationRetrievalEvaluator.evaluateCase(threeTurns, chatMemory(2), pipeline);

    assertThat(pipeline.windows.get("verw-conv-003#3"))
        .as("a two-message window has evicted the first turn by the third one")
        .extracting(Message::getText)
        .containsExactly("Frage 2?", "Antwort 2.");
  }

  @Test
  void eachCaseRunsUnderItsOwnConversationSoNoCaseInheritsAnothersWindow() {
    ChatMemory shared = chatMemory(20);
    ConversationCase first = twoTurnCase("anaphora_resolution");
    ConversationCase second =
        new ConversationCase(
            "verw-conv-002",
            "verwaltung",
            "anaphora_resolution",
            first.turns(),
            null,
            GoldenCase.ExpectedState.KNOWN_GAP,
            "2026-09-11",
            "Grund");
    RecordingPipeline pipeline =
        new RecordingPipeline(
            List.of(List.of(DOC_A), List.of(DOC_B), List.of(DOC_A), List.of(DOC_B)));

    ConversationRetrievalEvaluator.evaluateAll(List.of(first, second), shared, pipeline);

    assertThat(pipeline.windows.get("verw-conv-002#1")).isEmpty();
  }

  @Test
  void aCaseIsSolvedOnlyWhenEveryTurnIsSolved() {
    ConversationCase conversationCase = twoTurnCase("anaphora_resolution");

    ConversationRetrievalEvaluator.CaseOutcome bothSolved =
        ConversationRetrievalEvaluator.evaluateCase(
            conversationCase,
            chatMemory(20),
            new RecordingPipeline(List.of(List.of(DOC_A), List.of(DOC_B))));
    assertThat(bothSolved.solved()).isTrue();

    // Second turn: the expected document is in the window, but not on rank 1 - the follow-up was
    // not resolved, and the existing solved criterion says so.
    ConversationRetrievalEvaluator.CaseOutcome secondTurnUnsolved =
        ConversationRetrievalEvaluator.evaluateCase(
            conversationCase,
            chatMemory(20),
            new RecordingPipeline(List.of(List.of(DOC_A), List.of(DOC_C, DOC_B))));
    assertThat(secondTurnUnsolved.turns().get(0).solved()).isTrue();
    assertThat(secondTurnUnsolved.turns().get(1).solved()).isFalse();
    assertThat(secondTurnUnsolved.solved())
        .as("one unsolved turn is enough to make the whole case unsolved")
        .isFalse();
  }

  @Test
  void theReportGroupsTurnsByCaseClassAndByTurnNumber() {
    ConversationCase conversationCase = twoTurnCase("anaphora_resolution");
    List<ConversationRetrievalEvaluator.CaseOutcome> outcomes =
        List.of(
            ConversationRetrievalEvaluator.evaluateCase(
                conversationCase,
                chatMemory(20),
                new RecordingPipeline(List.of(List.of(DOC_A), List.of(DOC_C, DOC_B)))));

    ConversationEvaluationReport report =
        ConversationRetrievalEvaluator.report(outcomes, runConfiguration());

    assertThat(report.overall().n()).isEqualTo(2);
    assertThat(report.byCategory()).containsOnlyKeys("anaphora_resolution");
    assertThat(report.byTurn()).containsOnlyKeys("1", "2");
    assertThat(report.byTurn().get("1").hitRateAt5()).isEqualTo(1.0);
    assertThat(report.byTurn().get("2").mrrAt8())
        .as("the follow-up turn found its document only on rank 2")
        .isEqualTo(0.5);
    assertThat(report.caseOutcomes().cases()).isEqualTo(1);
    assertThat(report.caseOutcomes().solvedCases()).isZero();
    assertThat(report.cases().getFirst().turns().getFirst().subQueries())
        .containsExactly("Teilfrage 1");
    assertThat(report.cases().getFirst().turns().get(1).conversationWindowMessages()).isEqualTo(2);
  }

  @Test
  void topicBleedCountsPreviousTopicDocumentsInTheWindowOfTheChangeTurn() {
    ConversationCase switchCase =
        new ConversationCase(
            "verw-conv-010",
            "verwaltung",
            "topic_switch",
            List.of(
                new ConversationCase.Turn("Thema A?", "Antwort A.", List.of(DOC_A), null),
                new ConversationCase.Turn("Vertiefung A?", "Antwort A2.", List.of(DOC_A), null),
                new ConversationCase.Turn(
                    "Ganz anderes Thema?", "Antwort B.", List.of(DOC_B), null)),
            3,
            GoldenCase.ExpectedState.KNOWN_GAP,
            "2026-09-11",
            "Themen-Bleed nach einem Wechsel ist ungemessen.");
    // The change turn's window still holds the old topic's document.
    List<ConversationRetrievalEvaluator.CaseOutcome> outcomes =
        List.of(
            ConversationRetrievalEvaluator.evaluateCase(
                switchCase,
                chatMemory(20),
                new RecordingPipeline(
                    List.of(List.of(DOC_A), List.of(DOC_A), List.of(DOC_B, DOC_A)))));

    ConversationEvaluationReport report =
        ConversationRetrievalEvaluator.report(outcomes, runConfiguration());

    assertThat(report.topicBleed()).isNotNull();
    assertThat(report.topicBleed().switchTurns()).isEqualTo(1);
    assertThat(report.topicBleed().bleedingSwitchTurns()).isEqualTo(1);
    assertThat(report.topicBleed().bledDocuments()).isEqualTo(1);
    assertThat(report.topicBleed().byTurn().getFirst().turnId()).isEqualTo("verw-conv-010#3");
    assertThat(report.topicBleed().byTurn().getFirst().bledDocuments()).containsExactly(DOC_A);
    assertThat(report.cases().getFirst().turns().get(2).bledDocuments()).containsExactly(DOC_A);
  }

  /**
   * The state audit has to be able to speak on this path: a {@code known_gap} case a run solves is
   * the finding the whole epic is measured by, and a {@code solved} case that stops being solved is
   * a regression. The single-pathedness of this measurement sits on the report ({@link
   * ConversationEvaluationReport#SINGLE_PATH_NOTE}), and the audit points at the flat field.
   */
  @Test
  void aKnownGapCaseTheRunSolvesIsReportedAsAFinding() {
    List<ConversationRetrievalEvaluator.CaseOutcome> outcomes =
        List.of(
            ConversationRetrievalEvaluator.evaluateCase(
                twoTurnCase("anaphora_resolution"),
                chatMemory(20),
                new RecordingPipeline(List.of(List.of(DOC_A), List.of(DOC_B)))));

    ConversationEvaluationReport report =
        ConversationRetrievalEvaluator.report(outcomes, runConfiguration());

    assertThat(report.singlePathNote()).isEqualTo(ConversationEvaluationReport.SINGLE_PATH_NOTE);
    assertThat(report.expectedStateAudit().unexpectedlySolved()).containsExactly("verw-conv-001");
    assertThat(report.expectedStateAudit().matchesDeclaredStates()).isFalse();
    assertThat(report.expectedStateAudit().stateField()).isEqualTo("expected_state");
  }

  /**
   * Only the named change turn is measured. The specification's own example - "Und bei
   * Bedürftigkeit?" after "Was kostet ein Anwohnerparkausweis?" - is a follow-up whose target sits
   * in another document; counting it as a change would report the correct previous-topic document
   * as bleed, in the very number a topic-switch change is judged by.
   */
  @Test
  void aFollowUpWithADifferentTargetDocumentIsNotCountedAsATopicChange() {
    ConversationCase switchCase =
        new ConversationCase(
            "verw-conv-011",
            "verwaltung",
            "topic_switch",
            List.of(
                new ConversationCase.Turn(
                    "Was kostet ein Anwohnerparkausweis?", "30,70 Euro.", List.of(DOC_A), null),
                new ConversationCase.Turn(
                    "Und bei Bedürftigkeit?", "Dann entfällt sie.", List.of(DOC_B), null),
                new ConversationCase.Turn(
                    "Wie hoch ist die Hundesteuer?", "120 Euro.", List.of(DOC_C), null)),
            3,
            GoldenCase.ExpectedState.KNOWN_GAP,
            "2026-09-11",
            "Grund");
    // Turn 2 expects a document no earlier turn expects, and turn 1's document stands in its
    // window - a derivation would book that as bleed.
    List<ConversationRetrievalEvaluator.CaseOutcome> outcomes =
        List.of(
            ConversationRetrievalEvaluator.evaluateCase(
                switchCase,
                chatMemory(20),
                new RecordingPipeline(
                    List.of(List.of(DOC_A), List.of(DOC_B, DOC_A), List.of(DOC_C)))));

    ConversationEvaluationReport report =
        ConversationRetrievalEvaluator.report(outcomes, runConfiguration());

    assertThat(report.topicBleed().switchTurns())
        .as("exactly the one turn the case names, never every non-overlapping turn")
        .isEqualTo(1);
    assertThat(report.topicBleed().bledDocuments()).isZero();
    assertThat(report.cases().getFirst().turns().get(1).bledDocuments())
        .as("the follow-up turn is not a change turn and is never attributed bleed")
        .isEmpty();
  }

  // ---------------------------------------------------------------------------------------
  // Gesprächsnotiz (#1487)
  // ---------------------------------------------------------------------------------------

  /**
   * The note's timing, mirroring production: a point condensed from turn 1's question reaches turn
   * 2, never turn 1 itself - the condensation runs after the answer.
   */
  @Test
  void aPointCondensedFromATurnReachesTheNextTurnAndNotItsOwn() {
    ConversationCase conversationCase = twoTurnCase("constraint_carryover");
    RecordingPipeline pipeline = new RecordingPipeline(List.of(List.of(DOC_A), List.of(DOC_B)));

    ConversationRetrievalEvaluator.evaluateCase(
        conversationCase,
        chatMemory(20),
        pipeline,
        userMessage -> List.of(new ChatNoteCandidate("Bezugsjahr 2024", ChatNoteItemKind.RAHMEN)),
        10);

    assertThat(pipeline.notes.get("verw-conv-001#1")).isEmpty();
    assertThat(pipeline.notes.get("verw-conv-001#2")).containsExactly("Bezugsjahr 2024");
  }

  /**
   * The report carries what each turn actually received (#1490). Without it a run cannot tell "the
   * note never carried the Angabe" from "the decomposition ignored it" - the two explanations of a
   * fallen constraint_carryover that call for opposite follow-up work.
   */
  @Test
  void theReportRecordsTheNoteEachTurnReceived() {
    ConversationCase conversationCase = twoTurnCase("constraint_carryover");
    RecordingPipeline pipeline = new RecordingPipeline(List.of(List.of(DOC_A), List.of(DOC_B)));

    ConversationRetrievalEvaluator.CaseOutcome outcome =
        ConversationRetrievalEvaluator.evaluateCase(
            conversationCase,
            chatMemory(20),
            pipeline,
            userMessage ->
                List.of(new ChatNoteCandidate("Bezugsjahr 2024", ChatNoteItemKind.RAHMEN)),
            10);

    ConversationEvaluationReport.ConversationCaseResult caseResult =
        ConversationRetrievalEvaluator.report(List.of(outcome), runConfiguration())
            .cases()
            .getFirst();
    assertThat(caseResult.turns().get(0).conversationNote()).isEmpty();
    assertThat(caseResult.turns().get(1).conversationNote()).containsExactly("Bezugsjahr 2024");
  }

  /** Only RAHMEN points reach the decomposition - the same filter production applies. */
  @Test
  void anAntwortformPointNeverReachesTheDecomposition() {
    RecordingPipeline pipeline = new RecordingPipeline(List.of(List.of(DOC_A), List.of(DOC_B)));

    ConversationRetrievalEvaluator.evaluateCase(
        twoTurnCase("constraint_carryover"),
        chatMemory(20),
        pipeline,
        userMessage ->
            List.of(
                new ChatNoteCandidate("Möchte knappe Antworten", ChatNoteItemKind.ANTWORTFORM),
                new ChatNoteCandidate("Bezugsjahr 2024", ChatNoteItemKind.RAHMEN)),
        10);

    assertThat(pipeline.notes.get("verw-conv-001#2")).containsExactly("Bezugsjahr 2024");
  }

  /** The cap is the production one ({@link ChatNoteList}): the oldest point falls out first. */
  @Test
  void theNoteIsCappedAndDropsItsOldestPointFirst() {
    ConversationCase threeTurns =
        new ConversationCase(
            "verw-conv-004",
            "verwaltung",
            "constraint_carryover",
            List.of(
                new ConversationCase.Turn("Frage 1?", "Antwort 1.", List.of(DOC_A), null),
                new ConversationCase.Turn("Frage 2?", "Antwort 2.", List.of(DOC_B), null),
                new ConversationCase.Turn("Frage 3?", "Antwort 3.", List.of(DOC_C), null)),
            null,
            GoldenCase.ExpectedState.KNOWN_GAP,
            "2026-09-11",
            "Grund");
    RecordingPipeline pipeline =
        new RecordingPipeline(List.of(List.of(DOC_A), List.of(DOC_B), List.of(DOC_C)));

    ConversationRetrievalEvaluator.evaluateCase(
        threeTurns,
        chatMemory(20),
        pipeline,
        userMessage ->
            List.of(new ChatNoteCandidate("Angabe zu " + userMessage, ChatNoteItemKind.RAHMEN)),
        1);

    assertThat(pipeline.notes.get("verw-conv-004#2")).containsExactly("Angabe zu Frage 1?");
    assertThat(pipeline.notes.get("verw-conv-004#3"))
        .as("the cap of one drops the point of turn 1 when turn 2's point arrives")
        .containsExactly("Angabe zu Frage 2?");
  }

  /** A point the note already holds is not appended twice - the production deduplication. */
  @Test
  void aRepeatedPointDoesNotEnterTheNoteTwice() {
    ConversationCase conversationCase = twoTurnCase("constraint_carryover");
    RecordingPipeline pipeline = new RecordingPipeline(List.of(List.of(DOC_A), List.of(DOC_B)));

    ConversationRetrievalEvaluator.evaluateCase(
        conversationCase,
        chatMemory(20),
        pipeline,
        userMessage -> List.of(new ChatNoteCandidate("Bezugsjahr 2024", ChatNoteItemKind.RAHMEN)),
        10);

    assertThat(pipeline.notes.get("verw-conv-001#2")).containsExactly("Bezugsjahr 2024");
  }

  /**
   * The harness reproduces production's defensive catch, not only its prompt: a failing
   * condensation costs that turn its points and nothing else. Without it a single transient model
   * error in one turn of one case would discard the whole multi-turn measurement - three runs of
   * the dataset under the Mehrfachlauf-Regel.
   *
   * <p>And it must <b>say so</b>: a silent catch would let a run whose every condensation failed
   * report a complete measurement while still declaring its note cap as a checked fixed point - the
   * comparator would hold it comparable, and the numbers would read as evidence that the note does
   * not help.
   */
  @Test
  void aFailingCondensationCostsItsTurnButNeverTheRun() {
    ConversationCase threeTurns =
        new ConversationCase(
            "verw-conv-006",
            "verwaltung",
            "constraint_carryover",
            List.of(
                new ConversationCase.Turn("Frage 1?", "Antwort 1.", List.of(DOC_A), null),
                new ConversationCase.Turn("Frage 2?", "Antwort 2.", List.of(DOC_B), null),
                new ConversationCase.Turn("Frage 3?", "Antwort 3.", List.of(DOC_C), null)),
            null,
            GoldenCase.ExpectedState.KNOWN_GAP,
            "2026-09-11",
            "Grund");
    RecordingPipeline pipeline =
        new RecordingPipeline(List.of(List.of(DOC_A), List.of(DOC_B), List.of(DOC_C)));

    ConversationRetrievalEvaluator.CaseOutcome outcome =
        ConversationRetrievalEvaluator.evaluateCase(
            threeTurns,
            chatMemory(20),
            pipeline,
            userMessage -> {
              if ("Frage 2?".equals(userMessage)) {
                throw new IllegalStateException("Modell nicht erreichbar");
              }
              return List.of(
                  new ChatNoteCandidate("Angabe zu " + userMessage, ChatNoteItemKind.RAHMEN));
            },
            10);

    assertThat(outcome.turns()).as("every turn of the case was still measured").hasSize(3);
    assertThat(pipeline.notes.get("verw-conv-006#3"))
        .as("the failed turn contributes nothing and is not caught up on")
        .containsExactly("Angabe zu Frage 1?");

    var audit =
        ConversationRetrievalEvaluator.report(List.of(outcome), runConfiguration())
            .noteCondensation();
    assertThat(audit.attemptedCondensations()).isEqualTo(3);
    assertThat(audit.failedCondensations()).isEqualTo(1);
    assertThat(audit.failedTurnIds())
        .as("the report names the turn, so a run without a note cannot look like a clean one")
        .containsExactly("verw-conv-006#2");
  }

  /** A run measured without a note reports an absent section, not a clean one. */
  @Test
  void aRunWithoutANoteHasNoCondensationSection() {
    ConversationRetrievalEvaluator.CaseOutcome outcome =
        ConversationRetrievalEvaluator.evaluateCase(
            twoTurnCase("anaphora_resolution"),
            chatMemory(20),
            new RecordingPipeline(List.of(List.of(DOC_A), List.of(DOC_B))));

    assertThat(
            ConversationRetrievalEvaluator.report(List.of(outcome), runConfiguration())
                .noteCondensation())
        .isNull();
  }

  /** Every condensation succeeding is reported as such, not by the section's absence. */
  @Test
  void aRunWhoseCondensationsAllSucceedReportsZeroFailures() {
    ConversationRetrievalEvaluator.CaseOutcome outcome =
        ConversationRetrievalEvaluator.evaluateCase(
            twoTurnCase("constraint_carryover"),
            chatMemory(20),
            new RecordingPipeline(List.of(List.of(DOC_A), List.of(DOC_B))),
            userMessage ->
                List.of(new ChatNoteCandidate("Angabe zu " + userMessage, ChatNoteItemKind.RAHMEN)),
            10);

    var audit =
        ConversationRetrievalEvaluator.report(List.of(outcome), runConfiguration())
            .noteCondensation();
    assertThat(audit.attemptedCondensations()).isEqualTo(2);
    assertThat(audit.failedCondensations()).isZero();
    assertThat(audit.failedTurnIds()).isEmpty();
  }

  /** Each case starts from an empty note, exactly as each starts from an empty window. */
  @Test
  void noCaseInheritsAnotherCasesNote() {
    ConversationCase first = twoTurnCase("constraint_carryover");
    ConversationCase second =
        new ConversationCase(
            "verw-conv-005",
            "verwaltung",
            "constraint_carryover",
            first.turns(),
            null,
            GoldenCase.ExpectedState.KNOWN_GAP,
            "2026-09-11",
            "Grund");
    RecordingPipeline pipeline =
        new RecordingPipeline(
            List.of(List.of(DOC_A), List.of(DOC_B), List.of(DOC_A), List.of(DOC_B)));

    ConversationRetrievalEvaluator.evaluateAll(
        List.of(first, second),
        chatMemory(20),
        pipeline,
        userMessage -> List.of(new ChatNoteCandidate("Bezugsjahr 2024", ChatNoteItemKind.RAHMEN)),
        10);

    assertThat(pipeline.notes.get("verw-conv-005#1")).isEmpty();
  }

  private static ConversationCase caseWithAMessageWithoutSearch() {
    return new ConversationCase(
        "verw-conv-ac-001",
        "verwaltung",
        "answer_continuation",
        List.of(
            new ConversationCase.Turn(
                "Was kostet ein Anwohnerparkausweis?",
                "Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr.",
                List.of(DOC_A),
                null),
            new ConversationCase.Turn(
                "Antworte bitte kürzer.", "Gern, ich fasse mich kürzer.", List.of(), null, false),
            new ConversationCase.Turn(
                "Und bei Bedürftigkeit?", "Dann entfällt die Gebühr.", List.of(DOC_B), null)),
        null,
        GoldenCase.ExpectedState.KNOWN_GAP,
        "2026-09-17",
        "Grund");
  }

  /**
   * Answers every turn with {@code DOC_A}/{@code DOC_B}. The second turn is searched either way - a
   * run always searches - and is judged to need a search or not.
   */
  private static ConversationRetrievalEvaluator.TurnInvocation pipelineJudgingTheSecondTurn(
      boolean secondTurnNeedsSearch) {
    return (conversationCase, turnIndex, window, note) -> {
      if (turnIndex == 1) {
        return new ConversationRetrievalEvaluator.TurnInvocationResult(
            List.of(DOC_C),
            List.of("Was kostet ein Anwohnerparkausweis? Antworte bitte kürzer."),
            secondTurnNeedsSearch);
      }
      return new ConversationRetrievalEvaluator.TurnInvocationResult(
          List.of(turnIndex == 0 ? DOC_A : DOC_B), List.of("Teilfrage " + (turnIndex + 1)), true);
    };
  }

  @Test
  void aTurnWithoutSearchIsSolvedExactlyWhenTheRunJudgedItToNeedNone() {
    ConversationRetrievalEvaluator.CaseOutcome judgedWithoutSearch =
        ConversationRetrievalEvaluator.evaluateCase(
            caseWithAMessageWithoutSearch(), chatMemory(20), pipelineJudgingTheSecondTurn(false));
    ConversationRetrievalEvaluator.CaseOutcome judgedAsSearch =
        ConversationRetrievalEvaluator.evaluateCase(
            caseWithAMessageWithoutSearch(), chatMemory(20), pipelineJudgingTheSecondTurn(true));

    assertThat(judgedWithoutSearch.turns().get(1).solved())
        .as("searched with the fallback all the same, which is not what the turn is judged by")
        .isTrue();
    assertThat(judgedWithoutSearch.solved()).isTrue();
    assertThat(judgedAsSearch.turns().get(1).solved())
        .as("a message with nothing to look up that was judged to need a search")
        .isFalse();
    assertThat(judgedAsSearch.solved()).isFalse();
  }

  @Test
  void aTurnWithoutSearchEntersNoMetricAggregateAndIsReportedOnItsOwn() {
    ConversationEvaluationReport report =
        ConversationRetrievalEvaluator.report(
            List.of(
                ConversationRetrievalEvaluator.evaluateCase(
                    caseWithAMessageWithoutSearch(),
                    chatMemory(20),
                    pipelineJudgingTheSecondTurn(true))),
            runConfiguration());

    assertThat(report.overall().n()).isEqualTo(2);
    assertThat(report.byCategory().get("answer_continuation").n()).isEqualTo(2);
    assertThat(report.byTurn()).containsOnlyKeys("1", "3");
    assertThat(report.overall().hitRateAt5())
        .as("the searched message ranks no expected document, and counts for nothing here")
        .isEqualTo(1.0);
    assertThat(report.noSearch().noSearchTurns()).isEqualTo(1);
    assertThat(report.noSearch().noSearchTurnIdsJudgedAsSearch())
        .containsExactly("verw-conv-ac-001#2");
    assertThat(report.noSearch().searchTurnIdsJudgedAsNoSearch()).isEmpty();
    ConversationEvaluationReport.TurnResult messageTurn = report.cases().getFirst().turns().get(1);
    assertThat(messageTurn.searchExpected()).isFalse();
    assertThat(messageTurn.searchNeeded()).isTrue();
    assertThat(messageTurn.hitRateAt5()).isNull();
  }

  /**
   * Regression guard for #1684: a question judged to need no search is still searched and may score
   * like any other turn - so the misjudgement has to be counted on its own, or it is invisible. It
   * is counted in a dataset without a single turn without search, too.
   */
  @Test
  void aQuestionJudgedToNeedNoSearchIsCountedAlthoughItsTurnScores() {
    ConversationEvaluationReport report =
        ConversationRetrievalEvaluator.report(
            List.of(
                ConversationRetrievalEvaluator.evaluateCase(
                    twoTurnCase("anaphora_resolution"),
                    chatMemory(20),
                    (conversationCase, turnIndex, window, note) ->
                        new ConversationRetrievalEvaluator.TurnInvocationResult(
                            List.of(turnIndex == 0 ? DOC_A : DOC_B),
                            List.of("Teilfrage " + (turnIndex + 1)),
                            turnIndex == 0))),
            runConfiguration());

    assertThat(report.cases().getFirst().solved())
        .as("the fallback search found the document")
        .isTrue();
    assertThat(report.noSearch().noSearchTurns()).isZero();
    assertThat(report.noSearch().searchTurnsJudgedAsNoSearch()).isEqualTo(1);
    assertThat(report.noSearch().searchTurnIdsJudgedAsNoSearch())
        .containsExactly("verw-conv-001#2");
    assertThat(ConversationReportWriter.renderSummary(report))
        .contains("Runden mit Suchbedarf, als „keine Suche“ eingestuft: 1 (verw-conv-001#2)");
    assertThat(ConversationReportWriter.renderMarkdown(report, null))
        .contains("Runden mit Suchbedarf, als „keine Suche“ eingestuft: 1 (verw-conv-001#2)");
  }

  /** Both directions stay visible when nothing was misjudged - a zero, not an absent section. */
  @Test
  void aRunWithoutMisjudgementReportsBothDirectionsAsZero() {
    ConversationEvaluationReport report =
        ConversationRetrievalEvaluator.report(
            List.of(
                ConversationRetrievalEvaluator.evaluateCase(
                    caseWithAMessageWithoutSearch(),
                    chatMemory(20),
                    pipelineJudgingTheSecondTurn(false))),
            runConfiguration());

    String summary = ConversationReportWriter.renderSummary(report);
    assertThat(summary)
        .contains("Runden ohne Suchbedarf: 1, davon als Suche eingestuft: 0")
        .contains("Runden mit Suchbedarf, als „keine Suche“ eingestuft: 0");
  }

  /** The chat model's temperature is reported with the run, as an observation next to the model. */
  @Test
  void theReportNamesTheTemperatureTheChatModelRanAt() {
    ConversationEvaluationReport report =
        ConversationRetrievalEvaluator.report(
            List.of(
                ConversationRetrievalEvaluator.evaluateCase(
                    twoTurnCase("anaphora_resolution"),
                    chatMemory(20),
                    new RecordingPipeline(List.of(List.of(DOC_A), List.of(DOC_B))))),
            runConfiguration());

    assertThat(report.runConfiguration().chatTemperature()).isEqualByComparingTo("0.7");
    assertThat(ConversationReportWriter.renderSummary(report))
        .contains("Chat-Modell=qwen2.5:1.5b-instruct bei Temperatur 0.7");
    assertThat(ConversationReportWriter.renderMarkdown(report, null))
        .contains("Chat-Modell `qwen2.5:1.5b-instruct` bei Temperatur 0.7");
  }

  @Test
  void withoutATopicSwitchCaseTheBleedSectionIsAbsentRatherThanClean() {
    List<ConversationRetrievalEvaluator.CaseOutcome> outcomes =
        List.of(
            ConversationRetrievalEvaluator.evaluateCase(
                twoTurnCase("anaphora_resolution"),
                chatMemory(20),
                new RecordingPipeline(List.of(List.of(DOC_A), List.of(DOC_B)))));

    assertThat(ConversationRetrievalEvaluator.report(outcomes, runConfiguration()).topicBleed())
        .isNull();
  }

  /** A run configuration is irrelevant to the assertions above; only its shape has to be valid. */
  private static ConversationRunConfiguration runConfiguration() {
    return new ConversationRunConfiguration(
        new PipelineEvaluationReport.PipelineRunConfiguration(
            "verwaltung",
            "ollama",
            "nomic-embed-text:v1.5",
            "digest",
            "ollama/ollama:0.6.5",
            "haswell",
            768,
            1000,
            true,
            200,
            60,
            8,
            0.3,
            "note",
            3,
            0.7,
            true,
            true,
            true,
            4,
            "qwen2.5:1.5b-instruct",
            5,
            8,
            "hnsw",
            "corpus",
            72,
            "eval/golden/verwaltung-conversations.json",
            "dataset",
            1,
            "markdown:3",
            "prefix",
            false,
            1,
            "scope",
            "2026-09-11T00:00:00Z",
            1.0,
            false),
        new ConversationMemoryProfile(
            20,
            ConversationMemoryProfile.SEARCH_WINDOW_QUESTION_ONLY,
            ConversationMemoryProfile.NO_CONVERSATION_NOTE),
        2,
        new BigDecimal("0.7"));
  }
}
