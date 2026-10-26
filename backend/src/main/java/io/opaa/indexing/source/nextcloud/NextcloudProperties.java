package io.opaa.indexing.source.nextcloud;

import static io.opaa.common.PropertyDefaults.notNegativeOr;
import static io.opaa.common.PropertyDefaults.positiveOr;

import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
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
 */
@PerInstance("budgets and bounds of a run on this process")
@NoDataImpact("technical bounds, budgets and timeouts")
@ConfigurationProperties(prefix = "opaa.indexing.nextcloud")
public record NextcloudProperties(
    long maxFileSizeBytes,
    @NoDataImpact("a timeout of one request") Duration requestTimeout,
    int requestBudgetPerRun,
    int maxEntriesPerRun,
    int downloadConcurrency,
    long maxResponseBytes) {

  public static final long DEFAULT_MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;
  public static final int DEFAULT_REQUEST_BUDGET_PER_RUN = 20_000;
  public static final int DEFAULT_MAX_ENTRIES_PER_RUN = 1_000_000;
  public static final int DEFAULT_DOWNLOAD_CONCURRENCY = 2;
  public static final long DEFAULT_MAX_RESPONSE_BYTES = 64L * 1024 * 1024;

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
  }

  /** All defaults. */
  public static NextcloudProperties defaults() {
    return new NextcloudProperties(0, null, 0, 0, 0, 0);
  }
}
