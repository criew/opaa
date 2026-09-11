package io.opaa.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The one place that answers "which client address made this request?" for every check that keys on
 * it - the rate limiter and the network restriction of local system administrators (ADR-0033,
 * Entscheidung 9). The implementation is {@link TrustedProxyClientIpResolver}: {@code
 * X-Forwarded-For} counts only behind a proxy listed in {@code
 * OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS}, so a client can never choose its address, and the answer is
 * always a numeric address.
 */
@FunctionalInterface
public interface ClientIpResolver {

  /**
   * The client's numeric address (IPv4 or IPv6), or {@code null} when the request has none. Never
   * header text.
   */
  String resolve(HttpServletRequest request);
}
