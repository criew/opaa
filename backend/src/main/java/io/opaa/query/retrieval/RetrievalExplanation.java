package io.opaa.query.retrieval;

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
}
