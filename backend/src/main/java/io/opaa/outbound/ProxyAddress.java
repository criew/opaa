package io.opaa.outbound;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * The address of the outbound proxy: a host and a port, spoken to in plain HTTP ({@code CONNECT}
 * for {@code https} targets). Only its form is checked; the proxy is infrastructure the operator
 * names, not a target.
 *
 * @param host a host name or an IP literal, IPv6 without brackets, lower-cased
 * @param port 1..65535
 */
public record ProxyAddress(String host, int port) {

  public ProxyAddress {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("a proxy needs a host");
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("a proxy needs a port in 1..65535, got " + port);
    }
  }

  /**
   * {@code http://host:port} or {@code host:port}; {@code empty} for a form that is none of them -
   * another scheme ({@code https}, {@code socks5}), user information, a path, query or fragment, a
   * missing or unusable port.
   */
  public static Optional<ProxyAddress> parse(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String text = value.strip();
    if (text.isEmpty() || text.chars().anyMatch(Character::isWhitespace)) {
      return Optional.empty();
    }
    if (!text.contains("://")) {
      text = "http://" + text;
    }
    URI uri;
    try {
      uri = new URI(text);
    } catch (URISyntaxException e) {
      return Optional.empty();
    }
    if (!"http".equalsIgnoreCase(uri.getScheme())
        || uri.getRawUserInfo() != null
        || uri.getHost() == null
        || uri.getPort() < 1
        || uri.getPort() > 65535
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || !(uri.getRawPath() == null
            || uri.getRawPath().isEmpty()
            || uri.getRawPath().equals("/"))) {
      return Optional.empty();
    }
    String host = ProxyExclusions.stripBrackets(uri.getHost()).toLowerCase(Locale.ROOT);
    return Optional.of(new ProxyAddress(host, uri.getPort()));
  }

  /** {@code http://host:port}, IPv6 in brackets - the form the API shows. */
  public String toUrl() {
    return "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
  }

  /** Unresolved, so the name is looked up anew for every connection. */
  public InetSocketAddress socketAddress() {
    return InetSocketAddress.createUnresolved(host, port);
  }
}
