package io.opaa.indexing.source.googledrive;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational bounds of the Google Drive connector (ADR-0040, Entscheidung 10). Every value falls
 * back to its default when absent or zero.
 *
 * @param pageSize entries per {@code files.list} and {@code changes.list} page, at most {@value
 *     #MAX_PAGE_SIZE}
 * @param maxFileSizeBytes the bound of one download or export, applied while streaming; default 50
 *     MiB
 * @param requestTimeout per request; default 30 seconds
 * @param downloadTimeout the whole of one download or export, from the answer's start to its last
 *     byte; default 10 minutes
 * @param maxRetries retries of a throttled request ({@code 429}, {@code 403 rateLimitExceeded});
 *     default 5, negative turns them off
 * @param retryBackoff base of the exponential wait between retries; default 1 second
 * @param requestBudgetPerRun requests one run may send before it ends as truncated; default 20 000
 * @param maxFilesPerRun files one full sync may list before it fails asking for narrower scopes;
 *     default 1 000 000
 * @param downloadConcurrency downloads in flight per run; default 2
 * @param fullSyncInterval the instance-wide rhythm of the full sync; default 7 days
 */
@ConfigurationProperties(prefix = "opaa.indexing.google-drive")
public record GoogleDriveProperties(
    int pageSize,
    long maxFileSizeBytes,
    Duration requestTimeout,
    Duration downloadTimeout,
    Integer maxRetries,
    Duration retryBackoff,
    int requestBudgetPerRun,
    int maxFilesPerRun,
    int downloadConcurrency,
    Duration fullSyncInterval) {

  public static final int MAX_PAGE_SIZE = 1000;

  public GoogleDriveProperties {
    if (pageSize <= 0 || pageSize > MAX_PAGE_SIZE) {
      pageSize = MAX_PAGE_SIZE;
    }
    if (maxFileSizeBytes <= 0) {
      maxFileSizeBytes = 50L * 1024 * 1024;
    }
    if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
      requestTimeout = Duration.ofSeconds(30);
    }
    if (downloadTimeout == null || downloadTimeout.isZero() || downloadTimeout.isNegative()) {
      downloadTimeout = Duration.ofMinutes(10);
    }
    if (maxRetries == null) {
      maxRetries = 5;
    }
    if (retryBackoff == null || retryBackoff.isNegative()) {
      retryBackoff = Duration.ofSeconds(1);
    }
    if (requestBudgetPerRun <= 0) {
      requestBudgetPerRun = 20_000;
    }
    if (maxFilesPerRun <= 0) {
      maxFilesPerRun = 1_000_000;
    }
    if (downloadConcurrency <= 0) {
      downloadConcurrency = 2;
    }
    if (fullSyncInterval == null || fullSyncInterval.isZero() || fullSyncInterval.isNegative()) {
      fullSyncInterval = Duration.ofDays(7);
    }
  }

  public static GoogleDriveProperties defaults() {
    return new GoogleDriveProperties(0, 0, null, null, null, null, 0, 0, 0, null);
  }
}
