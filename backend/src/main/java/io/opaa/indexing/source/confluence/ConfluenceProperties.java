package io.opaa.indexing.source.confluence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational bounds of the Confluence access layer.
 *
 * @param pageSize the {@code limit} sent with every listing request (spaces, pages, attachments,
 *     search). Cloud caps at 250, Data Center at 200 for most listings; the adapters send this
 *     value as-is and follow whatever page size the instance actually returns. Default 100. {@code
 *     0} falls back to the default; a negative value is rejected.
 * @param requestTimeout per-request timeout for JSON calls. Default 30 seconds.
 * @param detectionTimeout per-probe timeout of the credential-free edition detection, which also
 *     runs inside a library creation - kept short so a slow instance cannot hold a transaction and
 *     a worker thread for long. Default 10 seconds.
 * @param maxRateLimitRetries how many consecutive {@code 429} responses one logical request
 *     survives before giving up with a rate-limit error. Default 6.
 * @param maxRetryAfter the longest single wait honoured from a {@code Retry-After} header; a longer
 *     value is capped to this (a run should slow down, not stall for an hour on one header).
 *     Default 2 minutes. {@code maxRateLimitRetries} and this value are Confluence's own numbers,
 *     applied by the shared {@code io.opaa.sourceaccess} rate-limit handling; the {@code
 *     User-Agent} is the deployment-wide {@code opaa.indexing.http.user-agent}.
 * @param maxResponseBytes upper bound for a single JSON response body. Default 10 MiB.
 * @param maxAttachmentSizeBytes upper bound for a single attachment download. Default 20 MiB.
 * @param maxListingPages how many pages one listing (spaces, pages of a space, attachments, a
 *     search) may follow before it is abandoned as unbounded - a server whose {@code next} link
 *     never runs out must not become an endless loop (the same reasoning as {@code
 *     OPAA_INDEXING_CRAWL_MAX_ENTRIES}). Abandoning is a visible failure, never a silent cut, so a
 *     full sync that hits it cannot pass an incomplete listing off as complete. Default 500 (50 000
 *     entries at the default page size).
 * @param fullSyncInterval how long after a completed full sync the next run is a full one again
 *     (ADR-0023, Entscheidung 4): only the full run reaches deletions Confluence never reports, so
 *     the interval is lengthened, never switched off; weekly by default
 * @param incrementalOverlap how far before the last anchor an incremental run searches, absorbing
 *     clock skew between OPAA and the instance and CQL's minute granularity; a re-found unchanged
 *     page costs a listing entry, no body fetch; ten minutes by default (zero falls back to it)
 * @param requestBudgetPerRun how many requests one run may send to its instance before it ends in
 *     an orderly way as "incomplete, continued by the next run" - the bound that makes a run
 *     against a very large instance plannable, for Cloud (a points budget answered with 429) and
 *     Data Center (no built-in limit, the instance is simply kept busy) alike. Applied only to a
 *     run's client ({@code ConfluenceClientFactory#createForRun}), never to the wizard's probes.
 *     Zero disables the budget; the default is {@link #DEFAULT_REQUEST_BUDGET_PER_RUN}
 */
@ConfigurationProperties(prefix = "opaa.indexing.confluence")
public record ConfluenceProperties(
    int pageSize,
    Duration requestTimeout,
    Duration detectionTimeout,
    int maxRateLimitRetries,
    Duration maxRetryAfter,
    long maxResponseBytes,
    long maxAttachmentSizeBytes,
    int maxListingPages,
    Duration fullSyncInterval,
    Duration incrementalOverlap,
    int requestBudgetPerRun) {

  /**
   * Calls per run before the run ends as incomplete. Measured against a real Data Center in the
   * container suite (run 33772411512): a full sync of 4 readable pages with 2 attachments cost 13
   * calls - 1 credential check, 2 listings, 4 page bodies, 4 attachment lists, 2 downloads - so a
   * page costs 2 calls plus its downloads plus one listing call per {@code pageSize} pages; an
   * incremental run for one change cost 6. 50 000 calls therefore cover roughly 20 000 pages of new
   * or changed content per run. A resumed full sync re-lists its unfinished spaces but spends no
   * call on a page already stored at the listed version, so the chain of runs converges as long as
   * the budget exceeds one space's listing plus a handful of pages.
   */
  public static final int DEFAULT_REQUEST_BUDGET_PER_RUN = 50_000;

  public ConfluenceProperties {
    if (pageSize < 0) {
      throw new IllegalArgumentException("pageSize must not be negative, got " + pageSize);
    }
    if (pageSize == 0) {
      pageSize = 100;
    }
    if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
      requestTimeout = Duration.ofSeconds(30);
    }
    if (detectionTimeout == null || detectionTimeout.isZero() || detectionTimeout.isNegative()) {
      detectionTimeout = Duration.ofSeconds(10);
    }
    if (maxRateLimitRetries < 0) {
      throw new IllegalArgumentException(
          "maxRateLimitRetries must not be negative, got " + maxRateLimitRetries);
    }
    if (maxRateLimitRetries == 0) {
      maxRateLimitRetries = 6;
    }
    if (maxRetryAfter == null || maxRetryAfter.isZero() || maxRetryAfter.isNegative()) {
      maxRetryAfter = Duration.ofMinutes(2);
    }
    if (maxResponseBytes <= 0) {
      maxResponseBytes = 10_485_760L;
    }
    if (maxAttachmentSizeBytes <= 0) {
      maxAttachmentSizeBytes = 20_971_520L;
    }
    if (maxListingPages < 0) {
      throw new IllegalArgumentException(
          "maxListingPages must not be negative, got " + maxListingPages);
    }
    if (maxListingPages == 0) {
      maxListingPages = 500;
    }
    // ADR-0023, Entscheidung 4 ("Betriebsarten im Zeitplan"): a full reconciliation stays
    // necessary - it is the only way deletions Confluence never reports reach the index - so the
    // interval can be lengthened, never switched off; weekly is the documented default.
    if (fullSyncInterval == null || fullSyncInterval.isZero() || fullSyncInterval.isNegative()) {
      fullSyncInterval = Duration.ofDays(7);
    }
    // The incremental run searches from the last anchor minus this overlap: clock skew between
    // OPAA and the instance, CQL's minute granularity and edits during the previous run are
    // absorbed by re-reading a little; an unchanged page costs one listing entry, no body fetch.
    if (incrementalOverlap == null
        || incrementalOverlap.isZero()
        || incrementalOverlap.isNegative()) {
      incrementalOverlap = Duration.ofMinutes(10);
    }
    if (requestBudgetPerRun < 0) {
      throw new IllegalArgumentException(
          "requestBudgetPerRun must not be negative, got " + requestBudgetPerRun);
    }
  }

  /** {@code true} when a run is bounded by {@link #requestBudgetPerRun}; zero means unbounded. */
  public boolean hasRequestBudget() {
    return requestBudgetPerRun > 0;
  }

  /** All defaults - for callers and tests that need a properties instance without configuration. */
  public static ConfluenceProperties defaults() {
    return new ConfluenceProperties(
        0, null, null, 0, null, 0, 0, 0, null, null, DEFAULT_REQUEST_BUDGET_PER_RUN);
  }
}
