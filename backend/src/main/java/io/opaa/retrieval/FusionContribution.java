package io.opaa.retrieval;

/**
 * What one candidate list contributed to a candidate's fused score - the part of a {@link
 * RetrievalStageName#RANK_FUSION} verdict that says which sub-query and which search path a
 * candidate owes its place to. Only lists that count towards the verdict's value appear.
 *
 * @param listLabel the contributing list ({@link CandidateList#label()}).
 * @param rank the candidate's 1-based rank in that list.
 * @param value the share of the fused score from that list, in the fusion method's own unit.
 */
public record FusionContribution(String listLabel, int rank, double value) {}
