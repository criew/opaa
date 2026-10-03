package io.opaa.connection.profile;

import io.opaa.common.ValidationException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * The server address of a profile and the rule that binds every target to it: a target lies under
 * the address when scheme, host and port match and its path is the address path or below it.
 */
public final class ServerAddress {

  private ServerAddress() {}

  /**
   * The address in its stored form: {@code http} or {@code https}, host, port and path without a
   * trailing slash; no user info, query or fragment.
   *
   * @throws ValidationException (German 400) for anything else
   */
  public static String normalize(String address) {
    if (address == null || address.isBlank()) {
      throw new ValidationException("serverUrl ist erforderlich");
    }
    URI uri = parse(address.trim());
    if (uri == null
        || uri.getHost() == null
        || !("http".equalsIgnoreCase(uri.getScheme())
            || "https".equalsIgnoreCase(uri.getScheme()))) {
      throw new ValidationException(
          "serverUrl muss eine absolute http- oder https-Adresse mit Host sein");
    }
    if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
      throw new ValidationException(
          "serverUrl darf weder Zugangsdaten noch Abfrage oder Fragment enthalten");
    }
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return uri.getScheme().toLowerCase(Locale.ROOT)
        + "://"
        + uri.getHost().toLowerCase(Locale.ROOT)
        + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
        + path;
  }

  /** Whether {@code target} lies under {@code address}; an unreadable target never does. */
  public static boolean covers(String address, String target) {
    URI base = parse(address);
    URI candidate = target == null ? null : parse(target.trim());
    if (base == null || candidate == null || candidate.getHost() == null) {
      return false;
    }
    if (!base.getScheme().equalsIgnoreCase(candidate.getScheme())
        || !base.getHost().equalsIgnoreCase(candidate.getHost())
        || port(base) != port(candidate)) {
      return false;
    }
    String basePath = base.getRawPath() == null ? "" : base.getRawPath();
    String path = candidate.getRawPath() == null ? "" : candidate.getRawPath();
    return basePath.isEmpty() || path.equals(basePath) || path.startsWith(basePath + "/");
  }

  /**
   * {@code target}, which lies under {@code oldAddress}, moved under {@code newAddress}: the part
   * below the old address, its query and fragment are kept.
   */
  public static String rebase(String target, String oldAddress, String newAddress) {
    if (!covers(oldAddress, target)) {
      throw new IllegalArgumentException("target does not lie under the old address");
    }
    URI candidate = Objects.requireNonNull(parse(target.trim()));
    String oldPath = parse(oldAddress).getRawPath();
    String path = candidate.getRawPath() == null ? "" : candidate.getRawPath();
    String below = path.substring(oldPath == null ? 0 : oldPath.length());
    return newAddress
        + below
        + (candidate.getRawQuery() == null ? "" : "?" + candidate.getRawQuery())
        + (candidate.getRawFragment() == null ? "" : "#" + candidate.getRawFragment());
  }

  /** Whether both addresses name the same scheme, host and port; an unreadable one never does. */
  public static boolean sameOrigin(String first, String second) {
    URI a = first == null ? null : parse(first.trim());
    URI b = second == null ? null : parse(second.trim());
    return a != null
        && b != null
        && a.getHost() != null
        && b.getHost() != null
        && a.getScheme().equalsIgnoreCase(b.getScheme())
        && a.getHost().equalsIgnoreCase(b.getHost())
        && port(a) == port(b);
  }

  private static int port(URI uri) {
    if (uri.getPort() >= 0) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private static URI parse(String value) {
    try {
      URI uri = new URI(value);
      return uri.getScheme() == null ? null : uri;
    } catch (URISyntaxException e) {
      return null;
    }
  }
}
