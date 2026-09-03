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
 */
@ConfigurationProperties(prefix = "opaa.indexing.confluence")
public record ConfluenceProperties(
    int pageSize,
    Duration requestTimeout,
    int maxRateLimitRetries,
    Duration maxRetryAfter,
    long maxResponseBytes,
    long maxAttachmentSizeBytes,
    String userAgent,
    int maxListingPages) {

  static final String DEFAULT_USER_AGENT = "OPAA-Indexer/1.0";

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
  }

  /** All defaults - for callers and tests that need a properties instance without configuration. */
  public static ConfluenceProperties defaults() {
    return new ConfluenceProperties(0, null, 0, null, 0, 0, null, 0);
  }
}
