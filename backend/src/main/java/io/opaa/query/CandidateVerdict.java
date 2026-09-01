package io.opaa.query;

import org.springframework.ai.document.Document;

/**
 * What one stage did to one candidate, and why - the smallest unit of the explanation protocol
 * (docs/features/hybrid-retrieval.md, Arbeitspaket 1).
 *
 * @param chunkId the candidate's chunk id ({@link Document#getId()}), the key every stage's
 *     verdicts can be joined on to follow one candidate through the whole run.
 * @param documentKey the candidate's document grouping key (see {@code
 *     QueryService#chunkGroupingKey}) - the level the diagnosis question "why is document Z not in
 *     the answer?" is actually asked at.
 * @param outcome what became of the candidate in this stage.
 * @param reason why, as a fixed vocabulary rather than prose, so a consumer can group by it.
 * @param listLabel which candidate list the verdict refers to ({@link CandidateList#label()}), or
 *     {@code null} once the lists have been fused into one.
 * @param rank the candidate's 1-based position in the ordering this stage decided against: its
 *     position in the stage's output for a surviving candidate, the position it held in the stage's
 *     input for a dropped one. {@code null} for a stage that does not rank at all. <b>Ranks, not
 *     scores, are what fusion works on</b> - and unlike {@link #value} they stay meaningful across
 *     search methods, which is why a lexical path's rank will be comparable with a vector path's
 *     while their scores never are.
 * @param value the stage-internal number the decision was made on - similarity score, fused RRF
 *     score, and so on. {@code null} for a stage whose decision is not numeric. Deliberately not
 *     comparable across stages, and not across search methods within one: a fusion score, a cosine
 *     similarity and a lexical rank score are different quantities, the exact confusion #912 was
 *     rooted in.
 */
public record CandidateVerdict(
    String chunkId,
    String documentKey,
    CandidateOutcome outcome,
    VerdictReason reason,
    String listLabel,
    Integer rank,
    Double value) {

  static CandidateVerdict of(
      Document document,
      CandidateOutcome outcome,
      VerdictReason reason,
      String listLabel,
      Integer rank,
      Double value) {
    return new CandidateVerdict(
        document.getId(),
        QueryService.chunkGroupingKey(document),
        outcome,
        reason,
        listLabel,
        rank,
        value);
  }
}
