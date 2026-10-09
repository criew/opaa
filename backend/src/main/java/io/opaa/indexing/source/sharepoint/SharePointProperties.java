package io.opaa.indexing.source.sharepoint;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational bounds of the SharePoint connector (ADR-0040, Nachtrag „SharePoint“). Every value
 * falls back to its default when absent or zero.
 *
 * @param pageSize entries per {@code delta} and {@code children} page ({@code $top}), at most
 *     {@value #MAX_PAGE_SIZE}; default 200
 * @param maxFileSizeBytes the bound of one download, applied while streaming; default 50 MiB
 * @param requestTimeout per request; default 30 seconds
 * @param downloadTimeout the whole of one download, from the answer's start to its last byte;
 *     default 10 minutes
 * @param maxRetries retries of a throttled request ({@code 429}, {@code 503}); default 5, negative
 *     turns them off
 * @param maxRetryWait the longest single wait a {@code Retry-After} may ask for; default 60 seconds
 * @param maxThrottleWaitPerRun the waiting on throttled answers one run may spend in total before
 *     it ends as truncated; default 30 minutes
 * @param requestBudgetPerRun requests one run may send before it ends as truncated; default 20 000
 * @param maxFilesPerRun files one full sync may list before it fails asking for narrower libraries;
 *     default 1 000 000
 * @param downloadConcurrency downloads in flight per run; default 2
 * @param fullSyncInterval the instance-wide rhythm of the full sync; default 1 day
 */
@ConfigurationProperties(prefix = "opaa.indexing.sharepoint")
public record SharePointProperties(
    int pageSize,
    long maxFileSizeBytes,
    Duration requestTimeout,
    Duration downloadTimeout,
    Integer maxRetries,
    Duration maxRetryWait,
    Duration maxThrottleWaitPerRun,
    int requestBudgetPerRun,
    int maxFilesPerRun,
    int downloadConcurrency,
    Duration fullSyncInterval) {

  public static final int MAX_PAGE_SIZE = 1000;

  public SharePointProperties {
    if (pageSize <= 0 || pageSize > MAX_PAGE_SIZE) {
      pageSize = pageSize > MAX_PAGE_SIZE ? MAX_PAGE_SIZE : 200;
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
    if (maxRetryWait == null || maxRetryWait.isZero() || maxRetryWait.isNegative()) {
      maxRetryWait = Duration.ofSeconds(60);
    }
    if (maxThrottleWaitPerRun == null
        || maxThrottleWaitPerRun.isZero()
        || maxThrottleWaitPerRun.isNegative()) {
      maxThrottleWaitPerRun = Duration.ofMinutes(30);
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
      fullSyncInterval = Duration.ofDays(1);
    }
  }

  public static SharePointProperties defaults() {
    return new SharePointProperties(0, 0, null, null, null, null, null, 0, 0, 0, null);
  }
}
