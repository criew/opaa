package io.opaa.retrieval;

import io.opaa.api.types.CandidateOutcome;
import io.opaa.api.types.RetrievalStageName;
import io.opaa.api.types.VerdictReason;
import java.util.List;
import org.springframework.ai.document.Document;

/**
 * What one stage did to one candidate, and why - the smallest unit of the explanation protocol
 * (docs/handbuch/suche.md Abschnitt 8).
 *
 * @param chunkId the candidate's chunk id ({@link Document#getId()}), the key every stage's
 *     verdicts can be joined on to follow one candidate through the whole run.
 * @param documentKey the candidate's document grouping key (see {@link ChunkGroupingKey}) - the
 *     level the diagnosis question "why is document Z not in the answer?" is actually asked at.
 * @param outcome what became of the candidate in this stage.
 * @param reason why, as a fixed vocabulary rather than prose, so a consumer can group by it.
 * @param listLabel which candidate list the verdict refers to ({@link CandidateList#label()}), or
 *     {@code null} once the lists have been fused into one.
 * @param rank the candidate's 1-based position in the ordering this stage decided against: its
 *     position in the stage's output for a surviving candidate, the position it held in the stage's
 *     input for a dropped one. {@code null} for a stage that does not rank at all. Ranks, not
 *     scores, are what fusion works on: unlike {@link #value} they stay meaningful across search
 *     methods.
 * @param value the stage-internal number the decision was made on - similarity score, fused RRF
 *     score, and so on. {@code null} for a stage whose decision is not numeric. Deliberately not
 *     comparable across stages, nor across search methods within one: a fusion score, a cosine
 *     similarity and a lexical rank score are different quantities.
 * @param contributions for a {@link RetrievalStageName#RANK_FUSION} verdict, the lists whose
 *     contributions make up {@code value}: every list the candidate appeared in when pooled, only
 *     the placing sub-query's lists with the quota. They add up to {@code value}. Empty for every
 *     other stage.
 */
public record CandidateVerdict(
    String chunkId,
    String documentKey,
    CandidateOutcome outcome,
    VerdictReason reason,
    String listLabel,
    Integer rank,
    Double value,
    List<FusionContribution> contributions) {

  public CandidateVerdict {
    contributions = contributions == null ? List.of() : List.copyOf(contributions);
  }

  /** A verdict without fusion contributions - every stage but the fusion. */
  public CandidateVerdict(
      String chunkId,
      String documentKey,
      CandidateOutcome outcome,
      VerdictReason reason,
      String listLabel,
      Integer rank,
      Double value) {
    this(chunkId, documentKey, outcome, reason, listLabel, rank, value, List.of());
  }

  public static CandidateVerdict of(
      Document document,
      CandidateOutcome outcome,
      VerdictReason reason,
      String listLabel,
      Integer rank,
      Double value) {
    return new CandidateVerdict(
        document.getId(), ChunkGroupingKey.of(document), outcome, reason, listLabel, rank, value);
  }

  /** A fusion verdict with the contribution of every list the candidate appeared in. */
  public static CandidateVerdict fused(
      Document document,
      CandidateOutcome outcome,
      VerdictReason reason,
      String listLabel,
      int rank,
      double value,
      List<FusionContribution> contributions) {
    return new CandidateVerdict(
        document.getId(),
        ChunkGroupingKey.of(document),
        outcome,
        reason,
        listLabel,
        rank,
        value,
        contributions);
  }
}
