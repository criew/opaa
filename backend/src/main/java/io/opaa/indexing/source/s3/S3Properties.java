package io.opaa.indexing.source.s3;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational bounds of the S3 access layer (ADR-0027, Entscheidung 11). The values here are the
 * layer's own; the run-level bounds (object count, download concurrency, event intake) join with
 * the executor.
 *
 * @param listPageSize {@code MaxKeys} of every {@code ListObjectsV2} call, at most {@link
 *     #MAX_LIST_PAGE_SIZE} - S3 caps the page there. Default 1000; {@code 0} falls back to it, a
 *     negative or larger value is rejected.
 * @param maxObjectSizeBytes upper bound for a single object download, applied to the listed size
 *     before the download and to the byte stream during it. Default 50 MiB.
 * @param requestTimeout per-attempt timeout: connection, socket and API-call-attempt timeout are
 *     all derived from this one value. Default 30 seconds.
 * @param maxRetries how many times one call is retried after {@code 503 SlowDown}/{@code 429} or a
 *     transient transport failure before it gives up. Default 5 when absent; {@code 0} turns
 *     retries off.
 * @param retryBackoff the base of the exponential backoff between retries. Default 500 ms.
 * @param requestBudgetPerRun how many requests one run may send before it ends in an orderly way as
 *     truncated; retries and the {@code HeadObject} of extension-less keys count. Applied only to a
 *     run's store ({@link S3ClientFactory#createForRun}), never to the wizard's probes. Zero
 *     disables the budget; the default is {@link #DEFAULT_REQUEST_BUDGET_PER_RUN}.
 * @param tempDirectory where downloads are written before the caller takes them over; {@code null}
 *     means the JVM's temporary directory.
 */
@ConfigurationProperties(prefix = "opaa.indexing.s3")
public record S3Properties(
    int listPageSize,
    long maxObjectSizeBytes,
    Duration requestTimeout,
    Integer maxRetries,
    Duration retryBackoff,
    int requestBudgetPerRun,
    Path tempDirectory) {

  public static final int MAX_LIST_PAGE_SIZE = 1000;

  /**
   * Calls per run before the run ends as truncated: one call per {@link #MAX_LIST_PAGE_SIZE} listed
   * objects, one per extension-less object (its {@code HeadObject}), one per changed object (its
   * download) - so 20 000 cover a million unchanged objects with extensions plus roughly 18 000
   * downloads. A starting value, not a measured one.
   */
  public static final int DEFAULT_REQUEST_BUDGET_PER_RUN = 20_000;

  public S3Properties {
    if (listPageSize < 0 || listPageSize > MAX_LIST_PAGE_SIZE) {
      throw new IllegalArgumentException(
          "listPageSize must lie between 1 and " + MAX_LIST_PAGE_SIZE + ", got " + listPageSize);
    }
    if (listPageSize == 0) {
      listPageSize = MAX_LIST_PAGE_SIZE;
    }
    if (maxObjectSizeBytes <= 0) {
      maxObjectSizeBytes = 50L * 1024 * 1024;
    }
    if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
      requestTimeout = Duration.ofSeconds(30);
    }
    if (maxRetries == null) {
      maxRetries = 5;
    }
    if (maxRetries < 0) {
      throw new IllegalArgumentException("maxRetries must not be negative, got " + maxRetries);
    }
    if (retryBackoff == null || retryBackoff.isZero() || retryBackoff.isNegative()) {
      retryBackoff = Duration.ofMillis(500);
    }
    if (requestBudgetPerRun < 0) {
      throw new IllegalArgumentException(
          "requestBudgetPerRun must not be negative, got " + requestBudgetPerRun);
    }
    if (tempDirectory == null) {
      tempDirectory = Path.of(System.getProperty("java.io.tmpdir"));
    }
  }

  /** {@code true} when a run is bounded by {@link #requestBudgetPerRun}; zero means unbounded. */
  public boolean hasRequestBudget() {
    return requestBudgetPerRun > 0;
  }

  /** All defaults - for callers and tests that need a properties instance without configuration. */
  public static S3Properties defaults() {
    return new S3Properties(0, 0, null, null, null, DEFAULT_REQUEST_BUDGET_PER_RUN, null);
  }
}
