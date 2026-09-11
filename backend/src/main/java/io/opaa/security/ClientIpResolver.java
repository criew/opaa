package io.opaa.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The one place that answers "which client address made this request?" for every check that keys on
 * it - the network restriction of local system administrators (ADR-0033, Entscheidung 9) and, once
 * #1535 replaces the default implementation with the trusted-proxy resolution over {@code
 * OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS}, the rate limiter too. Until then {@link
 * RemoteAddrClientIpResolver} answers with the connection's own address and ignores every
 * forwarding header, so a client can never choose its address.
 */
@FunctionalInterface
public interface ClientIpResolver {

  /** The client's address as text (IPv4 or IPv6), or {@code null} when the request has none. */
  String resolve(HttpServletRequest request);
}
