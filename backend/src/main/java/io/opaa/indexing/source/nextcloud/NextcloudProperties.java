package io.opaa.indexing.source.nextcloud;

import static io.opaa.common.PropertyDefaults.notNegativeOr;
import static io.opaa.common.PropertyDefaults.positiveOr;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational bounds of the Nextcloud connector ({@code opaa.indexing.nextcloud.*}). A value of
 * {@code 0} or an absent one falls back to its default; a negative one is refused.
 *
 * @param maxFileSizeBytes upper bound of one download, checked against the listed size and while
 *     streaming
 * @param requestTimeout per-request timeout of a run's {@code PROPFIND} and download
 * @param requestBudgetPerRun requests one run may send before it ends as truncated
 * @param maxEntriesPerRun listed files per run before the run fails visibly
 * @param downloadConcurrency downloads one run keeps in flight; {@code 1} is serial
 * @param maxResponseBytes upper bound of one {@code PROPFIND} answer
 * @param fullDescentInterval how long remembered folder ETags may spare a listing before a full
 *     sync lists every folder again
 */
@ConfigurationProperties(prefix = "opaa.indexing.nextcloud")
public record NextcloudProperties(
    long maxFileSizeBytes,
    Duration requestTimeout,
    int requestBudgetPerRun,
    int maxEntriesPerRun,
    int downloadConcurrency,
    long maxResponseBytes,
    Duration fullDescentInterval) {

  public static final long DEFAULT_MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;
  public static final int DEFAULT_REQUEST_BUDGET_PER_RUN = 20_000;
  public static final int DEFAULT_MAX_ENTRIES_PER_RUN = 1_000_000;
  public static final int DEFAULT_DOWNLOAD_CONCURRENCY = 2;
  public static final long DEFAULT_MAX_RESPONSE_BYTES = 64L * 1024 * 1024;
  public static final Duration DEFAULT_FULL_DESCENT_INTERVAL = Duration.ofDays(7);

  public NextcloudProperties {
    maxFileSizeBytes =
        notNegativeOr("maxFileSizeBytes", maxFileSizeBytes, DEFAULT_MAX_FILE_SIZE_BYTES);
    requestTimeout = positiveOr(requestTimeout, Duration.ofSeconds(30));
    requestBudgetPerRun =
        notNegativeOr("requestBudgetPerRun", requestBudgetPerRun, DEFAULT_REQUEST_BUDGET_PER_RUN);
    maxEntriesPerRun =
        notNegativeOr("maxEntriesPerRun", maxEntriesPerRun, DEFAULT_MAX_ENTRIES_PER_RUN);
    downloadConcurrency =
        notNegativeOr("downloadConcurrency", downloadConcurrency, DEFAULT_DOWNLOAD_CONCURRENCY);
    maxResponseBytes =
        notNegativeOr("maxResponseBytes", maxResponseBytes, DEFAULT_MAX_RESPONSE_BYTES);
    fullDescentInterval = positiveOr(fullDescentInterval, DEFAULT_FULL_DESCENT_INTERVAL);
  }

  /** All defaults. */
  public static NextcloudProperties defaults() {
    return new NextcloudProperties(0, null, 0, 0, 0, 0, null);
  }
}
