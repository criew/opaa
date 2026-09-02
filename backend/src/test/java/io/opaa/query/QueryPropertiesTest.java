package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link QueryProperties}'s compact-constructor defaults and validation (#914,
 * #923).
 */
class QueryPropertiesTest {

  @Test
  void nonPositiveTopKDefaultsToEight() {
    QueryProperties properties = new QueryProperties(0, 25, 0.7, 0.3, 1.0, true, 3, 2, true, 50);

    assertThat(properties.topK()).isEqualTo(8);
  }

  @Test
  void topKAboveTheMaximumIsRejected() {
    assertThatThrownBy(() -> new QueryProperties(101, 200, 0.7, 0.3, 1.0, true, 3, 2, true, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("topK");
  }

  /** Default fetchK is 25 when topK stays within the default range. */
  @Test
  void nonPositiveFetchKDefaultsToTwentyFiveWhenTopKIsSmall() {
    QueryProperties properties = new QueryProperties(8, 0, 0.7, 0.3, 1.0, true, 3, 2, true, 50);

    assertThat(properties.fetchK()).isEqualTo(25);
  }

  /**
   * A deployment that already configured {@code topK} above 25 must not fail startup just because
   * it never set {@code fetchK} - the missing value normalizes to {@code max(25, topK)}, not a flat
   * 25.
   */
  @Test
  void nonPositiveFetchKNormalizesToTopKWhenTopKExceedsTwentyFive() {
    QueryProperties properties = new QueryProperties(30, 0, 0.7, 0.3, 1.0, true, 3, 2, true, 50);

    assertThat(properties.fetchK()).isEqualTo(30);
  }

  @Test
  void fetchKAboveTheMaximumIsRejected() {
    assertThatThrownBy(() -> new QueryProperties(8, 201, 0.7, 0.3, 1.0, true, 3, 2, true, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fetchK");
  }

  @Test
  void fetchKBelowTopKIsRejected() {
    assertThatThrownBy(() -> new QueryProperties(10, 5, 0.7, 0.3, 1.0, true, 3, 2, true, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fetchK");
  }

  /**
   * {@code mmrLambda = 0.0} is a legal boundary value (pure diversity, no relevance term), not an
   * "unset" sentinel - it must be honored, not silently raised to the default.
   */
  @Test
  void mmrLambdaZeroIsAcceptedAsIs() {
    QueryProperties properties = new QueryProperties(8, 25, 0.0, 0.3, 1.0, true, 3, 2, true, 50);

    assertThat(properties.mmrLambda()).isEqualTo(0.0);
  }

  @Test
  void mmrLambdaOneIsAcceptedAsIs() {
    QueryProperties properties = new QueryProperties(8, 25, 1.0, 0.3, 1.0, true, 3, 2, true, 50);

    assertThat(properties.mmrLambda()).isEqualTo(1.0);
  }

  @Test
  void mmrLambdaBelowZeroIsRejected() {
    assertThatThrownBy(() -> new QueryProperties(8, 25, -0.1, 0.3, 1.0, true, 3, 2, true, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mmrLambda");
  }

  @Test
  void mmrLambdaAboveOneIsRejected() {
    assertThatThrownBy(() -> new QueryProperties(8, 25, 1.1, 0.3, 1.0, true, 3, 2, true, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mmrLambda");
  }

  @Test
  void similarityThresholdOutsideRangeIsRejected() {
    assertThatThrownBy(() -> new QueryProperties(8, 25, 0.7, 1.5, 1.0, true, 3, 2, true, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("similarityThreshold");
  }

  @Test
  void permissionHistorySampleRateOutsideRangeIsRejected() {
    assertThatThrownBy(() -> new QueryProperties(8, 25, 0.7, 0.3, 1.5, true, 3, 2, true, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("permissionHistorySampleRate");
  }

  /** {@code queryDecompositionEnabled} is a plain boolean - both values are legal as-is. */
  @Test
  void queryDecompositionEnabledFalseIsAcceptedAsIs() {
    QueryProperties properties = new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 2, true, 50);

    assertThat(properties.queryDecompositionEnabled()).isFalse();
  }

  @Test
  void maxSubQueriesOfOneIsAcceptedAsIs() {
    QueryProperties properties = new QueryProperties(8, 25, 1.0, 0.3, 1.0, true, 1, 2, true, 50);

    assertThat(properties.maxSubQueries()).isEqualTo(1);
  }

  @Test
  void nonPositiveMaxSubQueriesIsRejected() {
    assertThatThrownBy(() -> new QueryProperties(8, 25, 1.0, 0.3, 1.0, true, 0, 2, true, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxSubQueries");
  }

  @Test
  void maxSubQueriesAboveTheMaximumIsRejected() {
    assertThatThrownBy(() -> new QueryProperties(8, 25, 1.0, 0.3, 1.0, true, 11, 2, true, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxSubQueries");
  }

  /** {@code 1} is the documented opt-out value (#932) - it must not be rejected as "unset". */
  @Test
  void maxChunksPerDocumentOfOneIsAcceptedAsIs() {
    QueryProperties properties = new QueryProperties(8, 25, 1.0, 0.3, 1.0, true, 3, 1, true, 50);

    assertThat(properties.maxChunksPerDocument()).isEqualTo(1);
  }

  @Test
  void nonPositiveMaxChunksPerDocumentIsRejected() {
    assertThatThrownBy(() -> new QueryProperties(8, 25, 1.0, 0.3, 1.0, true, 3, 0, true, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxChunksPerDocument");
  }

  @Test
  void maxChunksPerDocumentAboveTheMaximumIsRejected() {
    assertThatThrownBy(() -> new QueryProperties(8, 25, 1.0, 0.3, 1.0, true, 3, 11, true, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxChunksPerDocument");
  }
}
