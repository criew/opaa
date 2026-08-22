package io.opaa.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the API rate limiter.
 *
 * @param enabled whether rate limiting is active (default true)
 * @param query per-endpoint limits for the query endpoint
 * @param indexing per-endpoint limits for the indexing trigger endpoint
 * @param sourceTest per-endpoint limits for the source connection test endpoint (#514, PR #537
 *     review finding 3) - a synchronous probe with its own outbound connections and timeouts, the
 *     same reason the indexing trigger above is limited.
 * @param documentContent per-endpoint limits for the document content endpoint (#748 review,
 *     finding 1) - {@code GET /api/v1/documents/{documentId}/content} proxies a {@code
 *     HTTP_DIRECTORY}/{@code RSS_FEED} document's original from its remote source (#747), the same
 *     kind of synchronous, outbound-connection-holding request {@code sourceTest} above is already
 *     limited for, except this one is VIEWER-reachable rather than gated by library creation.
 */
@ConfigurationProperties(prefix = "opaa.rate-limit")
public record RateLimitProperties(
    boolean enabled,
    EndpointLimit query,
    EndpointLimit indexing,
    EndpointLimit sourceTest,
    EndpointLimit documentContent) {

  /**
   * Rate limit settings for a single endpoint.
   *
   * @param maxRequests maximum requests per IP within the window. Must be at least 1.
   * @param windowSeconds sliding window duration in seconds. Must be at least 1.
   * @param globalMaxRequests maximum requests across all IPs within the window. Must be at least 1.
   */
  public record EndpointLimit(int maxRequests, int windowSeconds, int globalMaxRequests) {

    public EndpointLimit {
      if (maxRequests < 1) {
        throw new IllegalArgumentException("maxRequests must be at least 1, got " + maxRequests);
      }
      if (windowSeconds < 1) {
        throw new IllegalArgumentException(
            "windowSeconds must be at least 1, got " + windowSeconds);
      }
      if (globalMaxRequests < 1) {
        throw new IllegalArgumentException(
            "globalMaxRequests must be at least 1, got " + globalMaxRequests);
      }
    }
  }
}
