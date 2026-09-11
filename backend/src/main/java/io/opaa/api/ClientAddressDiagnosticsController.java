package io.opaa.api;

import io.opaa.api.dto.ClientAddressDiagnosticsResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.security.TrustedProxyClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one request-bound diagnostic of ADR-0033, Entscheidung 9: the client address of this very
 * request as the rate limiter and the administrator network restriction resolve it, next to what
 * the connection and {@code X-Forwarded-For} say - so an operator sees without a shell whether
 * {@code OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS} is right. System administrators only; the check is
 * here because there is no service behind this endpoint - it reads nothing but the request.
 */
@RestController
@RequestMapping("/api/v1/admin/diagnostics")
public class ClientAddressDiagnosticsController {

  private final TrustedProxyClientIpResolver clientIpResolver;

  public ClientAddressDiagnosticsController(TrustedProxyClientIpResolver clientIpResolver) {
    this.clientIpResolver = clientIpResolver;
  }

  @GetMapping("/client-address")
  public ClientAddressDiagnosticsResponse clientAddress(
      @Caller CurrentUser caller, HttpServletRequest request) {
    if (!caller.isSystemAdmin()) {
      throw new AccessDeniedException(
          "Nur die Administration darf die aufgelöste Client-Adresse einsehen");
    }
    String remote = clientIpResolver.remoteAddress(request);
    String forwardedFor = clientIpResolver.forwardedFor(request);
    ClientAddressDiagnosticsResponse response =
        new ClientAddressDiagnosticsResponse(
            forwardedFor != null && clientIpResolver.isTrustedProxy(remote),
            clientIpResolver.hasTrustedProxies());
    response.setClientAddress(clientIpResolver.resolve(request));
    response.setRemoteAddress(remote);
    response.setForwardedFor(forwardedFor);
    return response;
  }
}
