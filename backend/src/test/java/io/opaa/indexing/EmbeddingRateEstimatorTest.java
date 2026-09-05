package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link EmbeddingRateEstimator} (#1072): the runtime estimate behind "4.812 Chunks, rund 40
 * Minuten" - configured until enough calls are measured, measured afterwards.
 */
class EmbeddingRateEstimatorTest {

  @Test
  void usesTheConfiguredRateUntilEnoughChunksHaveBeenMeasured() {
    EmbeddingRateEstimator estimator = new EmbeddingRateEstimator(2.0);

    assertThat(estimator.rateSource()).isEqualTo(EmbeddingRateEstimator.RateSource.CONFIGURED);
    assertThat(estimator.estimatedSeconds(100)).isEqualTo(50);

    estimator.record(
        (int) EmbeddingRateEstimator.MIN_MEASURED_CHUNKS - 1, Duration.ofSeconds(1).toNanos());
    assertThat(estimator.rateSource()).isEqualTo(EmbeddingRateEstimator.RateSource.CONFIGURED);
  }

  @Test
  void switchesToTheMeasuredMeanOnceTheThresholdIsReached() {
    EmbeddingRateEstimator estimator = new EmbeddingRateEstimator(2.0);

    // 40 chunks in 20 seconds: half a second per chunk, twice as slow as the configured rate.
    estimator.record(40, Duration.ofSeconds(20).toNanos());

    assertThat(estimator.rateSource()).isEqualTo(EmbeddingRateEstimator.RateSource.MEASURED);
    assertThat(estimator.secondsPerChunk()).isEqualTo(0.5);
    assertThat(estimator.estimatedSeconds(100)).isEqualTo(50);
  }

  @Test
  void ignoresANonMeasurementAndEstimatesNothingForNoChunks() {
    EmbeddingRateEstimator estimator = new EmbeddingRateEstimator(4.0);
    estimator.record(0, 5);
    estimator.record(5, 0);

    assertThat(estimator.rateSource()).isEqualTo(EmbeddingRateEstimator.RateSource.CONFIGURED);
    assertThat(estimator.estimatedSeconds(0)).isZero();
  }

  @Test
  void fallsBackToTheDefaultRateForANonsensicalConfiguration() {
    assertThat(new EmbeddingRateEstimator(0).secondsPerChunk()).isEqualTo(0.25);
    assertThat(new EmbeddingRateEstimator(-1).secondsPerChunk()).isEqualTo(0.25);
  }
}
