package io.opaa.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;

/**
 * Merges the ranked candidate lists of one retrieval run into one list by <b>rank</b> - never by
 * {@link Document#getScore()}: scores of different searches are not comparable, so ranking the
 * merged pool by raw score would favour whichever list scores higher overall. Each chunk is scored
 * by {@code 1 / (K + rank)} summed over every list it appears in (rank 1-based), so a chunk ranked
 * first in its own list competes on equal footing with the top chunk of every other list.
 *
 * <p>Deduplicated by {@link Document#getId()}: a chunk's contributions from every list are summed
 * before ranking, and the instance from the <b>earliest</b> list is kept. That tie-break is
 * positional rather than "the higher score" because duplicate instances of one chunk can carry a
 * cosine similarity and a {@code ts_rank}, and picking the larger of the two would be exactly the
 * cross-scale comparison this class avoids. No consumer reads a surviving {@link
 * Document#getScore()}; the ranking is the fused score either way.
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
   * Fuses the ranked, already permission-scoped candidate lists into at most {@code overallBudget}
   * chunks, highest fused score first. An empty input, or a non-positive {@code overallBudget},
   * yields an empty list.
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
   * The same fusion as {@link #fuse}, uncapped and with each candidate's fused score, so the stage
   * can report both the chunks that made the budget and the ones that missed it. {@link #fuse} is
   * this method capped, so the two can never drift apart into two ranking rules.
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
