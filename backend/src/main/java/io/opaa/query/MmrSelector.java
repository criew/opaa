package io.opaa.query;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * <p><b>Pairwise similarity is lexical, not vector-based</b> (#914 issue discussion): {@code
 * org.springframework.ai.vectorstore.pgvector.PgVectorStore} (spring-ai-pgvector-store 2.0.0) never
 * puts the stored embedding on the {@link Document} it returns from {@code similaritySearch} - its
 * {@code DocumentRowMapper} builds the result from only {@code id}/{@code content}/{@code
 * metadata}/{@code distance}, and {@link Document} itself carries no embedding field at all.
 * Re-embedding every candidate to get a real vector would mean an extra embedding-API call per
 * query, which #914 explicitly rules out. Instead, this class approximates similarity with the
 * Jaccard index over each chunk's lowercase word-token set - cheap, deterministic, and effective at
 * the redundancy this method exists to catch: two chunks that restate the same passage (the
 * near-duplicate case #912 observed within one dominant topic) share most of their vocabulary,
 * while chunks from genuinely different topics do not.
 */
final class MmrSelector {

  private MmrSelector() {}

  /**
   * Selects at most {@code topK} candidates from {@code candidates} (already threshold-filtered and
   * permission-scoped by the caller's {@code similaritySearch} call - this method only ever narrows
   * that set, never widens it). Returns fewer than {@code topK} entries when {@code candidates} is
   * smaller, and an empty list for an empty or non-positive-{@code topK} input. {@code mmrLambda =
   * 1.0} reproduces plain top-{@code topK}-by-{@link Document#getScore()} selection, since the
   * diversity term is then always multiplied by zero.
   */
  static List<Document> select(List<Document> candidates, int topK, double mmrLambda) {
    if (candidates.isEmpty() || topK <= 0) {
      return List.of();
    }

    List<Document> remaining = new ArrayList<>(candidates);
    List<Set<String>> remainingTokens = new ArrayList<>(candidates.size());
    for (Document candidate : candidates) {
      remainingTokens.add(tokenize(candidate));
    }

    List<Document> selected = new ArrayList<>(Math.min(topK, candidates.size()));
    List<Set<String>> selectedTokens = new ArrayList<>(selected.size());

    while (!remaining.isEmpty() && selected.size() < topK) {
      int bestIndex = 0;
      double bestScore = Double.NEGATIVE_INFINITY;
      for (int i = 0; i < remaining.size(); i++) {
        double relevance = relevanceOf(remaining.get(i));
        double maxSimilarityToSelected = maxSimilarity(remainingTokens.get(i), selectedTokens);
        double mmrScore = mmrLambda * relevance - (1 - mmrLambda) * maxSimilarityToSelected;
        if (mmrScore > bestScore) {
          bestScore = mmrScore;
          bestIndex = i;
        }
      }
      selected.add(remaining.remove(bestIndex));
      selectedTokens.add(remainingTokens.remove(bestIndex));
    }
    return selected;
  }

  private static double relevanceOf(Document document) {
    Double score = document.getScore();
    return score != null ? score : 0.0;
  }

  private static double maxSimilarity(Set<String> tokens, List<Set<String>> selectedTokens) {
    double max = 0.0;
    for (Set<String> other : selectedTokens) {
      double similarity = jaccard(tokens, other);
      if (similarity > max) {
        max = similarity;
      }
    }
    return max;
  }

  private static Set<String> tokenize(Document document) {
    String text = document.getText();
    if (text == null || text.isBlank()) {
      return Set.of();
    }
    Set<String> tokens = new HashSet<>();
    for (String token : text.toLowerCase(Locale.ROOT).split("\\W+")) {
      if (!token.isBlank()) {
        tokens.add(token);
      }
    }
    return tokens;
  }

  private static double jaccard(Set<String> a, Set<String> b) {
    if (a.isEmpty() || b.isEmpty()) {
      return 0.0;
    }
    Set<String> intersection = new HashSet<>(a);
    intersection.retainAll(b);
    if (intersection.isEmpty()) {
      return 0.0;
    }
    Set<String> union = new HashSet<>(a);
    union.addAll(b);
    return (double) intersection.size() / union.size();
  }
}
