package io.opaa.query;

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
   * The candidate lost the per-list narrowing to {@code top-k} - it was retrieved, but ranked below
   * the budget of its own list.
   */
  OUTSIDE_LIST_BUDGET,

  /** The candidate's fused rank placed it below the overall {@code top-k} budget. */
  OUTSIDE_FUSION_BUDGET,

  /**
   * Document completion pulled the candidate in as a sibling chunk of a document the selection
   * already held (#932).
   */
  COMPLETED_AS_SIBLING,

  /**
   * Document completion evicted the candidate as the weakest chunk of another document that already
   * held at least two (#932, tier 1) - document diversity is unchanged by such an eviction.
   */
  EVICTED_BY_DOCUMENT_COMPLETION_TIER_1,

  /**
   * Document completion evicted the candidate as the lowest-ranked chunk of the whole selection
   * (#932, tier 2) - unlike tier 1 this can drop a document out of the selection entirely, which is
   * why it is a reason of its own.
   */
  EVICTED_BY_DOCUMENT_COMPLETION_TIER_2
}
