package io.opaa.auth.local;

import java.util.List;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * The network restriction of local system administrators (ADR-0033, Entscheidung 9): with {@code
 * OPAA_LOCAL_ADMIN_ALLOWED_CIDRS} set, a local {@code SYSTEM_ADMIN} signs in only from one of those
 * networks (IPv4 and IPv6, ranges or single addresses); regular local accounts are never
 * restricted. Fails closed: while restricted, a missing or unparseable client address is refused.
 * The list is validated when {@link LocalAuthProperties} binds, so a typo stops the start instead
 * of silently opening or closing the door.
 */
@Component
public class LocalAdminNetworkPolicy {

  private final List<IpAddressMatcher> allowed;

  public LocalAdminNetworkPolicy(LocalAuthProperties properties) {
    this.allowed = properties.adminAllowedCidrs().stream().map(IpAddressMatcher::new).toList();
  }

  /** Whether any network is configured at all. */
  public boolean isRestricted() {
    return !allowed.isEmpty();
  }

  public boolean permitsAdminSignIn(String clientAddress) {
    if (!isRestricted()) {
      return true;
    }
    if (clientAddress == null || clientAddress.isBlank()) {
      return false;
    }
    try {
      return allowed.stream().anyMatch(matcher -> matcher.matches(clientAddress.trim()));
    } catch (IllegalArgumentException unparseable) {
      return false;
    }
  }
}
