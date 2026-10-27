package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The comparison with the reference anchor (ADR-0061, Entscheidung 5): the smallest cosine
 * similarity over the anchor texts decides, so one text embedded differently is enough to fail.
 */
class EmbeddingAnchorTest {

  @Test
  void identicalVectorsAreFullySimilar() {
    List<float[]> vectors = List.of(vector(1, 0), vector(0, 1), vector(1, 1));

    assertThat(EmbeddingAnchor.smallestSimilarity(EmbeddingAnchor.flatten(vectors), vectors))
        .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
  }

  @Test
  void theSmallestSimilarityOfTheTextsDecides() {
    float[] anchor = EmbeddingAnchor.flatten(List.of(vector(1, 0), vector(0, 1), vector(1, 1)));

    double similarity =
        EmbeddingAnchor.smallestSimilarity(
            anchor, List.of(vector(1, 0), vector(0, 1), vector(1, 0)));

    assertThat(similarity).isLessThan(EmbeddingAnchor.MIN_SIMILARITY).isGreaterThan(0.7);
  }

  @Test
  void justAboveTheBoundStillCountsAsTheSameModel() {
    float[] anchor = EmbeddingAnchor.flatten(List.of(vector(1, 0), vector(1, 0), vector(1, 0)));
    // cos(angle) = 0.995 for the third text, the other two identical
    float[] slightlyOff = vector(0.995f, (float) Math.sqrt(1 - 0.995 * 0.995));

    double similarity =
        EmbeddingAnchor.smallestSimilarity(
            anchor, List.of(vector(1, 0), vector(1, 0), slightlyOff));

    assertThat(similarity).isGreaterThanOrEqualTo(EmbeddingAnchor.MIN_SIMILARITY);
  }

  @Test
  void anotherWidthOrCountCannotBeCompared() {
    float[] anchor = EmbeddingAnchor.flatten(List.of(vector(1, 0), vector(0, 1), vector(1, 1)));

    assertThat(
            EmbeddingAnchor.smallestSimilarity(
                anchor, List.of(vector(1, 0, 0), vector(0, 1, 0), vector(1, 1, 0))))
        .isNaN();
    assertThat(EmbeddingAnchor.smallestSimilarity(anchor, List.of(vector(1, 0), vector(0, 1))))
        .isNaN();
    assertThat(EmbeddingAnchor.smallestSimilarity(null, List.of(vector(1, 0)))).isNaN();
  }

  private static float[] vector(float... values) {
    return values;
  }
}
