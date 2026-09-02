package io.opaa.query;

import java.util.List;

/**
 * One stage's account of what it did - the mandatory half of {@link StageOutcome}. A stage cannot
 * stay silent: the interface does not accept a result without one.
 *
 * @param stage which stage this is.
 * @param status whether it actually ran.
 * @param incomingCount candidates the stage was handed, counted across all lists in flight.
 * @param outgoingCount candidates it passed on, counted the same way. Both are counts of list
 *     entries, not of distinct chunks: the same chunk found by two sub-queries is two entries
 *     before fusion and one after, and flattening that difference away would hide exactly what
 *     fusion did.
 * @param verdicts one entry per candidate the stage acted on, in the stage's own order. Empty for a
 *     stage that did not run, and for {@link RetrievalStageName#SEARCH_SCOPE}, which acts on no
 *     candidates at all.
 * @param notes short, technical statements of what the stage decided beyond its verdicts - the
 *     search scope's library count, the sub-queries produced, the parameter a budget came from.
 */
public record StageExplanation(
    RetrievalStageName stage,
    StageStatus status,
    int incomingCount,
    int outgoingCount,
    List<CandidateVerdict> verdicts,
    List<String> notes) {

  public StageExplanation {
    verdicts = List.copyOf(verdicts);
    notes = List.copyOf(notes);
  }

  /** A stage that ran, with its verdicts and notes. */
  static StageExplanation executed(
      RetrievalStageName stage,
      int incomingCount,
      int outgoingCount,
      List<CandidateVerdict> verdicts,
      List<String> notes) {
    return new StageExplanation(
        stage, StageStatus.EXECUTED, incomingCount, outgoingCount, verdicts, notes);
  }

  /**
   * A stage that did not run - switched off, or reached after the run halted. It still appears in
   * the protocol, with its input passed through unchanged as its output: a stage silently missing
   * from the protocol is the failure mode this whole record exists to make impossible.
   */
  static StageExplanation notRun(RetrievalStageName stage, StageStatus status, int candidateCount) {
    return notRun(stage, status, candidateCount, candidateCount, status.note());
  }

  /**
   * A stage that did not do its work but still had to hand something on - with its own note instead
   * of the status's generic one, and with an outgoing count that may differ from the incoming one:
   * {@link RerankStage} restores the {@code top-k} cap even when it could not rerank, and a
   * protocol claiming it passed everything through would misstate the selection.
   */
  static StageExplanation notRun(
      RetrievalStageName stage,
      StageStatus status,
      int incomingCount,
      int outgoingCount,
      String note) {
    return new StageExplanation(
        stage, status, incomingCount, outgoingCount, List.of(), List.of(note));
  }
}
