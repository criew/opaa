package io.opaa.indexing.source.smb;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * The share a library reads, from {@code sourceUrl}: {@code smb://server[:port]/freigabe}. A UNC
 * path ({@code \\server\freigabe}) is accepted and stored in the same normalised form; a path below
 * the share is refused, folders are connector settings. Every request of the library goes to {@link
 * #host()}.
 *
 * @param port the TCP port, {@code 445} unless the address names another
 */
record SmbAddress(String host, int port, String share) {

  static final int DEFAULT_PORT = 445;
  private static final String SCHEME = "smb://";
  private static final String FORBIDDEN_SHARE_CHARACTERS = "\\/:*?\"<>|";
  private static final int MAX_SHARE_LENGTH = 80;

  /**
   * The address {@code url} names.
   *
   * @throws InvalidSmbConfigurationException with a German sentence naming the defect
   */
  static SmbAddress parse(String url) {
    if (url == null || url.isBlank()) {
      throw new InvalidSmbConfigurationException(
          "sourceUrl (Adresse der Freigabe, smb://server/freigabe) ist erforderlich");
    }
    String text = url.trim();
    if (text.startsWith("\\\\")) {
      text = SCHEME + text.substring(2).replace('\\', '/');
    }
    if (!text.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
      throw new InvalidSmbConfigurationException(
          "sourceUrl muss mit smb:// beginnen (smb://server/freigabe)");
    }
    String rest = text.substring(SCHEME.length());
    int slash = rest.indexOf('/');
    String authority = slash < 0 ? rest : rest.substring(0, slash);
    String path = slash < 0 ? "" : rest.substring(slash + 1);
    if (authority.indexOf('@') >= 0) {
      throw new InvalidSmbConfigurationException(
          "sourceUrl darf keine Zugangsdaten enthalten; sie gehören in sourceCredentials");
    }
    URI server;
    try {
      server = new URI("smb://" + authority);
    } catch (URISyntaxException e) {
      throw new InvalidSmbConfigurationException("sourceUrl nennt keinen gültigen Server");
    }
    if (server.getHost() == null || server.getRawPath() != null && !server.getRawPath().isEmpty()) {
      throw new InvalidSmbConfigurationException("sourceUrl nennt keinen gültigen Server");
    }
    if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
      throw new InvalidSmbConfigurationException(
          "sourceUrl darf weder Query noch Fragment enthalten");
    }
    String share = decode(stripSlashes(path));
    if (share.isEmpty()) {
      throw new InvalidSmbConfigurationException(
          "sourceUrl nennt keine Freigabe (smb://server/freigabe)");
    }
    if (share.indexOf('/') >= 0) {
      throw new InvalidSmbConfigurationException(
          "sourceUrl nennt nur Server und Freigabe; Ordner darin werden als Ordner der Bibliothek"
              + " gewählt");
    }
    requireShareName(share);
    int port = server.getPort() < 0 ? DEFAULT_PORT : server.getPort();
    return new SmbAddress(server.getHost().toLowerCase(Locale.ROOT), port, share);
  }

  /** The stored form: lower-case server, the default port left out, the share percent-encoded. */
  String url() {
    try {
      return new URI("smb", null, host, port == DEFAULT_PORT ? -1 : port, "/" + share, null, null)
          .toString();
    } catch (URISyntaxException e) {
      throw new IllegalStateException("a parsed address is always a URI", e);
    }
  }

  /**
   * A document's {@code file_path}: server, share and the path below it, none of it encoded - the
   * identity of the file in the library.
   */
  String filePath(String relativePath) {
    return filePathPrefix() + relativePath;
  }

  /** The path below the share {@code filePath} names, empty when it names another share. */
  Optional<String> relativePath(String filePath) {
    String prefix = filePathPrefix();
    if (filePath == null || !filePath.startsWith(prefix) || filePath.length() == prefix.length()) {
      return Optional.empty();
    }
    return Optional.of(filePath.substring(prefix.length()));
  }

  /**
   * Whether credentials stored for {@code stored} also stand for {@code requested}: the same
   * server, port and share - on a file server the share is a boundary of rights. Unreadable
   * addresses never match.
   */
  static boolean sameShare(String stored, String requested) {
    try {
      SmbAddress before = parse(stored);
      SmbAddress after = parse(requested);
      return before.host.equals(after.host)
          && before.port == after.port
          && before.share.equalsIgnoreCase(after.share);
    } catch (InvalidSmbConfigurationException e) {
      return false;
    }
  }

  /** The host as a socket takes it - an IPv6 literal without its brackets. */
  String socketHost() {
    return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
  }

  private String filePathPrefix() {
    return SCHEME + host + (port == DEFAULT_PORT ? "" : ":" + port) + "/" + share + "/";
  }

  private static void requireShareName(String share) {
    if (share.length() > MAX_SHARE_LENGTH) {
      throw new InvalidSmbConfigurationException(
          "sourceUrl nennt keine gültige Freigabe: „" + share + "“");
    }
    if (share.endsWith("$")) {
      // hidden shares are never bound; this includes C$, ADMIN$ and IPC$ (a whole volume or no
      // files)
      throw new InvalidSmbConfigurationException(
          "sourceUrl nennt eine versteckte Freigabe („"
              + share
              + "“); versteckte Freigaben (Name endet auf $) werden nicht angebunden, bitte eine sichtbare Freigabe angeben");
    }
    for (int i = 0; i < share.length(); i++) {
      char c = share.charAt(i);
      if (c < 0x20 || FORBIDDEN_SHARE_CHARACTERS.indexOf(c) >= 0) {
        throw new InvalidSmbConfigurationException(
            "sourceUrl nennt keine gültige Freigabe: „" + share + "“");
      }
    }
  }

  private static String stripSlashes(String path) {
    int start = 0;
    int end = path.length();
    while (start < end && path.charAt(start) == '/') {
      start++;
    }
    while (end > start && path.charAt(end - 1) == '/') {
      end--;
    }
    return path.substring(start, end);
  }

  /** Percent-escapes decoded; a {@code +} stays a plus sign. */
  private static String decode(String path) {
    if (path.indexOf('%') < 0) {
      return path;
    }
    try {
      return URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new InvalidSmbConfigurationException("sourceUrl enthält eine ungültige Kodierung");
    }
  }

  @Override
  public String toString() {
    return url();
  }

  /** The configuration cannot be turned into a connection; the message is user-facing. */
  static final class InvalidSmbConfigurationException extends RuntimeException {
    InvalidSmbConfigurationException(String message) {
      super(message);
    }
  }
}
