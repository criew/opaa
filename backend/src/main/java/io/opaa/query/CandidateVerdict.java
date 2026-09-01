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
 * @param value the stage-internal number the decision was made on - similarity score, fused RRF
 *     score, and so on. {@code null} for a stage whose decision is not numeric. Deliberately not
 *     comparable across stages: a fusion score and a similarity score are different quantities, the
 *     exact confusion #912 was rooted in.
 */
public record CandidateVerdict(
    String chunkId,
    String documentKey,
    CandidateOutcome outcome,
    VerdictReason reason,
    String listLabel,
    Double value) {

  static CandidateVerdict of(
      Document document,
      CandidateOutcome outcome,
      VerdictReason reason,
      String listLabel,
      Double value) {
    return new CandidateVerdict(
        document.getId(),
        QueryService.chunkGroupingKey(document),
        outcome,
        reason,
        listLabel,
        value);
  }
}
