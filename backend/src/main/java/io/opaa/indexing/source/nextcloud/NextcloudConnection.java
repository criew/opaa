package io.opaa.indexing.source.nextcloud;

import io.opaa.indexing.source.SourceSettings;
import io.opaa.sourceaccess.ProxyAndCredentials;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * How one library reaches its Nextcloud: the instance address, the technical user with its app
 * password (Basic Auth), proxy and TLS switch. Every request goes to {@link #baseUrl()}'s origin;
 * {@link #toString()} never names the password.
 *
 * @param baseUrl the instance address without a trailing slash, possibly with a context path
 */
record NextcloudConnection(
    URI baseUrl,
    String username,
    String password,
    String proxyHost,
    int proxyPort,
    boolean insecureSsl) {

  static final String CREDENTIALS_FORMAT =
      "sourceCredentials müssen dem Format Benutzername:App-Passwort entsprechen";

  /**
   * Assembles the connection from the source settings the core resolved and the secret valid now.
   *
   * @throws InvalidNextcloudConfigurationException with a German sentence naming the defect
   */
  static NextcloudConnection of(SourceSettings source, String credentials) {
    URI baseUrl = normalizeBaseUrl(source.sourceUrl());
    ProxyAndCredentials parsed;
    try {
      parsed = ProxyAndCredentials.parse(source.sourceProxy(), credentials);
    } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
      throw new InvalidNextcloudConfigurationException(e.getMessage());
    }
    if (parsed.username() == null || parsed.password() == null || parsed.password().isEmpty()) {
      throw new InvalidNextcloudConfigurationException(CREDENTIALS_FORMAT);
    }
    return new NextcloudConnection(
        baseUrl,
        parsed.username(),
        parsed.password(),
        parsed.proxyHost(),
        parsed.proxyPort(),
        source.sourceInsecureSsl());
  }

  /**
   * The instance address as stored: {@code http(s)}, a host, no credentials, query or fragment, cut
   * before {@code /remote.php} or {@code /index.php} (a pasted WebDAV address), without a trailing
   * slash.
   *
   * @throws InvalidNextcloudConfigurationException for anything else
   */
  static URI normalizeBaseUrl(String url) {
    if (url == null || url.isBlank()) {
      throw new InvalidNextcloudConfigurationException(
          "sourceUrl (Adresse der Nextcloud) ist erforderlich");
    }
    URI uri;
    try {
      uri = new URI(url.trim());
    } catch (URISyntaxException e) {
      throw new InvalidNextcloudConfigurationException("sourceUrl ist keine gültige URL");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) {
      throw new InvalidNextcloudConfigurationException(
          "sourceUrl muss mit http:// oder https:// beginnen");
    }
    if (uri.getHost() == null) {
      throw new InvalidNextcloudConfigurationException("sourceUrl nennt keinen Host");
    }
    if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
      throw new InvalidNextcloudConfigurationException(
          "sourceUrl darf weder Zugangsdaten noch Query oder Fragment enthalten");
    }
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    for (String marker : new String[] {"/remote.php", "/index.php"}) {
      int at = path.indexOf(marker);
      if (at >= 0) {
        path = path.substring(0, at);
      }
    }
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return URI.create(scheme + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT) + path);
  }

  /** The context path of the instance, {@code ""} at the host's root. */
  String contextPath() {
    return baseUrl.getRawPath() == null ? "" : baseUrl.getRawPath();
  }

  /** The absolute URL of an already encoded absolute path on the instance's origin. */
  String url(String encodedAbsolutePath) {
    return baseUrl.getScheme() + "://" + baseUrl.getRawAuthority() + encodedAbsolutePath;
  }

  /** The WebDAV root every principal and file address lies below. */
  String davRoot() {
    return contextPath() + "/remote.php/dav/";
  }

  /** The address that opens the file {@code fileId} in the Nextcloud web interface. */
  String deepLink(String fileId) {
    return baseUrl + "/index.php/f/" + fileId;
  }

  String authorizationHeader() {
    return SourceHttpClientFactory.buildAuthHeader(username, password);
  }

  @Override
  public String toString() {
    return "NextcloudConnection[baseUrl="
        + baseUrl
        + ", username="
        + username
        + ", proxyHost="
        + proxyHost
        + ", insecureSsl="
        + insecureSsl
        + "]";
  }

  /** The configuration cannot be turned into a connection; the message is user-facing. */
  static final class InvalidNextcloudConfigurationException extends RuntimeException {
    InvalidNextcloudConfigurationException(String message) {
      super(message);
    }
  }
}
