package io.opaa.query;

/**
 * One named step of the retrieval pipeline (docs/handbuch/suche.md Abschnitt 4): candidate lists
 * in, candidate lists out, plus an explanation of what happened to them.
 *
 * <p>Three properties are structural rather than a matter of discipline: a stage receives {@link
 * RetrievalContext} read-only and can therefore not change the permission context; it sees no more
 * candidates than the state hands it, because only a search stage extends the pool via {@link
 * RetrievalState#withSearchResults}; and it cannot stay silent, because {@link StageOutcome} has no
 * constructor without a {@link StageExplanation}.
 */
public interface RetrievalStage {

  /** Which stage this is; unique across the registered stages of one pipeline. */
  RetrievalStageName name();

  /**
   * Runs the stage. Must not mutate {@code state} or anything reachable from {@code context} -
   * returns a new state instead.
   */
  StageOutcome apply(RetrievalContext context, RetrievalState state);

  /**
   * Whether this stage may be switched off. {@code true} for every stage except the one that
   * establishes the permission filter: "without that stage" would not be a measurable pipeline
   * variant but a search without a rights filter (ADR-0008 §5).
   */
  default boolean switchable() {
    return true;
  }
}
