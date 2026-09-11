package io.opaa.eval;

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
 * <p>Takes the retrieval itself as a {@link TurnInvocation} rather than depending on {@code
 * RetrievalPipeline}, for the same reason {@link PipelineRetrievalEvaluator} does: the harness
 * supplies the production run, while this class stays a Docker- and Spring-context-free unit
 * exercised by {@code ConversationRetrievalEvaluatorTest}.
 */
public final class ConversationRetrievalEvaluator {

  private ConversationRetrievalEvaluator() {}

  /** What one call into the pipeline produced for a turn. */
  public record TurnInvocationResult(List<String> rankedChunkFileNames, List<String> subQueries) {}

  /**
   * One turn's retrieval run. {@code conversationWindow} is the production window built from the
   * preceding turns, empty for the first turn of a case.
   */
  @FunctionalInterface
  public interface TurnInvocation {
    TurnInvocationResult invoke(
        ConversationCase conversationCase, int turnIndex, List<Message> conversationWindow);
  }

  /** One turn's outcome: its windowed metrics plus what the pipeline actually returned. */
  public record TurnOutcome(
      RetrievalMetrics.WindowedQueryResult metrics,
      int turnIndex,
      int conversationWindowMessages,
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

  /** One case's outcome: its turns in script order. A case is solved when every turn is. */
  public record CaseOutcome(ConversationCase conversationCase, List<TurnOutcome> turns) {

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
    List<CaseOutcome> outcomes = new ArrayList<>(cases.size());
    for (ConversationCase conversationCase : cases) {
      outcomes.add(evaluateCase(conversationCase, chatMemory, pipeline));
    }
    return List.copyOf(outcomes);
  }

  /** Runs a single case; visible for the unit test that proves turn 2 receives turn 1's window. */
  public static CaseOutcome evaluateCase(
      ConversationCase conversationCase, ChatMemory chatMemory, TurnInvocation pipeline) {
    String conversationId = "eval-conversation-" + conversationCase.id() + "-" + UUID.randomUUID();
    List<TurnOutcome> turnOutcomes = new ArrayList<>(conversationCase.turns().size());
    try {
      for (int turnIndex = 0; turnIndex < conversationCase.turns().size(); turnIndex++) {
        ConversationCase.Turn turn = conversationCase.turns().get(turnIndex);
        // The window as production would hand it over: everything the memory holds *before* this
        // turn's own question is added.
        List<Message> window = List.copyOf(chatMemory.get(conversationId));
        TurnInvocationResult invocation = pipeline.invoke(conversationCase, turnIndex, window);
        turnOutcomes.add(
            evaluateTurn(
                conversationCase,
                turnIndex,
                window.size(),
                invocation.rankedChunkFileNames(),
                invocation.subQueries()));
        chatMemory.add(conversationId, new UserMessage(turn.query()));
        ConversationWindowMessages.answer(turn.answer())
            .ifPresent(message -> chatMemory.add(conversationId, message));
      }
    } finally {
      chatMemory.clear(conversationId);
    }
    return new CaseOutcome(conversationCase, List.copyOf(turnOutcomes));
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
      List<String> rankedChunkFileNames,
      List<String> subQueries) {
    PipelineRetrievalEvaluator.CaseOutcome turn =
        PipelineRetrievalEvaluator.evaluateCase(
            conversationCase.turnAsGoldenCase(turnIndex), rankedChunkFileNames, subQueries);
    return new TurnOutcome(
        turn.metrics(),
        turnIndex,
        conversationWindowMessages,
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
        outcomes.stream().map(ConversationRetrievalEvaluator::toCaseResult).toList());
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
        turn.chunksReturned(),
        turn.distinctDocumentsReturned(),
        turn.subQueries(),
        bledDocuments);
  }
}
