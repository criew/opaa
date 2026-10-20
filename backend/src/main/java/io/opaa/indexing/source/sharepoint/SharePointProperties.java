package io.opaa.indexing.source.sharepoint;

import static io.opaa.common.PropertyDefaults.positiveOr;
import static java.util.Objects.requireNonNullElse;

import io.opaa.compliance.DataImpact;
import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational bounds of the SharePoint connector (ADR-0047). Every value falls back to its default
 * when absent or zero.
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
@PerInstance("budgets and bounds of a run on this process")
@NoDataImpact("technical bounds, budgets and timeouts")
@ConfigurationProperties(prefix = "opaa.indexing.sharepoint")
public record SharePointProperties(
    int pageSize,
    long maxFileSizeBytes,
    @NoDataImpact("a timeout of one request") Duration requestTimeout,
    @NoDataImpact("a timeout of one request") Duration downloadTimeout,
    Integer maxRetries,
    @NoDataImpact("pacing of retries") Duration maxRetryWait,
    @NoDataImpact("pacing of retries") Duration maxThrottleWaitPerRun,
    int requestBudgetPerRun,
    int maxFilesPerRun,
    int downloadConcurrency,
    @DataImpact Duration fullSyncInterval) {

  public static final int MAX_PAGE_SIZE = 1000;

  public SharePointProperties {
    pageSize = Math.min(positiveOr(pageSize, 200), MAX_PAGE_SIZE);
    maxFileSizeBytes = positiveOr(maxFileSizeBytes, 50L * 1024 * 1024);
    requestTimeout = positiveOr(requestTimeout, Duration.ofSeconds(30));
    downloadTimeout = positiveOr(downloadTimeout, Duration.ofMinutes(10));
    maxRetries = requireNonNullElse(maxRetries, 5);
    maxRetryWait = positiveOr(maxRetryWait, Duration.ofSeconds(60));
    maxThrottleWaitPerRun = positiveOr(maxThrottleWaitPerRun, Duration.ofMinutes(30));
    requestBudgetPerRun = positiveOr(requestBudgetPerRun, 20_000);
    maxFilesPerRun = positiveOr(maxFilesPerRun, 1_000_000);
    downloadConcurrency = positiveOr(downloadConcurrency, 2);
    fullSyncInterval = positiveOr(fullSyncInterval, Duration.ofDays(1));
  }

  public static SharePointProperties defaults() {
    return new SharePointProperties(0, 0, null, null, null, null, null, 0, 0, 0, null);
  }
}
