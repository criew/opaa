package io.opaa.query;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole run's explanation protocol: one {@link StageExplanation} per registered stage, in
 * execution order, whether that stage ran or not (docs/features/hybrid-retrieval.md, Arbeitspaket
 * 1).
 *
 * <p><b>It is always produced, never optional.</b> Whether it is kept is the caller's decision -
 * {@code QueryService#query} discards it, the admin diagnosis evaluates it - but no run can happen
 * without it, because {@link RetrievalStage} cannot return a result without one.
 *
 * <p>The invariant this type carries: {@code stages().size()} equals the number of stages the
 * pipeline has registered. A stage that vanishes from the protocol is a candidate that vanishes
 * without a trace in a tool that looks complete, which is the failure this is built to prevent.
 */
public record RetrievalExplanation(List<StageExplanation> stages) {

  public RetrievalExplanation {
    stages = List.copyOf(stages);
  }

  /** Every verdict for {@code chunkId} across all stages, in execution order. */
  public List<CandidateVerdict> forChunk(String chunkId) {
    List<CandidateVerdict> matching = new ArrayList<>();
    for (StageExplanation stage : stages) {
      stage.verdicts().stream().filter(v -> v.chunkId().equals(chunkId)).forEach(matching::add);
    }
    return List.copyOf(matching);
  }

  /**
   * The stage that dropped {@code chunkId}, or empty if none did - the answer to "was the document
   * never found, or was it found and displaced?", the one question the diagnosis exists for.
   */
  public List<StageExplanation> stagesThatDropped(String chunkId) {
    return stages.stream()
        .filter(
            stage ->
                stage.verdicts().stream()
                    .anyMatch(
                        v ->
                            v.chunkId().equals(chunkId) && v.outcome() == CandidateOutcome.DROPPED))
        .toList();
  }
}
