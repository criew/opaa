package io.opaa.security;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * {@link ClientIpResolver} with trusted-proxy resolution (ADR-0033, Entscheidung 9). {@code
 * X-Forwarded-For} counts only when the connection itself comes from one of the configured proxy
 * networks; the client is then the first entry from the right that is not a trusted proxy - the
 * entry the nearest trusted hop appended, which no client can forge - and, when every entry is
 * trusted, the leftmost one. With no trusted proxy configured (the default) the header is ignored
 * entirely. The result is always a numeric address: an entry that is no IPv4/IPv6 literal falls
 * back to the connection's address, so header text never reaches a DNS lookup, a bucket key or the
 * administrator network check.
 *
 * <p>Connection address and header are read from the request beneath every wrapper: with {@code
 * server.forward-headers-strategy: framework} Spring's {@code ForwardedHeaderFilter} already
 * rewrites {@code getRemoteAddr()} from the <em>leftmost</em> {@code X-Forwarded-For} entry - the
 * one a client writes itself - and hides the header, so the wrapped request would let any client
 * choose its address.
 */
public class TrustedProxyClientIpResolver implements ClientIpResolver {

  public static final String X_FORWARDED_FOR = "X-Forwarded-For";

  private static final String IPV4_OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
  private static final Pattern IPV4 =
      Pattern.compile("^" + IPV4_OCTET + "(\\." + IPV4_OCTET + "){3}$");
  private static final Pattern IPV6_CHARACTERS = Pattern.compile("^[0-9a-fA-F:.]+$");

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
    String remote = remoteAddress(request);
    if (remote == null || !isTrustedProxy(remote)) {
      return remote;
    }
    String header = forwardedFor(request);
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

  /** The address of the connection itself, beneath every request wrapper. */
  public String remoteAddress(HttpServletRequest request) {
    return underlying(request).getRemoteAddr();
  }

  /** The {@code X-Forwarded-For} header as it arrived, beneath every request wrapper. */
  public String forwardedFor(HttpServletRequest request) {
    return underlying(request).getHeader(X_FORWARDED_FOR);
  }

  private static HttpServletRequest underlying(HttpServletRequest request) {
    ServletRequest current = request;
    while (current instanceof ServletRequestWrapper wrapper) {
      current = wrapper.getRequest();
    }
    return current instanceof HttpServletRequest http ? http : request;
  }

  /**
   * An IPv4 dotted quad or an IPv6 literal (hex groups and at least two colons, no zone, no port).
   * The IPv6 check parses the literal; a string with a colon never reaches DNS.
   */
  static boolean isNumericAddress(String address) {
    if (address == null) {
      return false;
    }
    if (IPV4.matcher(address).matches()) {
      return true;
    }
    if (!IPV6_CHARACTERS.matcher(address).matches()
        || address.indexOf(':') == address.lastIndexOf(':')) {
      return false;
    }
    try {
      InetAddress.getByName(address);
      return true;
    } catch (UnknownHostException | IllegalArgumentException notALiteral) {
      return false;
    }
  }
}
