package io.opaa.indexing.source.googledrive;

import static io.opaa.common.PropertyDefaults.positiveOr;
import static java.util.Objects.requireNonNullElse;

import io.opaa.compliance.DataImpact;
import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
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
@PerInstance("budgets and bounds of a run on this process")
@NoDataImpact("technical bounds, budgets and timeouts")
@ConfigurationProperties(prefix = "opaa.indexing.google-drive")
public record GoogleDriveProperties(
    int pageSize,
    long maxFileSizeBytes,
    @NoDataImpact("a timeout of one request") Duration requestTimeout,
    @NoDataImpact("a timeout of one request") Duration downloadTimeout,
    Integer maxRetries,
    @NoDataImpact("pacing of retries") Duration retryBackoff,
    int requestBudgetPerRun,
    int maxFilesPerRun,
    int downloadConcurrency,
    @DataImpact Duration fullSyncInterval) {

  public static final int MAX_PAGE_SIZE = 1000;

  public GoogleDriveProperties {
    pageSize = Math.min(positiveOr(pageSize, MAX_PAGE_SIZE), MAX_PAGE_SIZE);
    maxFileSizeBytes = positiveOr(maxFileSizeBytes, 50L * 1024 * 1024);
    requestTimeout = positiveOr(requestTimeout, Duration.ofSeconds(30));
    downloadTimeout = positiveOr(downloadTimeout, Duration.ofMinutes(10));
    maxRetries = requireNonNullElse(maxRetries, 5);
    if (retryBackoff == null || retryBackoff.isNegative()) {
      retryBackoff = Duration.ofSeconds(1);
    }
    requestBudgetPerRun = positiveOr(requestBudgetPerRun, 20_000);
    maxFilesPerRun = positiveOr(maxFilesPerRun, 1_000_000);
    downloadConcurrency = positiveOr(downloadConcurrency, 2);
    fullSyncInterval = positiveOr(fullSyncInterval, Duration.ofDays(7));
  }

  public static GoogleDriveProperties defaults() {
    return new GoogleDriveProperties(0, 0, null, null, null, null, 0, 0, 0, null);
  }
}
