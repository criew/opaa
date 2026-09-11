package io.opaa.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * {@link ClientIpResolver} with trusted-proxy resolution (ADR-0033, Entscheidung 9). {@code
 * X-Forwarded-For} counts only when the connection itself ({@code getRemoteAddr()}) comes from one
 * of the configured proxy networks; the client is then the first entry from the right that is not a
 * trusted proxy - the entry the nearest trusted hop appended, which no client can forge - and, when
 * every entry is trusted, the leftmost one. With no trusted proxy configured (the default) the
 * header is ignored entirely. The result is always a numeric address: an entry that is no IPv4/IPv6
 * literal falls back to the connection's address, so header text never reaches a DNS lookup, a
 * bucket key or the administrator network check.
 */
public class TrustedProxyClientIpResolver implements ClientIpResolver {

  public static final String X_FORWARDED_FOR = "X-Forwarded-For";

  private static final String IPV4_OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
  private static final Pattern IPV4 =
      Pattern.compile("^" + IPV4_OCTET + "(\\." + IPV4_OCTET + "){3}$");
  private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F.]*:[0-9a-fA-F:.]*$");

  private final List<IpAddressMatcher> trustedProxies;

  public TrustedProxyClientIpResolver(List<String> trustedProxyCidrs) {
    this.trustedProxies =
        trustedProxyCidrs.stream().map(String::trim).map(IpAddressMatcher::new).toList();
  }

  public boolean hasTrustedProxies() {
    return !trustedProxies.isEmpty();
  }

  /** Whether {@code address} is a numeric address inside one of the trusted proxy networks. */
  public boolean isTrustedProxy(String address) {
    if (!isNumericAddress(address)) {
      return false;
    }
    try {
      return trustedProxies.stream().anyMatch(matcher -> matcher.matches(address));
    } catch (IllegalArgumentException unparseable) {
      return false;
    }
  }

  @Override
  public String resolve(HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    if (remote == null || !isTrustedProxy(remote)) {
      return remote;
    }
    String header = request.getHeader(X_FORWARDED_FOR);
    if (header == null || header.isBlank()) {
      return remote;
    }
    List<String> hops =
        Arrays.stream(header.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    if (hops.isEmpty()) {
      return remote;
    }
    for (int i = hops.size() - 1; i >= 0; i--) {
      String hop = hops.get(i);
      if (!isNumericAddress(hop)) {
        return remote;
      }
      if (!isTrustedProxy(hop)) {
        return hop;
      }
    }
    return hops.getFirst();
  }

  /** An IPv4 dotted quad or an IPv6 literal (hex groups and colons, no zone, no port). */
  static boolean isNumericAddress(String address) {
    return address != null && (IPV4.matcher(address).matches() || IPV6.matcher(address).matches());
  }
}
