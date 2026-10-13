package io.opaa.retrieval;

/**
 * The fixed vocabulary of reasons a stage attaches to a {@link CandidateVerdict}. A closed set
 * rather than free text so a consumer - the admin diagnosis above all - can group and translate
 * them instead of parsing sentences.
 */
public enum VerdictReason {

  /** A search stage found the candidate within its {@code fetch-k} window. */
  RETRIEVED_BY_SEARCH,

  /** The candidate was within the stage's budget and stayed. */
  WITHIN_BUDGET,

  /**
   * The candidate stayed only because it is one of the best candidates of its own list ({@code
   * opaa.query.fusion.guaranteed-per-list}): the fusion took it despite a fused rank beyond the
   * budget, or the relevance cutoff kept it despite a score below its bound.
   */
  GUARANTEED_LIST_TOP,

  /**
   * The candidate's fused rank was within the budget, but a guaranteed top candidate of another
   * list took its place.
   */
  DISPLACED_BY_GUARANTEED_LIST_TOP,

  /** The candidate's fused rank placed it below the overall {@code top-k} budget. */
  OUTSIDE_FUSION_BUDGET,

  /** The rerank model scored the candidate below the {@code top-k} the stage passes on. */
  OUTSIDE_RERANK_BUDGET,

  /**
   * The relevance cutoff removed the candidate: its score fell below the cutoff's share of the best
   * score, or it came after the drop in the score curve.
   */
  BELOW_RELEVANCE_CUTOFF,

  /**
   * Document completion pulled the candidate in as a sibling chunk of a document the selection
   * already held.
   */
  COMPLETED_AS_SIBLING,

  /**
   * Document completion evicted the candidate as the weakest chunk of another document that already
   * held at least two (tier 1) - document diversity is unchanged by such an eviction.
   */
  EVICTED_BY_DOCUMENT_COMPLETION_TIER_1,

  /**
   * Document completion evicted the candidate as the lowest-ranked chunk of the whole selection
   * (tier 2) - unlike tier 1 this can drop a document out of the selection entirely, which is why
   * it is a reason of its own.
   */
  EVICTED_BY_DOCUMENT_COMPLETION_TIER_2
}
