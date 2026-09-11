package io.opaa.eval;

import io.opaa.api.types.ChatNoteItemKind;
import io.opaa.chat.ChatNoteCandidate;
import io.opaa.chat.ChatNoteList;
import io.opaa.eval.ConversationEvaluationReport.CaseOutcomeSummary;
import io.opaa.eval.ConversationEvaluationReport.ClassOutcome;
import io.opaa.eval.ConversationEvaluationReport.ConversationCaseResult;
import io.opaa.eval.ConversationEvaluationReport.ConversationRunConfiguration;
import io.opaa.eval.ConversationEvaluationReport.SwitchTurnBleed;
import io.opaa.eval.ConversationEvaluationReport.TopicBleedAudit;
import io.opaa.eval.ConversationEvaluationReport.TurnResult;
import io.opaa.query.answer.ConversationWindowMessages;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Runs a multi-turn dataset turn by turn and assembles a {@link ConversationEvaluationReport}
 * (issue #1484, docs/features/conversation-memory.md, "Messung").
 *
 * <p><b>The conversation window is built by the production memory, not by this class.</b> Every
 * turn is appended to the {@link ChatMemory} bean a chat request uses - the question as a {@code
 * UserMessage}, the hand-written short answer through {@link ConversationWindowMessages#answer},
 * the same entrance a generated answer takes - and the window handed to the next turn is whatever
 * that memory returns. Window width, eviction order and citation-marker normalization are therefore
 * production behaviour by construction, not because a curated dataset happens to avoid them.
 *
 * <p><b>The Gespraechsnotiz is built by the production condensation, not scripted</b> (#1487):
 * after every turn, {@link NoteExtraction} condenses that turn's <em>question</em> - the harness
 * supplies {@code ChatNoteExtractionService#condense} - and {@link ChatNoteList} decides which
 * candidates enter and how many oldest points fall out, the same algebra the persisted note uses.
 * The next turn receives the {@code RAHMEN} points, which is all the sub-question decomposition
 * ever sees. The note is therefore a non-deterministic part of this path, like the decomposition
 * itself, and falls under the Mehrfachlauf-Regel.
 *
 * <p>Takes the retrieval itself as a {@link TurnInvocation} rather than depending on {@code
 * RetrievalPipeline}, for the same reason {@link PipelineRetrievalEvaluator} does: the harness
 * supplies the production run, while this class stays a Docker- and Spring-context-free unit
 * exercised by {@code ConversationRetrievalEvaluatorTest}.
 */
public final class ConversationRetrievalEvaluator {

  private static final Logger log = LoggerFactory.getLogger(ConversationRetrievalEvaluator.class);

  private ConversationRetrievalEvaluator() {}

  /** What one call into the pipeline produced for a turn. */
  public record TurnInvocationResult(List<String> rankedChunkFileNames, List<String> subQueries) {}

  /**
   * One turn's retrieval run. {@code conversationWindow} is the production window built from the
   * preceding turns, empty for the first turn of a case; {@code conversationNote} the {@code
   * RAHMEN} points condensed from those turns, empty for the first turn and for a run without a
   * note.
   */
  @FunctionalInterface
  public interface TurnInvocation {
    TurnInvocationResult invoke(
        ConversationCase conversationCase,
        int turnIndex,
        List<Message> conversationWindow,
        List<String> conversationNote);
  }

  /**
   * The production condensation of one user message into note candidates. A functional interface
   * rather than a dependency on the service, so this class stays Spring-free; the harness passes
   * the real one.
   */
  @FunctionalInterface
  public interface NoteExtraction {

    /** The condensation of a run that keeps no note - every turn then starts from nothing. */
    NoteExtraction NONE = userMessage -> List.of();

    List<ChatNoteCandidate> condense(String userMessage);
  }

  /** One turn's outcome: its windowed metrics plus what the pipeline actually returned. */
  public record TurnOutcome(
      RetrievalMetrics.WindowedQueryResult metrics,
      int turnIndex,
      int conversationWindowMessages,
      List<String> conversationNote,
      int chunksReturned,
      int distinctDocumentsReturned,
      List<String> subQueries) {

    /**
     * The existing solved criterion ({@link ExpectedStateAudit#isSolved}) at this path's window.
     */
    public boolean solved() {
      return ExpectedStateAudit.isSolved(
          metrics.allExpectedDocumentsHit(),
          metrics.rankedFileNames(),
          metrics.goldenCase().expectedDocuments());
    }
  }

  /**
   * One case's outcome: its turns in script order. A case is solved when every turn is.
   *
   * @param attemptedCondensations one call per turn, zero for a run measured without a note
   * @param failedCondensationTurnIds the turns whose condensation failed, in run order - carried up
   *     rather than swallowed, so {@link #report} can report a run whose note never came about
   */
  public record CaseOutcome(
      ConversationCase conversationCase,
      List<TurnOutcome> turns,
      int attemptedCondensations,
      List<String> failedCondensationTurnIds) {

    public CaseOutcome {
      failedCondensationTurnIds = List.copyOf(failedCondensationTurnIds);
    }

    public boolean solved() {
      return !turns.isEmpty() && turns.stream().allMatch(TurnOutcome::solved);
    }
  }

  /**
   * Runs every case, in dataset order, each case's turns in script order. Deliberately separate
   * from {@link #report}: the run configuration a report carries includes the measured duration,
   * which can only be determined after this method has returned.
   *
   * @param chatMemory the production conversation memory; each case runs under its own conversation
   *     id, which is cleared afterwards so no case can inherit another's window.
   */
  public static List<CaseOutcome> evaluateAll(
      List<ConversationCase> cases, ChatMemory chatMemory, TurnInvocation pipeline) {
    return evaluateAll(cases, chatMemory, pipeline, NoteExtraction.NONE, 0);
  }

  /** The same run with a Gesprächsnotiz - see {@link NoteExtraction}. */
  public static List<CaseOutcome> evaluateAll(
      List<ConversationCase> cases,
      ChatMemory chatMemory,
      TurnInvocation pipeline,
      NoteExtraction noteExtraction,
      int noteCap) {
    List<CaseOutcome> outcomes = new ArrayList<>(cases.size());
    for (ConversationCase conversationCase : cases) {
      outcomes.add(evaluateCase(conversationCase, chatMemory, pipeline, noteExtraction, noteCap));
    }
    return List.copyOf(outcomes);
  }

  /** Runs a single case; visible for the unit test that proves turn 2 receives turn 1's window. */
  public static CaseOutcome evaluateCase(
      ConversationCase conversationCase, ChatMemory chatMemory, TurnInvocation pipeline) {
    return evaluateCase(conversationCase, chatMemory, pipeline, NoteExtraction.NONE, 0);
  }

  /**
   * The same case with a Gesprächsnotiz: {@code noteExtraction} condenses each finished turn's
   * question, {@code noteCap} bounds the resulting list. {@code noteCap <= 0} keeps no note at all,
   * which is what the overload above passes.
   */
  public static CaseOutcome evaluateCase(
      ConversationCase conversationCase,
      ChatMemory chatMemory,
      TurnInvocation pipeline,
      NoteExtraction noteExtraction,
      int noteCap) {
    String conversationId = "eval-conversation-" + conversationCase.id() + "-" + UUID.randomUUID();
    List<TurnOutcome> turnOutcomes = new ArrayList<>(conversationCase.turns().size());
    List<ChatNoteCandidate> note = new ArrayList<>();
    List<String> failedCondensations = new ArrayList<>();
    try {
      for (int turnIndex = 0; turnIndex < conversationCase.turns().size(); turnIndex++) {
        ConversationCase.Turn turn = conversationCase.turns().get(turnIndex);
        // The window as production would hand it over: everything the memory holds *before* this
        // turn's own question is added.
        List<Message> window = List.copyOf(chatMemory.get(conversationId));
        List<String> rahmenPoints = rahmenPoints(note);
        TurnInvocationResult invocation =
            pipeline.invoke(conversationCase, turnIndex, window, rahmenPoints);
        turnOutcomes.add(
            evaluateTurn(
                conversationCase,
                turnIndex,
                window.size(),
                rahmenPoints,
                invocation.rankedChunkFileNames(),
                invocation.subQueries()));
        chatMemory.add(conversationId, new UserMessage(turn.query()));
        ConversationWindowMessages.answer(turn.answer())
            .ifPresent(message -> chatMemory.add(conversationId, message));
        // After the turn, as in production: a point condensed from this turn's question reaches
        // the *next* turn, never this one.
        if (!condenseInto(note, noteExtraction, turn.query(), noteCap)) {
          failedCondensations.add(conversationCase.turnId(turnIndex));
          log.warn(
              "Verdichtung der Gesprächsnotiz fehlgeschlagen für Runde {} — diese Runde steuert "
                  + "keine Notizpunkte bei, der Lauf geht weiter",
              conversationCase.turnId(turnIndex));
        }
      }
    } finally {
      chatMemory.clear(conversationId);
    }
    return new CaseOutcome(
        conversationCase,
        List.copyOf(turnOutcomes),
        noteCap <= 0 ? 0 : conversationCase.turns().size(),
        failedCondensations);
  }

  /** What the sub-question decomposition sees of the note - never the ANTWORTFORM points. */
  private static List<String> rahmenPoints(List<ChatNoteCandidate> note) {
    return note.stream()
        .filter(point -> point.kind() == ChatNoteItemKind.RAHMEN)
        .map(ChatNoteCandidate::text)
        .toList();
  }

  /**
   * Appends what the note accepts and drops as many oldest points as the cap requires - through
   * {@link ChatNoteList}, so the measured note cannot diverge from the persisted one.
   *
   * <p><b>A failing condensation costs this turn its points and nothing else</b>, the same
   * defensive catch {@code ChatNoteExtractionService#condenseAsync} makes in production. A run of
   * this path costs one condensation call per turn and is measured three times under the
   * Mehrfachlauf-Regel; letting a single transient model error propagate would discard the whole
   * multi-turn measurement, and would do so at the one seam this harness exists to reproduce
   * faithfully.
   */
  private static boolean condenseInto(
      List<ChatNoteCandidate> note,
      NoteExtraction noteExtraction,
      String userMessage,
      int noteCap) {
    if (noteCap <= 0) {
      return true;
    }
    List<ChatNoteCandidate> condensed;
    try {
      condensed = noteExtraction.condense(userMessage);
    } catch (RuntimeException e) {
      // Never the message or the condensed text: that is what the asking person wrote, which
      // docs/features/security-and-compliance.md keeps out of the log. The caller records which
      // turn it was and the report carries the count.
      log.debug("Condensation call failed", e);
      return false;
    }
    List<ChatNoteCandidate> accepted =
        ChatNoteList.accept(note.stream().map(ChatNoteCandidate::text).toList(), condensed);
    if (accepted.isEmpty()) {
      return true;
    }
    note.subList(0, ChatNoteList.overflow(note.size(), accepted.size(), noteCap)).clear();
    note.addAll(accepted);
    return true;
  }

  /**
   * A turn is scored by the single-question path's own evaluator, on the turn as a {@link
   * GoldenCase}: identical window, identical document deduplication, identical metric mathematics -
   * by construction rather than by a comment claiming it.
   */
  private static TurnOutcome evaluateTurn(
      ConversationCase conversationCase,
      int turnIndex,
      int conversationWindowMessages,
      List<String> conversationNote,
      List<String> rankedChunkFileNames,
      List<String> subQueries) {
    PipelineRetrievalEvaluator.CaseOutcome turn =
        PipelineRetrievalEvaluator.evaluateCase(
            conversationCase.turnAsGoldenCase(turnIndex), rankedChunkFileNames, subQueries);
    return new TurnOutcome(
        turn.metrics(),
        turnIndex,
        conversationWindowMessages,
        List.copyOf(conversationNote),
        turn.chunksReturned(),
        turn.distinctDocumentsReturned(),
        turn.subQueries());
  }

  /** Assembles the report from already-computed outcomes. */
  public static ConversationEvaluationReport report(
      List<CaseOutcome> outcomes, ConversationRunConfiguration runConfiguration) {
    List<RetrievalMetrics.WindowedQueryResult> allTurns =
        outcomes.stream().flatMap(c -> c.turns().stream()).map(TurnOutcome::metrics).toList();

    Map<String, List<RetrievalMetrics.WindowedQueryResult>> byTurnIndex = new TreeMap<>();
    for (CaseOutcome caseOutcome : outcomes) {
      for (TurnOutcome turn : caseOutcome.turns()) {
        byTurnIndex
            .computeIfAbsent(
                ConversationEvaluationReport.turnGroupKey(turn.turnIndex()), k -> new ArrayList<>())
            .add(turn.metrics());
      }
    }
    Map<String, PipelineMetricsAggregate> byTurn = new TreeMap<>();
    byTurnIndex.forEach((key, group) -> byTurn.put(key, PipelineMetricsAggregate.of(group)));

    return new ConversationEvaluationReport(
        ConversationEvaluationReport.CONVERSATION_MEASUREMENT_CONTRACT_VERSION,
        PipelineMetricsAggregate.METRIC_WINDOW_NOTE,
        ConversationEvaluationReport.SINGLE_PATH_NOTE,
        runConfiguration,
        PipelineMetricsAggregate.of(allTurns),
        PipelineMetricsAggregate.groupBy(allTurns, GoldenCase::category),
        byTurn,
        caseOutcomeSummary(outcomes),
        ExpectedStateAudit.evaluate(
            outcomes.stream()
                .map(
                    c ->
                        new ExpectedStateAudit.CaseState(
                            c.conversationCase().id(),
                            c.conversationCase().category(),
                            c.conversationCase().expectedState(),
                            c.conversationCase().expectedStateException(),
                            c.solved()))
                .toList()),
        topicBleed(outcomes),
        noteCondensation(outcomes),
        outcomes.stream().map(ConversationRetrievalEvaluator::toCaseResult).toList());
  }

  /**
   * How the run's note came about: one condensation call per turn, and the turns whose call failed.
   * {@code null} for a run measured without a note - an absent section, not a clean one.
   *
   * <p>Reported rather than merely caught: a run in which every call failed produces an empty note
   * for every turn while its {@code conversationNoteCap} fixed point still says 10, so the
   * comparator would hold it comparable and the numbers would read as evidence that the note does
   * not help.
   */
  private static ConversationEvaluationReport.NoteCondensationAudit noteCondensation(
      List<CaseOutcome> outcomes) {
    int attempted = outcomes.stream().mapToInt(CaseOutcome::attemptedCondensations).sum();
    if (attempted == 0) {
      return null;
    }
    List<String> failedTurnIds =
        outcomes.stream().flatMap(o -> o.failedCondensationTurnIds().stream()).toList();
    return new ConversationEvaluationReport.NoteCondensationAudit(
        attempted, failedTurnIds.size(), failedTurnIds);
  }

  private static CaseOutcomeSummary caseOutcomeSummary(List<CaseOutcome> outcomes) {
    Map<String, int[]> perClass = new TreeMap<>();
    int solved = 0;
    for (CaseOutcome outcome : outcomes) {
      int[] counts =
          perClass.computeIfAbsent(
              String.valueOf(outcome.conversationCase().category()), k -> new int[2]);
      counts[0]++;
      if (outcome.solved()) {
        counts[1]++;
        solved++;
      }
    }
    Map<String, ClassOutcome> byCategory = new TreeMap<>();
    perClass.forEach((key, c) -> byCategory.put(key, new ClassOutcome(c[0], c[1])));
    return new CaseOutcomeSummary(outcomes.size(), solved, byCategory);
  }

  /**
   * The Bleed count over every {@code topic_switch} case, measured in the <b>one change turn the
   * case names</b> ({@link ConversationCase#topicSwitchTurn()}): how many documents of the previous
   * topic - the expected documents of every turn before it that this turn does not itself expect -
   * still stood in its window. {@code null} without a single {@code topic_switch} case: an absent
   * section, not a clean one.
   *
   * <p>The change turn is never derived from the expected documents. A derivation would also fire
   * on an ordinary follow-up whose answer sits in another document ("Und bei Bedürftigkeit?" after
   * "Was kostet ein Anwohnerparkausweis?") and would then count the correct previous-topic document
   * as bleed - in the very number a topic-switch change is judged by.
   */
  private static TopicBleedAudit topicBleed(List<CaseOutcome> outcomes) {
    List<SwitchTurnBleed> byTurn = new ArrayList<>();
    int switchTurns = 0;
    int bledDocumentCount = 0;
    boolean anySwitchCase = false;
    for (CaseOutcome outcome : outcomes) {
      if (!ConversationCaseCuration.TOPIC_SWITCH_CLASS.equals(
          outcome.conversationCase().category())) {
        continue;
      }
      anySwitchCase = true;
      TurnOutcome changeTurn = changeTurnOf(outcome);
      if (changeTurn == null) {
        continue;
      }
      switchTurns++;
      List<String> bled = bledDocuments(outcome, changeTurn);
      bledDocumentCount += bled.size();
      if (!bled.isEmpty()) {
        byTurn.add(
            new SwitchTurnBleed(
                outcome.conversationCase().turnId(changeTurn.turnIndex()), List.copyOf(bled)));
      }
    }
    if (!anySwitchCase) {
      return null;
    }
    return new TopicBleedAudit(
        switchTurns,
        byTurn.size(),
        bledDocumentCount,
        switchTurns == 0 ? 0.0 : (double) bledDocumentCount / switchTurns,
        List.copyOf(byTurn));
  }

  /**
   * The turn the case declares as its change turn, or {@code null} for a case that names none or
   * names one outside its script - {@code ConversationCaseCuration} refuses both, so this is the
   * defensive half rather than a second rule.
   */
  private static TurnOutcome changeTurnOf(CaseOutcome outcome) {
    int index = outcome.conversationCase().topicSwitchTurnIndex();
    return index < 0 || index >= outcome.turns().size() ? null : outcome.turns().get(index);
  }

  private static List<String> bledDocuments(CaseOutcome outcome, TurnOutcome changeTurn) {
    Set<String> previousTopic = new LinkedHashSet<>();
    for (TurnOutcome turn : outcome.turns()) {
      if (turn.turnIndex() >= changeTurn.turnIndex()) {
        break;
      }
      previousTopic.addAll(turn.metrics().goldenCase().expectedDocuments());
    }
    previousTopic.removeAll(changeTurn.metrics().goldenCase().expectedDocuments());
    return changeTurn.metrics().rankedFileNames().stream()
        .distinct()
        .filter(previousTopic::contains)
        .toList();
  }

  private static ConversationCaseResult toCaseResult(CaseOutcome outcome) {
    Map<Integer, List<String>> bledByTurnIndex = new TreeMap<>();
    TurnOutcome changeTurn = changeTurnOf(outcome);
    if (ConversationCaseCuration.TOPIC_SWITCH_CLASS.equals(outcome.conversationCase().category())
        && changeTurn != null) {
      bledByTurnIndex.put(changeTurn.turnIndex(), bledDocuments(outcome, changeTurn));
    }
    List<TurnResult> turns =
        outcome.turns().stream()
            .map(
                turn ->
                    toTurnResult(
                        outcome.conversationCase(),
                        turn,
                        bledByTurnIndex.getOrDefault(turn.turnIndex(), List.of())))
            .toList();
    return new ConversationCaseResult(
        outcome.conversationCase().id(),
        outcome.conversationCase().category(),
        outcome.conversationCase().expectedState(),
        outcome.solved(),
        turns);
  }

  private static TurnResult toTurnResult(
      ConversationCase conversationCase, TurnOutcome turn, List<String> bledDocuments) {
    RetrievalMetrics.WindowedQueryResult m = turn.metrics();
    return new TurnResult(
        conversationCase.turnId(turn.turnIndex()),
        turn.turnIndex(),
        m.goldenCase().query(),
        m.goldenCase().expectedDocuments(),
        m.rankedFileNames(),
        m.hitRate(),
        m.reciprocalRank(),
        m.ndcg(),
        m.recall(),
        m.allExpectedDocumentsHit(),
        m.hitRateMargin(),
        m.rankingMargin(),
        turn.solved(),
        turn.conversationWindowMessages(),
        turn.conversationNote(),
        turn.chunksReturned(),
        turn.distinctDocumentsReturned(),
        turn.subQueries(),
        bledDocuments);
  }
}
