package io.opaa.indexing.source.confluence;

import io.opaa.api.types.ConfluenceEdition;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Everything an adapter needs to talk to one Confluence instance on behalf of one library: the
 * normalised base address, the edition, the credentials and the outbound connection options a
 * library's source configuration already carries for the URL-based source types.
 *
 * @param baseUrl normalised by {@link #normalizeBaseUrl}: no trailing slash, no query/fragment, no
 *     user info; for Cloud a trailing {@code /wiki} is removed because the Cloud adapter adds it
 * @param credentials {@code null} only for the credential-free probes (edition detection)
 * @param proxyHost {@code null} when no proxy is configured
 */
public record ConfluenceConnection(
    URI baseUrl,
    ConfluenceEdition edition,
    ConfluenceCredentials credentials,
    String proxyHost,
    int proxyPort,
    boolean insecureSsl) {

  /**
   * Normalises a user-entered Confluence address.
   *
   * @throws InvalidBaseUrlException with a German, user-facing message when the address is not an
   *     absolute {@code http}/{@code https} URL with a host, or carries user info (credentials
   *     belong into the credentials field, never into the address)
   */
  public static URI normalizeBaseUrl(String raw, ConfluenceEdition edition) {
    if (raw == null || raw.isBlank()) {
      throw new InvalidBaseUrlException("Die Confluence-Adresse ist erforderlich.");
    }
    URI uri;
    try {
      uri = new URI(raw.strip());
    } catch (URISyntaxException e) {
      throw new InvalidBaseUrlException("Die Confluence-Adresse ist keine gültige URL.");
    }
    String scheme = uri.getScheme();
    if (scheme == null
        || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
        || uri.getHost() == null) {
      throw new InvalidBaseUrlException(
          "Die Confluence-Adresse muss mit http:// oder https:// beginnen und einen Host nennen.");
    }
    if (uri.getRawUserInfo() != null) {
      throw new InvalidBaseUrlException(
          "Die Confluence-Adresse darf keine Zugangsdaten enthalten; diese gehören in das Feld für"
              + " Zugangsdaten.");
    }
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    if (edition == ConfluenceEdition.CLOUD && path.endsWith("/wiki")) {
      path = path.substring(0, path.length() - "/wiki".length());
    }
    try {
      return new URI(
          scheme.toLowerCase(), null, uri.getHost().toLowerCase(), uri.getPort(), path, null, null);
    } catch (URISyntaxException e) {
      throw new InvalidBaseUrlException("Die Confluence-Adresse ist keine gültige URL.");
    }
  }

  /** {@code baseUrl} plus an absolute path (starting with {@code /}) and optional query. */
  String url(String pathAndQuery) {
    return baseUrl.toString() + pathAndQuery;
  }

  /**
   * Never prints the credentials - the record's generated form would print their {@code
   * toString()}.
   */
  @Override
  public String toString() {
    return "ConfluenceConnection[baseUrl="
        + baseUrl
        + ", edition="
        + edition
        + ", credentials="
        + (credentials == null ? "none" : "***")
        + ", proxy="
        + proxyHost
        + "]";
  }

  /** Thrown by {@link #normalizeBaseUrl}. */
  public static final class InvalidBaseUrlException extends RuntimeException {
    public InvalidBaseUrlException(String message) {
      super(message);
    }
  }
}
