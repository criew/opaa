package io.opaa.connection.profile;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ServerAddressRule;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The server address of a profile and the rule that binds every target to it: a target lies under
 * the address when scheme, host and port match and its path is the address path or below it.
 */
public final class ServerAddress {

  private ServerAddress() {}

  /**
   * The address in its stored form: a scheme {@code rule} admits, host, port and path without a
   * trailing slash; no user info, query or fragment. Under a fixed rule a blank address stands for
   * the fixed one, and any other is refused.
   *
   * @throws ValidationException (German 400) for anything else
   */
  public static String normalize(String address, ServerAddressRule rule) {
    if (rule.isFixed()) {
      List<String> scheme = List.of(schemeOf(rule.fixed()));
      String fixed = normalize(rule.fixed(), scheme);
      if (address == null || address.isBlank() || fixed.equals(normalizeOrNull(address, scheme))) {
        return fixed;
      }
      throw new ValidationException("serverUrl ist für diese Quellart fest vorgegeben: " + fixed);
    }
    return normalize(address, rule.schemes());
  }

  private static String normalize(String address, List<String> schemes) {
    if (address == null || address.isBlank()) {
      throw new ValidationException("serverUrl ist erforderlich");
    }
    URI uri = parse(address.trim());
    if (uri == null
        || uri.getHost() == null
        || !schemes.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
      throw new ValidationException(
          "serverUrl muss eine absolute Adresse mit Host sein, beginnend mit "
              + String.join("://, ", schemes)
              + "://");
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

  private static String normalizeOrNull(String address, List<String> schemes) {
    try {
      return normalize(address, schemes);
    } catch (ValidationException e) {
      return null;
    }
  }

  private static String schemeOf(String address) {
    URI uri = parse(address.trim());
    if (uri == null) {
      throw new IllegalArgumentException("a fixed address carries a scheme");
    }
    return uri.getScheme().toLowerCase(Locale.ROOT);
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
    String basePath = pathOf(base);
    String path = pathOf(candidate);
    if (path == null || basePath == null) {
      return false;
    }
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

  /**
   * The one rule whether a stored secret may follow a library to {@code second}: same scheme, host
   * and port, an omitted port counting as the default only for {@code http} and {@code https}. An
   * absent, unreadable or padded address, or one without a host, never matches.
   */
  public static boolean sameOrigin(String first, String second) {
    if (first == null || second == null) {
      return false;
    }
    try {
      return RedirectFollowingFetcher.sameOrigin(URI.create(first), URI.create(second));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * The origin {@link #sameOrigin} compares, as text: scheme and host in lower case and the port,
   * an omitted one as the default only for {@code http} and {@code https}; {@code null} where
   * {@link #sameOrigin} never matches.
   */
  public static String originOf(String address) {
    if (address == null) {
      return null;
    }
    URI uri;
    try {
      uri = URI.create(address);
    } catch (IllegalArgumentException e) {
      return null;
    }
    if (uri.getScheme() == null || uri.getHost() == null) {
      return null;
    }
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    int port = uri.getPort();
    if (port < 0 && scheme.equals("https")) {
      port = 443;
    } else if (port < 0 && scheme.equals("http")) {
      port = 80;
    }
    return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (port < 0 ? "" : ":" + port);
  }

  /**
   * The path with dot segments resolved and without a trailing slash; {@code null} when it climbs
   * above the root or encodes a dot segment, which a server might resolve after this check.
   */
  private static String pathOf(URI uri) {
    String raw = uri.getRawPath() == null ? "" : uri.getRawPath();
    if (raw.toLowerCase(Locale.ROOT).contains("%2e")) {
      return null;
    }
    Deque<String> segments = new ArrayDeque<>();
    for (String segment : raw.split("/")) {
      if (segment.isEmpty() || segment.equals(".")) {
        continue;
      }
      if (segment.equals("..")) {
        if (segments.isEmpty()) {
          return null;
        }
        segments.removeLast();
      } else {
        segments.addLast(segment);
      }
    }
    return segments.isEmpty() ? "" : "/" + String.join("/", segments);
  }

  private static int port(URI uri) {
    if (uri.getPort() >= 0) {
      return uri.getPort();
    }
    return switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
      case "https" -> 443;
      case "smb" -> 445;
      default -> 80;
    };
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
