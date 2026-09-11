package io.opaa.api;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * Configuration properties for the API rate limiter.
 *
 * @param enabled whether rate limiting is active (default true)
 * @param trustedProxyCidrs the networks whose {@code X-Forwarded-For} is honoured by {@code
 *     io.opaa.security.TrustedProxyClientIpResolver} (ADR-0033, Entscheidung 9). Empty by default -
 *     the secure default for a backend without a reverse proxy, which then ignores the header
 *     entirely; behind a proxy it must name the proxy's network, or every client shares one bucket.
 *     A wildcard ({@code 0.0.0.0/0}, {@code ::/0}) is refused: it would make every client a proxy.
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
 * @param webhook per-endpoint limits for the Confluence webhook intake (#1140) - {@code POST
 *     /api/v1/libraries/{libraryId}/confluence-webhook} is reachable without a session, so the
 *     limiter is the bound on how much signature checking an unauthenticated caller can cause;
 *     keyed per library so a chatty instance does not starve another library's notifications.
 * @param localAuth the limits of the local sign-in and its self-service endpoints (ADR-0033,
 *     Entscheidung 9); every absent value is the ADR's default
 */
@ConfigurationProperties(prefix = "opaa.rate-limit")
public record RateLimitProperties(
    boolean enabled,
    List<String> trustedProxyCidrs,
    EndpointLimit query,
    EndpointLimit indexing,
    EndpointLimit sourceTest,
    EndpointLimit documentContent,
    EndpointLimit webhook,
    LocalAuthLimits localAuth) {

  public static final String TRUSTED_PROXY_CIDRS_VARIABLE = "OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS";

  public RateLimitProperties {
    trustedProxyCidrs = normalizeTrustedProxies(trustedProxyCidrs);
    localAuth =
        localAuth != null ? localAuth : new LocalAuthLimits(null, null, null, null, null, null);
  }

  /**
   * Trimmed, blanks dropped, every entry parseable as an address or CIDR and none a wildcard - fail
   * fast otherwise, naming the environment variable.
   */
  private static List<String> normalizeTrustedProxies(List<String> cidrs) {
    if (cidrs == null) {
      return List.of();
    }
    List<String> normalized =
        cidrs.stream().filter(c -> c != null && !c.isBlank()).map(String::trim).toList();
    for (String cidr : normalized) {
      try {
        new IpAddressMatcher(cidr);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "opaa.rate-limit.trusted-proxy-cidrs ("
                + TRUSTED_PROXY_CIDRS_VARIABLE
                + ") contains no valid address or CIDR range: "
                + cidr,
            e);
      }
      if (isWildcard(cidr)) {
        throw new IllegalArgumentException(
            "opaa.rate-limit.trusted-proxy-cidrs ("
                + TRUSTED_PROXY_CIDRS_VARIABLE
                + ") must not trust every address - a /0 range would let any client choose its"
                + " own address via X-Forwarded-For (ADR-0033): "
                + cidr);
      }
    }
    return normalized;
  }

  private static boolean isWildcard(String cidr) {
    int slash = cidr.indexOf('/');
    if (slash < 0) {
      return false;
    }
    try {
      return Integer.parseInt(cidr.substring(slash + 1).trim()) == 0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

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

  /**
   * The limits of the local sign-in (ADR-0033, Entscheidung 9). {@code login}, {@code refresh},
   * {@code register}, {@code forgotPassword} and {@code setPassword} are keyed by client address in
   * {@code RateLimitFilter}; {@code changePassword} by the authenticated account and {@code
   * register}/{@code forgotPassword} additionally by the address they name, both in {@code
   * io.opaa.auth.local.LocalAuthRateLimiter}. The endpoints of {@code register}, {@code
   * forgotPassword} and {@code setPassword} arrive with #1538; their limits stand ready.
   */
  public record LocalAuthLimits(
      LocalAuthLimit login,
      LocalAuthLimit refresh,
      LocalAuthLimit changePassword,
      LocalAuthLimit register,
      LocalAuthLimit forgotPassword,
      LocalAuthLimit setPassword) {

    public LocalAuthLimits {
      login = login != null ? login : new LocalAuthLimit(10, 60, 100, null);
      refresh = refresh != null ? refresh : new LocalAuthLimit(30, 60, null, null);
      changePassword =
          changePassword != null ? changePassword : new LocalAuthLimit(5, 300, null, null);
      register = register != null ? register : new LocalAuthLimit(5, 3600, 50, 3);
      forgotPassword = forgotPassword != null ? forgotPassword : new LocalAuthLimit(5, 3600, 50, 3);
      setPassword = setPassword != null ? setPassword : new LocalAuthLimit(10, 900, null, null);
    }
  }

  /**
   * One local-auth limit.
   *
   * @param maxRequests requests per key (client address or account) within the window
   * @param windowSeconds sliding window in seconds
   * @param globalMaxRequests requests across all keys within the window, or {@code null} for no
   *     global ceiling; exceeding it is a {@code WARN} line and a metric
   * @param maxRequestsPerAddress requests naming the same e-mail address within the window, or
   *     {@code null} when the endpoint names none
   */
  public record LocalAuthLimit(
      int maxRequests,
      int windowSeconds,
      Integer globalMaxRequests,
      Integer maxRequestsPerAddress) {

    public LocalAuthLimit {
      if (maxRequests < 1) {
        throw new IllegalArgumentException("maxRequests must be at least 1, got " + maxRequests);
      }
      if (windowSeconds < 1) {
        throw new IllegalArgumentException(
            "windowSeconds must be at least 1, got " + windowSeconds);
      }
      if (globalMaxRequests != null && globalMaxRequests < 1) {
        throw new IllegalArgumentException(
            "globalMaxRequests must be at least 1, got " + globalMaxRequests);
      }
      if (maxRequestsPerAddress != null && maxRequestsPerAddress < 1) {
        throw new IllegalArgumentException(
            "maxRequestsPerAddress must be at least 1, got " + maxRequestsPerAddress);
      }
    }

    public boolean hasGlobalLimit() {
      return globalMaxRequests != null;
    }

    public boolean hasAddressLimit() {
      return maxRequestsPerAddress != null;
    }
  }
}
