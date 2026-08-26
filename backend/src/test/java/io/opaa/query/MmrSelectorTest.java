package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * Deterministic unit tests for {@link MmrSelector} using hand-picked embedding vectors (not real
 * ones - {@link ChunkEmbeddingLookup} needs a database, exercised separately by {@code
 * QueryIntegrationTest}), so every candidate's pairwise cosine similarity is known exactly in
 * advance.
 */
class MmrSelectorTest {

  private static Document chunk(String id, double score) {
    return Document.builder().id(id).text("chunk " + id).metadata(Map.of()).score(score).build();
  }

  /**
   * The #912 scenario in miniature: two near-duplicate high-scoring chunks (identical embedding
   * direction, one dominant topic) plus a lower-scoring but orthogonal (cosine similarity 0) third
   * chunk from a topically distinct second topic. At {@code mmrLambda = 0.5}, the second
   * near-duplicate must lose to the distinct chunk - proving redundancy is actually penalized, not
   * merely that relevance order is preserved.
   */
  @Test
  void redundantCandidateLosesToATopicallyDistinctLowerScoringOne() {
    Document mostRelevant = chunk("a", 0.90);
    Document nearDuplicate = chunk("b", 0.89);
    Document distinctTopic = chunk("c", 0.70);
    Map<String, float[]> embeddings =
        Map.of(
            "a", new float[] {1f, 0f},
            "b", new float[] {1f, 0f}, // identical direction to "a" - cosine similarity 1.0
            "c", new float[] {0f, 1f} // orthogonal to "a"/"b" - cosine similarity 0.0
            );

    List<Document> selected =
        MmrSelector.select(List.of(mostRelevant, nearDuplicate, distinctTopic), 2, 0.5, embeddings);

    assertThat(selected).extracting(Document::getId).containsExactly("a", "c");
  }

  /** {@code mmrLambda = 1.0} disables the diversity term entirely - plain top-K by relevance. */
  @Test
  void lambdaOneReproducesPureTopKByRelevance() {
    Document mostRelevant = chunk("a", 0.90);
    Document nearDuplicate = chunk("b", 0.89);
    Document distinctTopic = chunk("c", 0.70);
    Map<String, float[]> embeddings =
        Map.of(
            "a", new float[] {1f, 0f},
            "b", new float[] {1f, 0f},
            "c", new float[] {0f, 1f});

    List<Document> selected =
        MmrSelector.select(List.of(mostRelevant, nearDuplicate, distinctTopic), 2, 1.0, embeddings);

    assertThat(selected).extracting(Document::getId).containsExactly("a", "b");
  }

  /**
   * {@code mmrLambda = 0.0} is a legal boundary value (pure diversity, no relevance term at all).
   */
  @Test
  void lambdaZeroIgnoresRelevanceEntirelyAfterTheFirstPick() {
    Document mostRelevant = chunk("a", 0.90);
    Document nearDuplicate = chunk("b", 0.89);
    Document distinctTopic = chunk("c", 0.10);
    Map<String, float[]> embeddings =
        Map.of(
            "a", new float[] {1f, 0f},
            "b", new float[] {1f, 0f},
            "c", new float[] {0f, 1f});

    List<Document> selected =
        MmrSelector.select(List.of(mostRelevant, nearDuplicate, distinctTopic), 2, 0.0, embeddings);

    // First pick is still the highest relevance (nothing selected yet to be similar to); second
    // pick ignores relevance and picks whichever remaining candidate is least similar to "a" - "c"
    // (similarity 0.0) beats "b" (similarity 1.0) even though "c" has far lower relevance.
    assertThat(selected).extracting(Document::getId).containsExactly("a", "c");
  }

  /** Fewer candidates than {@code topK}: every candidate is selected, none invented. */
  @Test
  void selectsAllCandidatesWhenFewerThanTopK() {
    Document a = chunk("a", 0.8);
    Document b = chunk("b", 0.6);
    Map<String, float[]> embeddings = Map.of("a", new float[] {1f, 0f}, "b", new float[] {0f, 1f});

    List<Document> selected = MmrSelector.select(List.of(a, b), 5, 0.7, embeddings);

    assertThat(selected).extracting(Document::getId).containsExactly("a", "b");
  }

  @Test
  void selectsNothingFromAnEmptyCandidateList() {
    assertThat(MmrSelector.select(List.of(), 5, 0.7, Map.of())).isEmpty();
  }

  @Test
  void selectsNothingWhenTopKIsZero() {
    Document a = chunk("a", 0.8);

    assertThat(MmrSelector.select(List.of(a), 0, 0.7, Map.of("a", new float[] {1f, 0f}))).isEmpty();
  }

  /**
   * The first pick is always the highest-relevance candidate regardless of its position in the
   * input list - the diversity term only ever affects picks after the first, since there is nothing
   * selected yet to be similar to.
   */
  @Test
  void firstPickIsAlwaysTheHighestRelevanceCandidate() {
    Document low = chunk("low", 0.4);
    Document high = chunk("high", 0.95);
    Map<String, float[]> embeddings =
        Map.of("low", new float[] {1f, 0f}, "high", new float[] {0f, 1f});

    List<Document> selected = MmrSelector.select(List.of(low, high), 1, 0.5, embeddings);

    assertThat(selected).extracting(Document::getId).containsExactly("high");
  }

  /** A candidate with no score at all (null) is treated as relevance 0, never as the best pick. */
  @Test
  void treatsANullScoreAsZeroRelevance() {
    Document scored = chunk("scored", 0.5);
    Document unscored =
        Document.builder().id("unscored").text("chunk unscored").metadata(Map.of()).build();
    Map<String, float[]> embeddings =
        Map.of("scored", new float[] {1f, 0f}, "unscored", new float[] {0f, 1f});

    List<Document> selected = MmrSelector.select(List.of(unscored, scored), 2, 1.0, embeddings);

    assertThat(selected).extracting(Document::getId).containsExactly("scored", "unscored");
  }

  /**
   * A candidate missing from {@code embeddingsByChunkId} (e.g. deleted between {@code
   * similaritySearch} and {@link ChunkEmbeddingLookup}) contributes {@code 0.0} similarity to every
   * comparison instead of failing the whole selection - see {@link MmrSelector}'s Javadoc.
   */
  @Test
  void treatsAMissingEmbeddingAsZeroSimilarityRatherThanFailing() {
    Document a = chunk("a", 0.9);
    Document bWithoutEmbedding = chunk("b", 0.8);
    Map<String, float[]> embeddings = Map.of("a", new float[] {1f, 0f});

    List<Document> selected = MmrSelector.select(List.of(a, bWithoutEmbedding), 2, 0.5, embeddings);

    assertThat(selected).extracting(Document::getId).containsExactly("a", "b");
  }
}
