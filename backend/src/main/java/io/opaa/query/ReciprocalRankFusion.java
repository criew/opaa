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
 * - exactly the #912 failure mode Maßnahme B exists to fix. Reciprocal Rank Fusion instead scores
 * each chunk by {@code 1 / (K + rank)} summed over every sub-query list it appears in (rank
 * 1-based), so a chunk ranked first in its own sub-query's results competes on equal footing with
 * the top chunk of every other sub-query, regardless of either list's absolute scores.
 *
 * <p>Deduplicated by {@link Document#getId()} (the chunk id, stable across sub-queries since the
 * same chunk can legitimately be a top candidate for more than one sub-query): a chunk's
 * contributions from every list it appears in are summed before ranking, and the first {@link
 * Document} instance encountered for a given id is the one kept in the result - reasonable since a
 * chunk's own content/metadata do not vary between sub-query result lists.
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

  /**
   * Fuses {@code rankedResultsPerSubQuery} (one ranked, already permission/threshold-filtered chunk
   * list per sub-query - see {@code QueryService#retrieveRelevantChunks}) into at most {@code
   * overallBudget} chunks, highest fused score first. An empty input, or a non-positive {@code
   * overallBudget}, yields an empty list.
   */
  static List<Document> fuse(List<List<Document>> rankedResultsPerSubQuery, int overallBudget) {
    if (rankedResultsPerSubQuery.isEmpty() || overallBudget <= 0) {
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
        .limit(overallBudget)
        .map(entry -> documentByChunkId.get(entry.getKey()))
        .toList();
  }
}
