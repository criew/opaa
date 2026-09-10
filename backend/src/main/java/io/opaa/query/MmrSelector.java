package io.opaa.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;

/**
 * Maximal Marginal Relevance (MMR) selection within one candidate list: the first pick is the
 * highest-relevance candidate, every following pick maximizes {@code mmrLambda * relevance - (1 -
 * mmrLambda) * maxSimilarityToAlreadySelected}, so a candidate that merely repeats an
 * already-selected chunk loses ground to a less relevant but topically distinct one.
 *
 * <p>Pairwise similarity is cosine similarity of the real chunk embeddings, read by row id via
 * {@link ChunkEmbeddingLookup} - no embedding-API call. A candidate whose id is missing from {@code
 * embeddingsByChunkId} contributes {@code 0.0} similarity: a defensive fallback for a chunk deleted
 * between search and lookup, not a claim that it is dissimilar.
 *
 * <p>Scale note: relevance scores differ by as little as ~0.02 between neighbours while cosine
 * similarities span ~0.3-0.5, and a lexical list's {@code ts_rank} relevance is an order of
 * magnitude smaller still. At any {@code mmrLambda < 1.0} the diversity term therefore dominates,
 * for a lexical list almost entirely; comparing the two paths here would need a per-path
 * normalization that does not exist. At the shipped {@code 1.0} the question does not arise.
 */
final class MmrSelector {

  private MmrSelector() {}

  /**
   * Selects at most {@code topK} candidates from the already permission-scoped {@code candidates} -
   * this method only ever narrows that set. {@code mmrLambda = 1.0} reproduces plain top-{@code
   * topK}-by-{@link Document#getScore()} selection, and {@code embeddingsByChunkId} may then
   * legitimately be {@link Map#of()}, because the diversity term is multiplied by zero.
   */
  static List<Document> select(
      List<Document> candidates,
      int topK,
      double mmrLambda,
      Map<String, float[]> embeddingsByChunkId) {
    if (candidates.isEmpty() || topK <= 0) {
      return List.of();
    }

    List<Document> remaining = new ArrayList<>(candidates);
    List<Document> selected = new ArrayList<>(Math.min(topK, candidates.size()));

    while (!remaining.isEmpty() && selected.size() < topK) {
      int bestIndex = 0;
      double bestScore = Double.NEGATIVE_INFINITY;
      for (int i = 0; i < remaining.size(); i++) {
        Document candidate = remaining.get(i);
        double relevance = relevanceOf(candidate);
        double maxSimilarityToSelected = maxSimilarity(candidate, selected, embeddingsByChunkId);
        double mmrScore = mmrLambda * relevance - (1 - mmrLambda) * maxSimilarityToSelected;
        if (mmrScore > bestScore) {
          bestScore = mmrScore;
          bestIndex = i;
        }
      }
      selected.add(remaining.remove(bestIndex));
    }
    return selected;
  }

  private static double relevanceOf(Document document) {
    Double score = document.getScore();
    return score != null ? score : 0.0;
  }

  private static double maxSimilarity(
      Document candidate, List<Document> selected, Map<String, float[]> embeddingsByChunkId) {
    float[] candidateEmbedding = embeddingsByChunkId.get(candidate.getId());
    if (candidateEmbedding == null) {
      return 0.0;
    }
    double max = 0.0;
    for (Document other : selected) {
      float[] otherEmbedding = embeddingsByChunkId.get(other.getId());
      if (otherEmbedding == null) {
        continue;
      }
      double similarity = cosineSimilarity(candidateEmbedding, otherEmbedding);
      if (similarity > max) {
        max = similarity;
      }
    }
    return max;
  }

  private static double cosineSimilarity(float[] a, float[] b) {
    if (a.length != b.length) {
      return 0.0;
    }
    double dot = 0.0;
    double normA = 0.0;
    double normB = 0.0;
    for (int i = 0; i < a.length; i++) {
      dot += (double) a[i] * b[i];
      normA += (double) a[i] * a[i];
      normB += (double) b[i] * b[i];
    }
    if (normA == 0.0 || normB == 0.0) {
      return 0.0;
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }
}
