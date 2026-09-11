package io.opaa.eval;

import io.opaa.eval.ConversationEvaluationReport.CaseOutcomeSummary;
import io.opaa.eval.ConversationEvaluationReport.ClassOutcome;
import io.opaa.eval.ConversationEvaluationReport.ConversationCaseResult;
import io.opaa.eval.ConversationEvaluationReport.ConversationRunConfiguration;
import io.opaa.eval.ConversationEvaluationReport.SwitchTurnBleed;
import io.opaa.eval.ConversationEvaluationReport.TopicBleedAudit;
import io.opaa.eval.ConversationEvaluationReport.TurnResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Runs a multi-turn dataset turn by turn and assembles a {@link ConversationEvaluationReport}
 * (issue #1484, docs/features/conversation-memory.md, "Messung").
 *
 * <p><b>The conversation window is built by the production memory, not by this class.</b> Every
 * turn is appended to the {@link ChatMemory} bean a chat request uses, and the window handed to the
 * next turn is whatever that memory returns - so the window width, its eviction order and (once
 * there is one) its normalization are production behaviour, never a second implementation that can
 * drift. The {@code Message} shapes match {@code ChatService#historyAsSpringAiMessages}: the user's
 * question as a {@code UserMessage}, the hand-written short answer as an {@code AssistantMessage}.
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
   * preceding turns (empty for the first turn); {@code conversationNote} is the Gesprächsnotiz,
   * empty until it exists.
   */
  @FunctionalInterface
  public interface TurnInvocation {
    TurnInvocationResult invoke(
        ConversationCase conversationCase,
        int turnIndex,
        List<Message> conversationWindow,
        List<String> conversationNote);
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
        TurnInvocationResult invocation =
            pipeline.invoke(
                conversationCase, turnIndex, window, ConversationMemoryProfile.conversationNote());
        turnOutcomes.add(
            evaluateTurn(
                conversationCase,
                turnIndex,
                window.size(),
                invocation.rankedChunkFileNames(),
                invocation.subQueries()));
        chatMemory.add(
            conversationId,
            List.of(new UserMessage(turn.query()), new AssistantMessage(turn.answer())));
      }
    } finally {
      chatMemory.clear(conversationId);
    }
    return new CaseOutcome(conversationCase, List.copyOf(turnOutcomes));
  }

  private static TurnOutcome evaluateTurn(
      ConversationCase conversationCase,
      int turnIndex,
      int conversationWindowMessages,
      List<String> rankedChunkFileNames,
      List<String> subQueries) {
    DocumentRanking.DocumentWindowResult window =
        DocumentRanking.applyDocumentWindow(
            rankedChunkFileNames, PipelineMetricsAggregate.RANKING_K);
    return new TurnOutcome(
        RetrievalMetrics.evaluateAt(
            conversationCase.turnAsGoldenCase(turnIndex),
            window.rankedFileNames(),
            PipelineMetricsAggregate.HIT_RATE_K,
            PipelineMetricsAggregate.RANKING_K),
        turnIndex,
        conversationWindowMessages,
        rankedChunkFileNames.size(),
        window.distinctDocumentsReached(),
        subQueries);
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
   * The Bleed count over every {@code topic_switch} case. A <b>change turn</b> is the first turn
   * whose expected documents share nothing with any earlier turn of the same case; the bled
   * documents are those earlier turns' expected documents that still stand in the change turn's
   * window. {@code null} without a single {@code topic_switch} case - an absent section, not a
   * clean one.
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
      for (TurnOutcome turn : switchTurnsOf(outcome)) {
        switchTurns++;
        List<String> bled = bledDocuments(outcome, turn);
        bledDocumentCount += bled.size();
        if (!bled.isEmpty()) {
          byTurn.add(
              new SwitchTurnBleed(
                  outcome.conversationCase().turnId(turn.turnIndex()), List.copyOf(bled)));
        }
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

  private static List<TurnOutcome> switchTurnsOf(CaseOutcome outcome) {
    List<TurnOutcome> switchTurns = new ArrayList<>();
    Set<String> earlier = new LinkedHashSet<>();
    for (TurnOutcome turn : outcome.turns()) {
      List<String> expected = turn.metrics().goldenCase().expectedDocuments();
      if (!earlier.isEmpty() && expected.stream().noneMatch(earlier::contains)) {
        switchTurns.add(turn);
      }
      earlier.addAll(expected);
    }
    return switchTurns;
  }

  private static List<String> bledDocuments(CaseOutcome outcome, TurnOutcome switchTurn) {
    Set<String> previousTopic = new LinkedHashSet<>();
    for (TurnOutcome turn : outcome.turns()) {
      if (turn.turnIndex() >= switchTurn.turnIndex()) {
        break;
      }
      previousTopic.addAll(turn.metrics().goldenCase().expectedDocuments());
    }
    previousTopic.removeAll(switchTurn.metrics().goldenCase().expectedDocuments());
    return switchTurn.metrics().rankedFileNames().stream()
        .distinct()
        .filter(previousTopic::contains)
        .toList();
  }

  private static ConversationCaseResult toCaseResult(CaseOutcome outcome) {
    Map<Integer, List<String>> bledByTurnIndex = new TreeMap<>();
    if (ConversationCaseCuration.TOPIC_SWITCH_CLASS.equals(outcome.conversationCase().category())) {
      for (TurnOutcome switchTurn : switchTurnsOf(outcome)) {
        bledByTurnIndex.put(switchTurn.turnIndex(), bledDocuments(outcome, switchTurn));
      }
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
