package io.opaa.auth.local;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * The network restriction of local system administrators (ADR-0033, Entscheidung 9): with {@code
 * OPAA_LOCAL_ADMIN_ALLOWED_CIDRS} set, a local {@code SYSTEM_ADMIN} signs in only from one of those
 * networks (IPv4 and IPv6, ranges or single addresses); regular local accounts are never
 * restricted. Fails closed: while restricted, a missing or non-numeric client address is refused
 * <em>before</em> the matcher sees it - {@link IpAddressMatcher} would resolve a host name through
 * DNS, and once #1535 takes the address from {@code X-Forwarded-For} that name would be attacker
 * chosen. The list is validated when {@link LocalAuthProperties} binds, so a typo stops the start
 * instead of silently opening or closing the door.
 */
@Component
public class LocalAdminNetworkPolicy {

  private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
  private static final Pattern IPV4 = Pattern.compile("^" + OCTET + "(\\." + OCTET + "){3}$");
  private static final Pattern IPV6 =
      Pattern.compile("^[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*(%[A-Za-z0-9]+)?$");

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
    if (clientAddress == null || !isNumericAddress(clientAddress.trim())) {
      return false;
    }
    try {
      return allowed.stream().anyMatch(matcher -> matcher.matches(clientAddress.trim()));
    } catch (IllegalArgumentException unparseable) {
      return false;
    }
  }

  /** A dotted-quad IPv4 or a colon-separated IPv6 literal - never anything DNS could resolve. */
  static boolean isNumericAddress(String address) {
    return IPV4.matcher(address).matches() || IPV6.matcher(address).matches();
  }
}
