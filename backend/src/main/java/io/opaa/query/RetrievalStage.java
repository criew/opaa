package io.opaa.query;

/**
 * One named step of the retrieval pipeline (docs/features/hybrid-retrieval.md, Arbeitspaket 1):
 * candidate lists in, candidate lists out, plus an explanation of what happened to them.
 *
 * <p>Three properties are structural rather than a matter of discipline:
 *
 * <ul>
 *   <li><b>A stage cannot change the permission context.</b> It receives {@link RetrievalContext}
 *       read-only; the search scope and the filter derived from it are not part of what it returns.
 *   <li><b>A stage cannot see more candidates than it was handed.</b> Its input is the state's
 *       candidate lists and pool; only a search stage extends the pool, via {@link
 *       RetrievalState#withSearchResults}.
 *   <li><b>A stage cannot stay silent.</b> {@link StageOutcome} has no constructor without a {@link
 *       StageExplanation}, so an implementation that explains nothing does not compile rather than
 *       silently dropping out of the diagnosis.
 * </ul>
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
