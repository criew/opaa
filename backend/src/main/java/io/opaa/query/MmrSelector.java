package io.opaa.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;

/**
 * Maximal Marginal Relevance (MMR) re-ranking of {@code similaritySearch} candidates (#914,
 * Maßnahme A - see #912 for the failure it addresses): a dominant topic's near-duplicate chunks
 * used to fill every {@code topK} slot on a multi-topic question, crowding out a second, less
 * dominant topic entirely. {@link #select} instead builds the final selection greedily - the first
 * pick is always the highest-relevance candidate, and every following pick maximizes {@code
 * mmrLambda * relevance - (1 - mmrLambda) * maxSimilarityToAlreadySelected} - so a candidate that
 * merely repeats an already-selected chunk's content loses ground to a less relevant but topically
 * distinct one.
 *
 * <p><b>Pairwise similarity is cosine similarity of the real chunk embeddings</b>, read back from
 * the pgvector table by row id via {@link ChunkEmbeddingLookup} - not an embedding-API call (the
 * vector already sits in the row {@code similaritySearch} itself read to compute its distance) and
 * not a lexical approximation. A candidate whose id is missing from {@code embeddingsByChunkId}
 * (deleted between the search and this lookup, or simply never resolved) contributes {@code 0.0}
 * similarity to every comparison it takes part in - a defensive fallback, not a claim that the
 * chunk is actually dissimilar; it only ever matters for a race this narrow a window makes
 * exceedingly unlikely.
 *
 * <p><b>Scale note:</b> candidate relevance scores from {@code similaritySearch} typically differ
 * by as little as ~0.02 between neighbors, while cosine similarities between candidates commonly
 * span ~0.3-0.5 - the diversity term can therefore dominate the relevance term even at a
 * relevance-favoring {@code mmrLambda} unless the caller accounts for that scale mismatch when
 * choosing it. {@link QueryProperties#mmrLambda()}'s Javadoc documents the measured effect this had
 * on {@code mmrLambda}'s chosen default.
 *
 * <p><b>The mismatch is sharper for the lexical path's lists</b> (#1049): their relevance is a
 * {@code ts_rank}, which lives around 0.06 to 0.1 rather than the 0.3 to 0.9 of a cosine
 * similarity, while the diversity term keeps using cosine similarities of the chunk embeddings. At
 * any {@code mmrLambda < 1.0} - a supported operator value, not a hypothetical - the diversity term
 * therefore dominates a lexical list's ranking essentially completely, and the list that reaches
 * the fusion is ordered by diversity rather than by lexical relevance. At the shipped default of
 * {@code 1.0} the diversity term is multiplied by zero and the question does not arise. Making the
 * two paths comparable here would need a per-path normalization, which is a measured decision and
 * not made in #1049.
 */
final class MmrSelector {

  private MmrSelector() {}

  /**
   * Selects at most {@code topK} candidates from {@code candidates} (already threshold-filtered and
   * permission-scoped by the caller's {@code similaritySearch} call - this method only ever narrows
   * that set, never widens it). Returns fewer than {@code topK} entries when {@code candidates} is
   * smaller, and an empty list for an empty or non-positive-{@code topK} input. {@code mmrLambda =
   * 1.0} reproduces plain top-{@code topK}-by-{@link Document#getScore()} selection, since the
   * diversity term is then always multiplied by zero - callers are expected to skip the {@link
   * ChunkEmbeddingLookup} round trip entirely in that case (see {@code QueryService#query}), so
   * {@code embeddingsByChunkId} may legitimately be {@link Map#of()} whenever {@code mmrLambda ==
   * 1.0}.
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
