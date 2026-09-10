package io.opaa.query.retrieval;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole run's explanation protocol: one {@link StageExplanation} per registered stage, in
 * execution order, whether that stage ran or not (docs/handbuch/suche.md Abschnitt 8).
 *
 * <p>It is always produced; keeping it is the caller's decision. The invariant it carries: {@code
 * stages().size()} equals the number of stages the pipeline registered, so a candidate cannot
 * vanish without a trace in a diagnosis that looks complete.
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
