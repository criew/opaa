package io.opaa.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The cost preview's rate with two instances against one database (ADR-0053, Entscheidung 12): the
 * calls instance A measured make the estimate of instance B as well, so both name the same duration
 * for the same number of chunks. Each estimator counts under a model of its own here, so the
 * measurements of the rest of the suite do not enter.
 */
@OpaaIntegrationTest
class EmbeddingRateTwoInstanceIntegrationTest {

  @Autowired private JdbcTemplate jdbc;

  private final String model = "rate-test-" + UUID.randomUUID();

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM embedding_rate_measurements WHERE embedding_model = ?", model);
  }

  @Test
  void theCallsMeasuredOnOneInstanceSetTheRateOnTheOther() {
    EmbeddingRateEstimator onA = estimator();
    EmbeddingRateEstimator onB = estimator();

    // 40 chunks in 20 seconds on A: half a second per chunk, twice as slow as configured
    onA.record(40, Duration.ofSeconds(20).toNanos(), onA.started());

    assertThat(onB.rateSource()).isEqualTo(EmbeddingRateEstimator.RateSource.MEASURED);
    assertThat(onB.secondsPerChunk()).isEqualTo(0.5);
    assertThat(onB.estimatedSeconds(100)).isEqualTo(onA.estimatedSeconds(100)).isEqualTo(50);
  }

  @Test
  void aPortionBelowTheFlushSizeStaysWithItsInstanceUntilItStops() {
    EmbeddingRateEstimator onA = estimator();
    EmbeddingRateEstimator onB = estimator();
    onA.record(30, Duration.ofSeconds(15).toNanos(), onA.started());
    onA.record(5, Duration.ofSeconds(5).toNanos(), onA.started());

    assertThat(onB.secondsPerChunk()).isEqualTo(0.5);
    assertThat(onA.secondsPerChunk()).isEqualTo(20.0 / 35);

    onA.flushPending();

    assertThat(onB.secondsPerChunk()).isEqualTo(20.0 / 35);
  }

  private EmbeddingRateEstimator estimator() {
    return new EmbeddingRateEstimator(4.0, new JdbcEmbeddingRateMeasurements(jdbc, model));
  }
}
