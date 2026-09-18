package io.opaa.externalaccess;

import java.util.List;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * The network restriction of the whole external access channel (ADR-0035, Entscheidung 2;
 * docs/features/external-access.md, "Der Netzbereich gehört dem Kanal, nicht dem Token"). Fails
 * closed: an empty list, a missing address and anything DNS could resolve are refused. The
 * enforcement at {@code /mcp} is #1721; this component is the one place that decides it.
 *
 * <p>The matchers are rebuilt whenever the stored list differs from the one they were built from -
 * the settings row is read per call, so a changed network range takes effect with the next request
 * and no invalidation is needed.
 */
@Component
public class ExternalAccessNetworkPolicy {

  private record Compiled(List<String> cidrs, List<IpAddressMatcher> matchers) {}

  private final ExternalAccessSettingsService settings;

  private volatile Compiled compiled = new Compiled(List.of(), List.of());

  public ExternalAccessNetworkPolicy(ExternalAccessSettingsService settings) {
    this.settings = settings;
  }

  /** Whether {@code remoteAddress} lies in one of the channel's configured networks. */
  public boolean isAllowed(String remoteAddress) {
    return CidrList.matches(matchersOf(settings.allowedCidrs()), remoteAddress);
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
