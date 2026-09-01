package io.opaa.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;

/**
 * Merges the per-sub-query ranked chunk lists {@code QueryService} retrieves for a decomposed
 * question (#923) into one final list, by <b>rank</b> - never by {@link Document#getScore()}: two
 * different sub-queries' similarity scores are not comparable (a chunk's score reflects how well it
 * matches its own sub-query's embedding, not the original question), so ranking the merged pool by
 * raw score would systematically favor whichever sub-query's topic happens to score higher overall
 * - exactly the #912 failure mode this class exists to fix. Reciprocal Rank Fusion instead scores
 * each chunk by {@code 1 / (K + rank)} summed over every sub-query list it appears in (rank
 * 1-based), so a chunk ranked first in its own sub-query's results competes on equal footing with
 * the top chunk of every other sub-query, regardless of either list's absolute scores.
 *
 * <p>Deduplicated by {@link Document#getId()} (the chunk id, stable across lists since the same
 * chunk can legitimately be a top candidate for more than one sub-query and for more than one
 * search path): a chunk's contributions from every list it appears in are summed before ranking,
 * and the {@link Document} instance from the <b>earliest</b> list is the one kept in the result.
 *
 * <p>That tie-break is deliberately positional and not "the higher {@link Document#getScore()}"
 * (its rule until #1049): since the lexical path became an input, duplicate instances of one chunk
 * can carry a cosine similarity and a {@code ts_rank}, and picking the larger of those two numbers
 * would be the very cross-scale comparison this class exists to avoid. The pipeline hands its lists
 * in stage order, so the earliest list is the vector path's - the score that survives is the
 * similarity every consumer of {@code ChatSource#getRelevanceScore()} expects. Only a chunk no
 * vector list found at all carries a {@code ts_rank} downstream; the ranking itself is the fused
 * score in either case and never a document's own.
 */
final class ReciprocalRankFusion {

  /**
   * The standard RRF damping constant (Cormack et al., 2009): large enough that the difference
   * between rank 1 and rank 2 within one list is modest relative to appearing in multiple lists at
   * all, which is the point of fusing several independently-relevant rankings rather than trusting
   * any single one's exact order too heavily.
   */
  private static final double RANK_DAMPING_CONSTANT = 60.0;

  private ReciprocalRankFusion() {}

  /** One fused candidate and the fused score it was ranked by. */
  record FusedCandidate(Document document, double fusedScore) {}

  /**
   * Fuses {@code rankedResultsPerSubQuery} (one ranked, already permission/threshold-filtered chunk
   * list per sub-query - see {@code RankFusionStage}) into at most {@code overallBudget} chunks,
   * highest fused score first. An empty input, or a non-positive {@code overallBudget}, yields an
   * empty list.
   */
  static List<Document> fuse(List<List<Document>> rankedResultsPerSubQuery, int overallBudget) {
    if (overallBudget <= 0) {
      return List.of();
    }
    return fuseRanked(rankedResultsPerSubQuery).stream()
        .limit(overallBudget)
        .map(FusedCandidate::document)
        .toList();
  }

  /**
   * The same fusion as {@link #fuse}, uncapped and with each candidate's fused score - what the
   * pipeline stage needs to report, in one protocol entry, both the chunks that made the budget and
   * the ones that missed it, each with the number it was ranked by. {@link #fuse} is this method
   * capped, so the two can never drift apart into two ranking rules.
   */
  static List<FusedCandidate> fuseRanked(List<List<Document>> rankedResultsPerSubQuery) {
    if (rankedResultsPerSubQuery.isEmpty()) {
      return List.of();
    }

    Map<String, Double> fusedScoreByChunkId = new LinkedHashMap<>();
    Map<String, Document> documentByChunkId = new LinkedHashMap<>();
    for (List<Document> rankedResults : rankedResultsPerSubQuery) {
      for (int i = 0; i < rankedResults.size(); i++) {
        Document document = rankedResults.get(i);
        int rank = i + 1;
        double contribution = 1.0 / (RANK_DAMPING_CONSTANT + rank);
        fusedScoreByChunkId.merge(document.getId(), contribution, Double::sum);
        documentByChunkId.putIfAbsent(document.getId(), document);
      }
    }

    return fusedScoreByChunkId.entrySet().stream()
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
        .map(entry -> new FusedCandidate(documentByChunkId.get(entry.getKey()), entry.getValue()))
        .toList();
  }
}
