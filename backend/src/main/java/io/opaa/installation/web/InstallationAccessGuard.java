package io.opaa.installation.web;

import io.opaa.common.AccessDeniedException;
import io.opaa.common.NumericAddress;
import io.opaa.common.TooManyRequestsException;
import io.opaa.common.UnauthorizedException;
import io.opaa.installation.InstallationProperties;
import io.opaa.installation.InstallationService;
import io.opaa.ratelimit.RateLimitService;
import io.opaa.security.TrustedProxyClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * Who may call the installation API (ADR-0060, Entscheidung 5): a client from {@code
 * OPAA_INSTALLATION_ALLOWED_CIDRS}, if set, presenting the installation token as {@code
 * Authorization: Bearer}. Each client address has a budget of requests and a smaller one of refused
 * tokens; both windows are per instance (ADR-0053: "je Instanz") and register in {@code
 * backend/docs/prozesslokaler-zustand.md}. The token is compared over its hash in constant time.
 */
@Component
class InstallationAccessGuard {

  static final int MAX_REFUSED_TOKENS = 10;
  static final int MAX_REQUESTS = 300;
  static final int WINDOW_SECONDS = 900;
  static final String TOKEN_MESSAGE = "Das Installations-Token fehlt oder ist ungültig.";
  static final String NETWORK_MESSAGE = "Die Installation ist aus diesem Netz nicht zugelassen.";

  private static final Logger log = LoggerFactory.getLogger(InstallationAccessGuard.class);
  private static final String BEARER = "Bearer ";
  private static final String UNKNOWN_CLIENT = "unknown";
  private static final String FORWARDED_FOR = "X-Forwarded-For";
  private final AtomicBoolean untrustedProxyReported = new AtomicBoolean();

  private final InstallationService installation;
  private final TrustedProxyClientIpResolver clientIpResolver;
  private final List<IpAddressMatcher> allowedNetworks;
  private final RateLimitService requests = new RateLimitService(MAX_REQUESTS, WINDOW_SECONDS);
  private final RateLimitService refusals =
      new RateLimitService(MAX_REFUSED_TOKENS, WINDOW_SECONDS);

  InstallationAccessGuard(
      InstallationService installation,
      TrustedProxyClientIpResolver clientIpResolver,
      InstallationProperties properties) {
    this.installation = installation;
    this.clientIpResolver = clientIpResolver;
    this.allowedNetworks = properties.allowedCidrs().stream().map(IpAddressMatcher::new).toList();
  }

  /**
   * @throws AccessDeniedException outside the allowed networks
   * @throws TooManyRequestsException past a budget of the client address
   * @throws UnauthorizedException without the valid token
   */
  void requireAccess(HttpServletRequest request) {
    String address = clientIpResolver.resolve(request);
    warnAboutAnUntrustedProxy(request, address);
    if (!permitsNetwork(address)) {
      throw new AccessDeniedException(NETWORK_MESSAGE);
    }
    String key = address == null ? UNKNOWN_CLIENT : address;
    RateLimitService.Decision budget = requests.tryAcquire(key);
    if (!budget.allowed()) {
      throw new TooManyRequestsException(budget.retryAfterSeconds());
    }
    RateLimitService.Decision refusal = refusals.tryAcquire(key);
    if (!refusal.allowed()) {
      throw new TooManyRequestsException(refusal.retryAfterSeconds());
    }
    if (installation.tokenMatches(bearerToken(request))) {
      refusals.release(key, refusal);
      return;
    }
    log.warn("Installation token refused for a request from {}", key);
    throw new UnauthorizedException(TOKEN_MESSAGE);
  }

  /**
   * Warns once when the network restriction meets {@code X-Forwarded-For} from a connection that is
   * no trusted proxy: the restriction and the budgets then see the proxy's address for every
   * client. Read beneath the request wrappers, as the header arrived, before Spring rewrites it.
   */
  private void warnAboutAnUntrustedProxy(HttpServletRequest request, String address) {
    String connection = clientIpResolver.remoteAddress(request);
    if (allowedNetworks.isEmpty()
        || clientIpResolver.forwardedFor(request) == null
        || connection == null
        || clientIpResolver.isTrustedProxy(connection)
        || !untrustedProxyReported.compareAndSet(false, true)) {
      return;
    }
    log.warn(
        "The installation API is restricted to networks, but a request carries {} from {}, which"
            + " is no trusted proxy: set OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS, or every client has"
            + " the proxy's address",
        FORWARDED_FOR,
        connection);
  }

  /** Fails closed: while restricted, an address that is no numeric literal is refused. */
  private boolean permitsNetwork(String address) {
    if (allowedNetworks.isEmpty()) {
      return true;
    }
    if (address == null || !NumericAddress.isNumeric(address.trim())) {
      return false;
    }
    try {
      return allowedNetworks.stream().anyMatch(matcher -> matcher.matches(address.trim()));
    } catch (IllegalArgumentException unparseable) {
      return false;
    }
  }

  private static String bearerToken(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      return null;
    }
    return header.substring(BEARER.length()).trim();
  }
}
