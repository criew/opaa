package io.opaa.indexing.source.smb;

import static io.opaa.common.PropertyDefaults.notNegativeOr;
import static io.opaa.common.PropertyDefaults.positiveOr;

import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational bounds of the SMB connector ({@code opaa.indexing.smb.*}). A value of {@code 0} or an
 * absent one falls back to its default; a negative one is refused.
 *
 * @param maxFileSizeBytes upper bound of one download, checked against the listed size and while
 *     copying
 * @param requestTimeout connect timeout and per-message timeout of a run
 * @param requestBudgetPerRun SMB messages one run may send before it ends as truncated; a download
 *     costs at least four (open, check, read, close), a folder at least five
 * @param maxEntriesPerRun listed files per run before the run fails visibly
 * @param downloadConcurrency downloads one run keeps in flight; {@code 1} is serial
 * @param listPageSize entries per listing page, so a large folder is processed while it is read
 */
@PerInstance("budgets and bounds of a run on this process")
@NoDataImpact("technical bounds, budgets and timeouts")
@ConfigurationProperties(prefix = "opaa.indexing.smb")
public record SmbProperties(
    long maxFileSizeBytes,
    @NoDataImpact("a timeout of one request") Duration requestTimeout,
    int requestBudgetPerRun,
    int maxEntriesPerRun,
    int downloadConcurrency,
    int listPageSize) {

  public static final long DEFAULT_MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;
  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
  public static final int DEFAULT_REQUEST_BUDGET_PER_RUN = 100_000;
  public static final int DEFAULT_MAX_ENTRIES_PER_RUN = 1_000_000;
  public static final int DEFAULT_DOWNLOAD_CONCURRENCY = 2;
  public static final int DEFAULT_LIST_PAGE_SIZE = 1_000;

  public SmbProperties {
    maxFileSizeBytes =
        notNegativeOr("maxFileSizeBytes", maxFileSizeBytes, DEFAULT_MAX_FILE_SIZE_BYTES);
    requestTimeout = positiveOr(requestTimeout, DEFAULT_REQUEST_TIMEOUT);
    requestBudgetPerRun =
        notNegativeOr("requestBudgetPerRun", requestBudgetPerRun, DEFAULT_REQUEST_BUDGET_PER_RUN);
    maxEntriesPerRun =
        notNegativeOr("maxEntriesPerRun", maxEntriesPerRun, DEFAULT_MAX_ENTRIES_PER_RUN);
    downloadConcurrency =
        notNegativeOr("downloadConcurrency", downloadConcurrency, DEFAULT_DOWNLOAD_CONCURRENCY);
    listPageSize = notNegativeOr("listPageSize", listPageSize, DEFAULT_LIST_PAGE_SIZE);
  }

  /** All defaults. */
  public static SmbProperties defaults() {
    return new SmbProperties(0, null, 0, 0, 0, 0);
  }
}
