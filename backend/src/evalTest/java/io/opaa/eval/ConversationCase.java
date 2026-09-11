package io.opaa.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * A multi-turn case of the conversation measurement path (issue #1484,
 * docs/features/conversation-memory.md, "Messung"): a scripted sequence of turns, each with its own
 * question, its own hand-written short answer and its own expected documents.
 *
 * <p><b>Why the answer is part of the fixture.</b> The harness generates no answers, but a
 * production conversation window contains them - a follow-up question is resolved against what was
 * said, not only against what was asked. The hand-written answer (one to three sentences, derived
 * from the target document's {@code answer_span}) makes the window deterministic and the case
 * reproducible without a generation step.
 *
 * <p>A case is solved when <b>every</b> one of its turns is solved under the existing criterion
 * ({@link ExpectedStateAudit#isSolved}); the state fields describe the case, never a single turn.
 *
 * @param topicSwitchTurn the 1-based turn a {@code topic_switch} case changes its topic in, {@code
 *     null} for every other class - see {@link ConversationCaseCuration#TOPIC_SWITCH_TURN_RULE}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConversationCase(
    String id,
    String domain,
    String category,
    List<Turn> turns,
    @JsonProperty("topic_switch_turn") Integer topicSwitchTurn,
    @JsonProperty("expected_state") GoldenCase.ExpectedState expectedState,
    @JsonProperty("expected_state_since") String expectedStateSince,
    @JsonProperty("expected_state_reason") String expectedStateReason,
    @JsonProperty("expected_state_exception") String expectedStateException) {

  /**
   * One turn of a case.
   *
   * @param answer the hand-written short answer that enters the conversation window as the
   *     assistant's turn - see the class Javadoc.
   * @param confusableDocument the document this turn's carried-over constraint exists to keep out
   *     (the other Nebenstelle, the other Fassung). Mandatory in at least one turn of a {@code
   *     constraint_carryover} case, because without it the class measures nothing.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Turn(
      String query,
      String answer,
      @JsonProperty("expected_documents") List<String> expectedDocuments,
      @JsonProperty("confusable_document") String confusableDocument) {}

  /** The id of one turn, as every per-turn report line and protocol dump names it. */
  public String turnId(int turnIndex) {
    return id + "#" + (turnIndex + 1);
  }

  /**
   * The 0-based index of the change turn, or {@code -1} for a case that names none. Only a {@code
   * topic_switch} case has one; the bleed count is defined over exactly this turn.
   */
  public int topicSwitchTurnIndex() {
    return topicSwitchTurn == null ? -1 : topicSwitchTurn - 1;
  }

  /**
   * The turn as the {@link GoldenCase} the metric mathematics and {@link PipelineMetricsAggregate}
   * consume. Deliberately an adaptation rather than a second metric implementation: a turn is a
   * question with expected documents, which is exactly what {@link GoldenCase} models, and reusing
   * it keeps this path's numbers computed by the very code that computes the single-question path's
   * (ADR-0012, Nachtrag Mehrrunden-Messpfad).
   *
   * <p>{@code difficulty} and {@code language} stay {@code null}: this path groups by case class
   * and by turn index only, so a value there would name a report group nothing reads.
   */
  public GoldenCase turnAsGoldenCase(int turnIndex) {
    Turn turn = turns.get(turnIndex);
    return new GoldenCase(
        turnId(turnIndex),
        domain,
        turn.query(),
        turn.expectedDocuments(),
        category,
        null,
        null,
        null,
        null,
        expectedState,
        expectedStateSince,
        expectedStateReason,
        expectedStateException,
        null,
        null,
        turn.confusableDocument(),
        null);
  }
}
