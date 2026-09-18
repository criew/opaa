package io.opaa.externalaccess;

import io.opaa.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * The network restriction of the whole external access channel (ADR-0035, Entscheidung 2;
 * docs/features/external-access.md, "Der Netzbereich gehört dem Kanal, nicht dem Token"). Fails
 * closed: an empty list, a missing address and anything DNS could resolve are refused. The
 * enforcement at {@code /mcp} is #1721; this component is the one place that decides it.
 *
 * <p>The address comes from {@link ClientIpResolver} and from nowhere else - that is why the
 * callable entry point takes the request, not a string. {@code HttpServletRequest#getRemoteAddr()}
 * is no permitted source: behind a reverse proxy it is either the proxy's own address, which lies
 * inside the house network and would let every address in the world pass, or, with {@code
 * server.forward-headers-strategy: framework}, the leftmost {@code X-Forwarded-For} entry, which
 * the client writes itself.
 *
 * <p>The matchers are rebuilt whenever the stored list differs from the one they were built from -
 * the settings row is read per call, so a changed network range takes effect with the next request
 * and no invalidation is needed.
 */
@Component
public class ExternalAccessNetworkPolicy {

  private record Compiled(List<String> cidrs, List<IpAddressMatcher> matchers) {}

  private final ExternalAccessSettingsService settings;
  private final ClientIpResolver clientIpResolver;

  private volatile Compiled compiled = new Compiled(List.of(), List.of());

  public ExternalAccessNetworkPolicy(
      ExternalAccessSettingsService settings, ClientIpResolver clientIpResolver) {
    this.settings = settings;
    this.clientIpResolver = clientIpResolver;
  }

  /** Whether the client of {@code request} reaches the channel from one of its networks. */
  public boolean isAllowed(HttpServletRequest request) {
    return isAllowed(clientIpResolver.resolve(request));
  }

  /**
   * Package-private on purpose: only an address that {@link ClientIpResolver} resolved may be
   * checked, and {@link #isAllowed(HttpServletRequest)} is the only way to obtain one.
   */
  boolean isAllowed(String resolvedClientAddress) {
    return CidrList.matches(matchersOf(settings.allowedCidrs()), resolvedClientAddress);
  }

  private List<IpAddressMatcher> matchersOf(List<String> cidrs) {
    Compiled current = compiled;
    if (current.cidrs().equals(cidrs)) {
      return current.matchers();
    }
    Compiled rebuilt = new Compiled(List.copyOf(cidrs), CidrList.matchers(cidrs));
    compiled = rebuilt;
    return rebuilt.matchers();
  }
}
