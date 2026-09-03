package io.opaa.indexing.source.confluence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational bounds of the Confluence access layer - deliberately its own property block rather
 * than a component of {@code IndexingProperties} (mirrors {@code CrawlProperties}'s reasoning:
 * adding a record component there touches every positional call site for a concern specific to one
 * source type).
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
 *     Default 2 minutes.
 * @param maxResponseBytes upper bound for a single JSON response body. Default 10 MiB.
 * @param maxAttachmentSizeBytes upper bound for a single attachment download. Default 20 MiB.
 * @param userAgent truthful {@code User-Agent} sent with every request. Default {@code
 *     OPAA-Indexer/1.0}.
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
 *     an orderly way as "incomplete, continued by the next run" (#1141) - the bound that makes a
 *     run against a very large instance plannable, for Cloud (a points budget answered with 429)
 *     and Data Center (no built-in limit, the instance is simply kept busy) alike. Zero disables
 *     the budget; the default is the operational value documented in deployment.md
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
    String userAgent,
    int maxListingPages,
    Duration fullSyncInterval,
    Duration incrementalOverlap,
    int requestBudgetPerRun) {

  static final String DEFAULT_USER_AGENT = "OPAA-Indexer/1.0";

  /**
   * Requests per run before the run ends as incomplete (#1141). Measured against a real Data Center
   * in the container suite: a page costs about two requests (body, attachment list) plus its
   * attachment downloads and a share of the listing; 50 000 requests therefore cover roughly 20 000
   * pages per run, so a weekly full sync of a 100 000-page selection finishes within a working week
   * of daily runs while a single run stays under an hour even at Cloud's pace.
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
    if (userAgent == null || userAgent.isBlank()) {
      userAgent = DEFAULT_USER_AGENT;
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
        0, null, null, 0, null, 0, 0, null, 0, null, null, DEFAULT_REQUEST_BUDGET_PER_RUN);
  }
}
