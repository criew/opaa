package io.opaa.retrieval;

import io.opaa.api.types.RetrievalStageName;

/**
 * How {@link RetrievalStageName#RANK_FUSION} turns candidate lists into one score per candidate
 * (docs/features/retrieval-algorithm.md, step 5).
 */
public enum FusionMethod {

  /** Reciprocal Rank Fusion: {@code 1 / (60 + rank)} per list; scores are ignored. */
  RRF,

  /**
   * Convex combination of normalized scores: the cosine similarity rescaled from the similarity
   * threshold (0) to 1 (1), weighted by {@link QueryProperties.Fusion#vectorWeight}, plus the
   * {@code ts_rank} relative to the best of its list, weighted by the remainder.
   */
  SCORE
}
