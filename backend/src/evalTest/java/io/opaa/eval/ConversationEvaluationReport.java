package io.opaa.eval;

import java.util.List;
import java.util.Map;

/**
 * Machine-readable report of the multi-turn measurement path (issue #1484,
 * docs/features/conversation-memory.md, "Messung"): every case run turn by turn through the same
 * chain a real request takes, with the conversation window of each turn built from the preceding
 * scripted turns.
 *
 * <p><b>A separate type and a separate file, like the pipeline path itself.</b> The single-question
 * path measures standalone questions and this one measures turns of a conversation; a shared report
 * shape would invite exactly the unlabelled side-by-side comparison ADR-0012's Nachtrag zum
 * Pipeline-Messpfad rules out. The metric mathematics is <b>not</b> duplicated: a turn is measured
 * by {@link RetrievalMetrics} and aggregated by {@link PipelineMetricsAggregate} at this path's own
 * windows, which are the pipeline path's (Hit Rate@5, MRR@8, nDCG@8, Recall@8).
 *
 * @param overall every turn of every case, in one aggregate.
 * @param byCategory turn-level aggregate per case class ({@link
 *     ConversationCaseCuration#CASE_CLASSES}).
 * @param byTurn turn-level aggregate per turn index, keyed {@code turn-1}, {@code turn-2}, … - the
 *     grouping the whole path exists for: whether a follow-up turn is resolved at all is invisible
 *     in an aggregate that averages it with the standalone first turns.
 * @param caseOutcomes the case-level verdict: a case counts as solved only when <b>every</b> one of
 *     its turns is solved.
 * @param expectedStateAudit declared vs. measured case state, at the case-level criterion above.
 * @param topicBleed how many documents of the previous topic stood in the window of a topic change,
 *     {@code null} for a dataset without a {@code topic_switch} case.
 */
public record ConversationEvaluationReport(
    int conversationMeasurementContractVersion,
    String metricWindowNote,
    ConversationRunConfiguration runConfiguration,
    PipelineMetricsAggregate overall,
    Map<String, PipelineMetricsAggregate> byCategory,
    Map<String, PipelineMetricsAggregate> byTurn,
    CaseOutcomeSummary caseOutcomes,
    ExpectedStateAudit.Result expectedStateAudit,
    TopicBleedAudit topicBleed,
    List<ConversationCaseResult> cases) {

  /**
   * Version of the multi-turn path's own measurement contract (ADR-0012, Nachtrag
   * Mehrrunden-Messpfad). Counted independently of the pipeline path's {@link
   * PipelineEvaluationReport#PIPELINE_MEASUREMENT_CONTRACT_VERSION}, for the same reason that one
   * is counted independently of the raw-vector path's: raising a contract version invalidates every
   * committed baseline of that path, and this path's arrival changes nothing about what the other
   * two measure.
   */
  public static final int CONVERSATION_MEASUREMENT_CONTRACT_VERSION = 1;

  /**
   * The key of the per-turn group: the turn number, 1-based. Unprefixed like the report's category
   * keys - {@link ConversationBaseline#turn} builds the {@code turn:<n>} group key a committed
   * baseline carries, exactly as {@link Baseline#category} does for the other grouping.
   */
  public static String turnGroupKey(int turnIndex) {
    return String.valueOf(turnIndex + 1);
  }

  /**
   * The fixed points of a multi-turn run: everything the pipeline path pins, plus the three
   * conversation-memory dimensions and the dataset's own size.
   *
   * <p>{@code pipeline.goldenDatasetFile}/{@code goldenDatasetSha256}/{@code goldenCaseCount}
   * describe the <b>multi-turn</b> dataset here, not the single-question one - the same three
   * fields in the same roles, for the dataset this run actually measured.
   */
  public record ConversationRunConfiguration(
      PipelineEvaluationReport.PipelineRunConfiguration pipeline,
      ConversationMemoryProfile memoryProfile,
      int turnCount) {}

  /** How many cases were solved in full, overall and per case class. */
  public record CaseOutcomeSummary(
      int cases, int solvedCases, Map<String, ClassOutcome> byCategory) {}

  /** One case class's case-level verdict. */
  public record ClassOutcome(int cases, int solvedCases) {}

  /** One case's result: the per-turn lines plus the case-level verdict over them. */
  public record ConversationCaseResult(
      String id,
      String category,
      GoldenCase.ExpectedState expectedState,
      boolean solved,
      List<TurnResult> turns) {}

  /**
   * One turn's result. Metric components carry their window in their name for the same reason
   * {@link PipelineEvaluationReport.PipelineQueryResult}'s do.
   *
   * @param conversationWindowMessages how many messages this turn's conversation window actually
   *     held - 0 for the first turn of a case, and capped by the production window width.
   * @param subQueries the search queries decomposition produced for this turn: the observation the
   *     whole path exists for, since a follow-up question that was not resolved shows up here
   *     before it shows up in the metrics.
   * @param bledDocuments documents of an earlier turn's topic that stood in this turn's window,
   *     recorded only for the change turn of a {@code topic_switch} case (empty otherwise).
   */
  public record TurnResult(
      String turnId,
      int turnIndex,
      String query,
      List<String> expectedDocuments,
      List<String> rankedFileNames,
      double hitRateAt5,
      double reciprocalRankAt8,
      double ndcgAt8,
      double recallAt8,
      double allExpectedDocumentsHitAt8,
      Integer hitRateMargin,
      Integer rankingMargin,
      boolean solved,
      int conversationWindowMessages,
      int chunksReturned,
      int distinctDocumentsReturned,
      List<String> subQueries,
      List<String> bledDocuments) {}

  /**
   * The Bleed count of docs/features/conversation-memory.md: in the turn in which a {@code
   * topic_switch} case changes topic, how many documents of the <b>previous</b> topic still stood
   * in the window. A change turn is one whose expected documents are disjoint from every earlier
   * turn's - determined from the dataset, not from a detector, because the specification
   * deliberately has none.
   */
  public record TopicBleedAudit(
      int switchTurns,
      int bleedingSwitchTurns,
      int bledDocuments,
      double meanBledDocumentsPerSwitchTurn,
      List<SwitchTurnBleed> byTurn) {}

  /** One change turn's bleed. */
  public record SwitchTurnBleed(String turnId, List<String> bledDocuments) {}
}
