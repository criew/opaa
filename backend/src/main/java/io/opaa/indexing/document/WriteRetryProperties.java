package io.opaa.indexing.document;

import static io.opaa.common.PropertyDefaults.positiveOr;

import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds of {@link ConnectionLossRetry}: how often and how long a document's or an upload's ingest
 * is repeated after its write lost the database connection.
 *
 * @param maxAttempts attempts in all, the first included; {@code 1} repeats nothing. Default 5:
 *     every repetition after the first pause waits in the connection pool for up to its timeout (30
 *     seconds) until the database answers again, so two or three attempts cover a failover of a
 *     replicated database (about 20 seconds without a primary when the primary fails); the rest
 *     cover a loss that recurs while the new primary settles. Zero or negative falls back to it.
 * @param window how long after the first lost connection a further attempt may still start. Default
 *     60 seconds: twice the longest failover measured, and well below {@code
 *     opaa.instance.dead-after}, so a repetition never outlives the instance's claim on its run.
 *     Zero or negative falls back to it.
 * @param initialDelay the pause before the first repetition, doubled before each further one up to
 *     ten seconds. Default 1 second: the pool has evicted the dead connection by then. Zero or
 *     negative falls back to it.
 */
@PerInstance("how this process repeats its own writes")
@NoDataImpact("repetition of a write after a lost database connection")
@ConfigurationProperties(prefix = "opaa.indexing.write-retry")
public record WriteRetryProperties(
    int maxAttempts,
    @NoDataImpact("how long a write is repeated") Duration window,
    @NoDataImpact("pause before a repetition") Duration initialDelay) {

  public WriteRetryProperties {
    maxAttempts = positiveOr(maxAttempts, 5);
    window = positiveOr(window, Duration.ofSeconds(60));
    initialDelay = positiveOr(initialDelay, Duration.ofSeconds(1));
  }
}
