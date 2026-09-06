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

    record(estimator, (int) EmbeddingRateEstimator.MIN_MEASURED_CHUNKS - 1, Duration.ofSeconds(1));
    assertThat(estimator.rateSource()).isEqualTo(EmbeddingRateEstimator.RateSource.CONFIGURED);
  }

  @Test
  void switchesToTheMeasuredMeanOnceTheThresholdIsReached() {
    EmbeddingRateEstimator estimator = new EmbeddingRateEstimator(2.0);

    // 40 chunks in 20 seconds: half a second per chunk, twice as slow as the configured rate.
    record(estimator, 40, Duration.ofSeconds(20));

    assertThat(estimator.rateSource()).isEqualTo(EmbeddingRateEstimator.RateSource.MEASURED);
    assertThat(estimator.secondsPerChunk()).isEqualTo(0.5);
    assertThat(estimator.estimatedSeconds(100)).isEqualTo(50);
  }

  @Test
  void ignoresANonMeasurementAndEstimatesNothingForNoChunks() {
    EmbeddingRateEstimator estimator = new EmbeddingRateEstimator(4.0);
    record(estimator, 0, Duration.ofNanos(5));
    record(estimator, 5, Duration.ZERO);

    assertThat(estimator.rateSource()).isEqualTo(EmbeddingRateEstimator.RateSource.CONFIGURED);
    assertThat(estimator.estimatedSeconds(0)).isZero();
  }

  @Test
  void fallsBackToTheDefaultRateForANonsensicalConfiguration() {
    assertThat(new EmbeddingRateEstimator(0).secondsPerChunk()).isEqualTo(0.25);
    assertThat(new EmbeddingRateEstimator(-1).secondsPerChunk()).isEqualTo(0.25);
  }

  @Test
  void dropsTheWallTimeOfOverlappingCallsInsteadOfSummingThemIntoTheMean() {
    EmbeddingRateEstimator estimator = new EmbeddingRateEstimator(2.0);

    // Two concurrent sub-batches: their wall times overlap, so neither is a throughput measurement.
    long first = estimator.started();
    long second = estimator.started();
    estimator.record(1000, Duration.ofSeconds(1000).toNanos(), second);
    estimator.record(1000, Duration.ofSeconds(1000).toNanos(), first);

    assertThat(estimator.rateSource()).isEqualTo(EmbeddingRateEstimator.RateSource.CONFIGURED);
    assertThat(estimator.secondsPerChunk()).isEqualTo(0.5);
  }

  /** One embedding call that ran alone, the only kind that contributes a measurement. */
  private static void record(EmbeddingRateEstimator estimator, int chunks, Duration took) {
    estimator.record(chunks, took.toNanos(), estimator.started());
  }
}
