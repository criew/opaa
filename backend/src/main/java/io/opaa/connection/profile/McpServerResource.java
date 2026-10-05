package io.opaa.connection.profile;

import io.opaa.common.ValidationException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The resource indicator of an MCP server (RFC 8707; MCP authorization): its Streamable HTTP
 * endpoint in canonical form - lower-case scheme and host, no default port, no trailing slash, no
 * user info, query or fragment. An MCP profile stores its address in this form, and a token is
 * issued for exactly this value; another path on the same host is another server.
 */
public final class McpServerResource {

  private static final int MAX_LENGTH = 2000;

  private McpServerResource() {}

  /** The resource indicator {@code profile}'s tokens are issued for. */
  public static String of(ConnectionProfile profile) {
    if (!profile.isMcpServer()) {
      throw new IllegalArgumentException("only an MCP server has a resource indicator");
    }
    return profile.getServerUrl();
  }

  /**
   * {@code address} in canonical form.
   *
   * @throws ValidationException (German 400) for anything but an absolute {@code https} address -
   *     {@code http} only on a loopback host - without user info, query or fragment
   */
  public static String canonical(String address) {
    if (address == null || address.isBlank()) {
      throw new ValidationException("serverUrl ist erforderlich");
    }
    URI uri;
    try {
      uri = new URI(address.strip());
    } catch (URISyntaxException e) {
      throw new ValidationException("serverUrl ist keine gültige Adresse");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
    if (host == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || !(scheme.equals("https") || scheme.equals("http") && isLoopback(host))) {
      throw new ValidationException(
          "serverUrl muss die vollständige https-Adresse des MCP-Endpunkts sein (Streamable"
              + " HTTP), ohne Benutzerangabe, Abfrage und Fragment");
    }
    int port = uri.getPort();
    boolean defaultPort =
        port == -1 || scheme.equals("https") && port == 443 || scheme.equals("http") && port == 80;
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    // an IPv6 host comes with its brackets
    String canonical = scheme + "://" + host + (defaultPort ? "" : ":" + port) + path;
    if (canonical.length() > MAX_LENGTH) {
      throw new ValidationException("serverUrl ist zu lang");
    }
    return canonical;
  }

  /**
   * Whether {@code host} is the loopback interface - the only host an MCP server or its
   * authorization server is reached on without TLS; the target check refuses it in production.
   */
  public static boolean isLoopback(String host) {
    if (host == null) {
      return false;
    }
    String bare =
        host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    return bare.equalsIgnoreCase("localhost")
        || bare.startsWith("127.")
        || bare.equals("::1")
        || bare.equals("0:0:0:0:0:0:0:1");
  }
}
