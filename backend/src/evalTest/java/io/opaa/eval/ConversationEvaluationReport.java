package io.opaa.eval;

import java.math.BigDecimal;
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
 * @param singlePathNote records once per report that this measurement runs on the pipeline path
 *     alone - see {@link #SINGLE_PATH_NOTE}.
 * @param overall every turn with search of every case, in one aggregate - a turn without search has
 *     no expected documents to rank and is judged in {@code noSearch} instead.
 * @param byCategory turn-level aggregate per case class ({@link
 *     ConversationCaseCuration#CASE_CLASSES}).
 * @param byTurn turn-level aggregate per turn number, keyed {@code "1"}, {@code "2"}, … ({@link
 *     #turnGroupKey}) - the grouping the whole path exists for: whether a follow-up turn is
 *     resolved at all is invisible in an aggregate that averages it with the standalone first
 *     turns.
 * @param caseOutcomes the case-level verdict: a case counts as solved only when <b>every</b> one of
 *     its turns is solved.
 * @param expectedStateAudit declared vs. measured case state, at the case-level criterion above.
 * @param topicBleed how many documents of the previous topic stood in the window of a topic change,
 *     {@code null} for a dataset without a {@code topic_switch} case.
 * @param noteCondensation how many of the run's Gespraechsnotiz condensations failed, {@code null}
 *     for a run measured without a note - see {@link NoteCondensationAudit}.
 * @param noSearch how the run judged the search need, in both wrong directions - see {@link
 *     NoSearchAudit}.
 */
public record ConversationEvaluationReport(
    int conversationMeasurementContractVersion,
    String metricWindowNote,
    String singlePathNote,
    ConversationRunConfiguration runConfiguration,
    PipelineMetricsAggregate overall,
    Map<String, PipelineMetricsAggregate> byCategory,
    Map<String, PipelineMetricsAggregate> byTurn,
    CaseOutcomeSummary caseOutcomes,
    ExpectedStateAudit.Result expectedStateAudit,
    TopicBleedAudit topicBleed,
    NoteCondensationAudit noteCondensation,
    NoSearchAudit noSearch,
    List<ConversationCaseResult> cases) {

  /**
   * Version of the multi-turn path's own measurement contract (ADR-0012, Nachtrag
   * Mehrrunden-Messpfad). Counted independently of the pipeline path's {@link
   * PipelineEvaluationReport#PIPELINE_MEASUREMENT_CONTRACT_VERSION}, for the same reason that one
   * is counted independently of the raw-vector path's: raising a contract version invalidates every
   * committed baseline of that path, and this path's arrival changes nothing about what the other
   * two measure.
   *
   * <p>Version 2 (issue #1522): {@code ollamaImage} became a checked fixed point of the shared
   * pipeline block, so a committed baseline of this path states which Ollama produced its vectors
   * (ADR-0012, Nachtrag Ollama-Herkunft). No measured value moves.
   *
   * <p>Version 3 (issue #1652): {@code ollamaCpuBackend} became a checked fixed point of the shared
   * pipeline block - the ggml CPU kernel set inside that image, which decides the greedy
   * decomposition of every turn (ADR-0012, Nachtrag CPU-Backend).
   *
   * <p>Version 4 (issue #1650): {@code contextPrefixFingerprint} became a checked fixed point of
   * the shared pipeline block (ADR-0012, Nachtrag Kontextpräfix-Form).
   *
   * <p>Version 5 (issue #1684): a turn can expect no search. Such a turn enters no metric aggregate
   * - {@code overall}, {@code byCategory} and {@code byTurn} count turns with search only - and is
   * judged in {@code noSearch}; its {@link TurnResult} carries no metric values.
   */
  public static final int CONVERSATION_MEASUREMENT_CONTRACT_VERSION = 5;

  /**
   * The Einpfad-Regel of docs/features/retrieval-benchmark.md §5, recorded <b>once per report</b>:
   * a multi-turn case can only ever run on the pipeline path - the raw-vector path searches
   * directly and knows neither conversation history nor decomposition. The dataset's one flat state
   * per case is therefore that path's state; a property of the measurement setup, not of a case.
   */
  public static final String SINGLE_PATH_NOTE =
      "Einpfad-Messung: Mehrrunden-Fälle laufen konstruktionsbedingt nur über den Pipeline-Pfad — "
          + "der Rohvektor-Pfad kennt weder Gesprächsverlauf noch Teilfragen-Zerlegung. Der "
          + "deklarierte Zustand eines Falls (expected_state) ist deshalb der Zustand dieses Pfads "
          + "(docs/features/retrieval-benchmark.md, Abschnitt 5, Einpfad-Regel). Die Einpfadigkeit "
          + "ist eine Eigenschaft dieses Datensatzes, nicht des einzelnen Falls.";

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
   *
   * @param chatTemperature the sampling temperature the chat model ran at - an observation next to
   *     {@code pipeline.chatModel}, never a fixed point: the pinned model always runs at the same
   *     one, and an external model's run is no baseline run.
   */
  public record ConversationRunConfiguration(
      PipelineEvaluationReport.PipelineRunConfiguration pipeline,
      ConversationMemoryProfile memoryProfile,
      int turnCount,
      BigDecimal chatTemperature) {}

  /**
   * How the Gesprächsnotiz of this run actually came about (#1487): one condensation call per turn,
   * and how many of them failed. A failed call costs its turn the points and never the run - which
   * is exactly why the count has to be reported: a run in which <em>every</em> call failed measures
   * standalone turns while still declaring {@code conversationNoteCap} as a checked fixed point,
   * and would otherwise read like a run that proved the note ineffective.
   *
   * <p>Deliberately an observation, never a fixed point: the comparator pins what a run measured
   * <em>with</em>, while this says how well the run went. {@code null} for a run measured without a
   * note at all - an absent section, not a clean one, the idiom {@link TopicBleedAudit} uses.
   *
   * @param failedTurnIds the turns whose condensation failed, in run order
   */
  public record NoteCondensationAudit(
      int attemptedCondensations, int failedCondensations, List<String> failedTurnIds) {}

  /**
   * How the run judged the search need of its turns, in both wrong directions. Every turn is
   * searched; the judgement only reaches the answer. An observation, never a fixed point.
   *
   * @param noSearchTurns the turns that expect no search ({@code search_expected: false})
   * @param noSearchTurnIdsJudgedAsSearch those of them judged to need a search, in run order
   * @param searchTurnIdsJudgedAsNoSearch the turns with search judged to need none, in run order -
   *     the costlier direction: in production such an answer is told there was nothing to search
   *     for
   */
  public record NoSearchAudit(
      int noSearchTurns,
      int noSearchTurnsJudgedAsSearch,
      List<String> noSearchTurnIdsJudgedAsSearch,
      int searchTurnsJudgedAsNoSearch,
      List<String> searchTurnIdsJudgedAsNoSearch) {}

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
   * @param conversationNote the {@code RAHMEN} points this turn received (#1490), empty for the
   *     first turn of a case and for a run measured without a note. Recorded for the same reason
   *     {@code subQueries} is: once the search window is narrower than the case, the note is the
   *     only way an early Rahmenangabe can reach the search at all, so a report without it cannot
   *     tell "the note never carried the Angabe" from "the decomposition ignored it".
   * @param subQueries the search queries decomposition produced for this turn: the observation the
   *     whole path exists for, since a follow-up question that was not resolved shows up here
   *     before it shows up in the metrics.
   * @param bledDocuments documents of an earlier turn's topic that stood in this turn's window,
   *     recorded only for the change turn of a {@code topic_switch} case (empty otherwise).
   * @param searchExpected {@code false} for a turn without search, whose metric values are {@code
   *     null}: it has no expected documents, and a zero would read as a missed one.
   * @param searchNeeded whether the run judged the turn to need a search; it searched either way.
   */
  public record TurnResult(
      String turnId,
      int turnIndex,
      boolean searchExpected,
      boolean searchNeeded,
      String query,
      List<String> expectedDocuments,
      List<String> rankedFileNames,
      Double hitRateAt5,
      Double reciprocalRankAt8,
      Double ndcgAt8,
      Double recallAt8,
      Double allExpectedDocumentsHitAt8,
      Integer hitRateMargin,
      Integer rankingMargin,
      boolean solved,
      int conversationWindowMessages,
      List<String> conversationNote,
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
