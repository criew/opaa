package io.opaa.query;

import java.util.Objects;

/**
 * What a {@link RetrievalStage} returns: the new state and the explanation of how it got there.
 *
 * <p>The explanation is a component of the return type, not a side effect a stage may or may not
 * produce - that is the entire point of this record existing rather than the interface returning a
 * bare {@link RetrievalState}.
 */
public record StageOutcome(RetrievalState state, StageExplanation explanation) {

  public StageOutcome {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(explanation, "explanation");
  }
}
