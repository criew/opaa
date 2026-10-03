package io.opaa.indexing.source.smb;

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
 *     costs at least three (open, read, close), a folder at least four
 * @param maxEntriesPerRun listed files per run before the run fails visibly
 * @param downloadConcurrency downloads one run keeps in flight; {@code 1} is serial
 * @param listPageSize entries per listing page, so a large folder is processed while it is read
 */
@ConfigurationProperties(prefix = "opaa.indexing.smb")
public record SmbProperties(
    long maxFileSizeBytes,
    Duration requestTimeout,
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
    requireNotNegative("maxFileSizeBytes", maxFileSizeBytes);
    requireNotNegative("requestBudgetPerRun", requestBudgetPerRun);
    requireNotNegative("maxEntriesPerRun", maxEntriesPerRun);
    requireNotNegative("downloadConcurrency", downloadConcurrency);
    requireNotNegative("listPageSize", listPageSize);
    maxFileSizeBytes = maxFileSizeBytes == 0 ? DEFAULT_MAX_FILE_SIZE_BYTES : maxFileSizeBytes;
    requestTimeout =
        requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
            ? DEFAULT_REQUEST_TIMEOUT
            : requestTimeout;
    requestBudgetPerRun =
        requestBudgetPerRun == 0 ? DEFAULT_REQUEST_BUDGET_PER_RUN : requestBudgetPerRun;
    maxEntriesPerRun = maxEntriesPerRun == 0 ? DEFAULT_MAX_ENTRIES_PER_RUN : maxEntriesPerRun;
    downloadConcurrency =
        downloadConcurrency == 0 ? DEFAULT_DOWNLOAD_CONCURRENCY : downloadConcurrency;
    listPageSize = listPageSize == 0 ? DEFAULT_LIST_PAGE_SIZE : listPageSize;
  }

  /** All defaults. */
  public static SmbProperties defaults() {
    return new SmbProperties(0, null, 0, 0, 0, 0);
  }

  private static void requireNotNegative(String name, long value) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative, got " + value);
    }
  }
}
