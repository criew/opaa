package io.opaa.indexing.source.s3;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Everything the adapter needs to talk to one object store on behalf of one library: the normalised
 * endpoint, the signing region, the addressing style, the credentials and the outbound connection
 * options a library's source configuration carries for every URL-based type.
 *
 * @param endpoint normalised by {@link #normalizeEndpoint}: scheme and host lower-case, no path,
 *     query, fragment or user info
 * @param region the region every request is signed for; blank falls back to {@link
 *     #DEFAULT_REGION}, so the SDK's own region resolution (environment, profile, instance
 *     metadata) is never consulted (ADR-0027, Entscheidung 7)
 * @param pathStyle {@code true} addresses {@code endpoint/bucket/key} (MinIO, Ceph), {@code false}
 *     {@code bucket.endpoint/key} (AWS, Hetzner)
 * @param proxyHost {@code null} when no proxy is configured
 */
public record S3Connection(
    URI endpoint,
    String region,
    boolean pathStyle,
    S3Credentials credentials,
    String proxyHost,
    int proxyPort,
    boolean insecureSsl) {

  public static final String DEFAULT_REGION = "us-east-1";

  public S3Connection {
    if (endpoint == null) {
      throw new IllegalArgumentException("an S3Connection needs an endpoint");
    }
    if (credentials == null) {
      throw new IllegalArgumentException("an S3Connection needs credentials");
    }
    if (region == null || region.isBlank()) {
      region = DEFAULT_REGION;
    } else {
      region = region.strip();
    }
  }

  /**
   * Normalises a user-entered endpoint address.
   *
   * @throws InvalidEndpointException with a German, user-facing message when the address is not an
   *     absolute {@code http}/{@code https} URL with a host, carries a path, query or user info
   */
  public static URI normalizeEndpoint(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new InvalidEndpointException("Der Endpoint des Objektspeichers ist erforderlich.");
    }
    URI uri;
    try {
      uri = new URI(raw.strip());
    } catch (URISyntaxException e) {
      throw new InvalidEndpointException("Der Endpoint ist keine gültige URL.");
    }
    String scheme = uri.getScheme();
    if (scheme == null
        || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
        || uri.getHost() == null) {
      throw new InvalidEndpointException(
          "Der Endpoint muss mit http:// oder https:// beginnen und einen Host nennen.");
    }
    if (uri.getRawUserInfo() != null) {
      throw new InvalidEndpointException(
          "Der Endpoint darf keine Zugangsdaten enthalten; diese gehören in das Feld für"
              + " Zugangsdaten.");
    }
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    if (!path.isEmpty()) {
      throw new InvalidEndpointException(
          "Der Endpoint darf keinen Pfad enthalten; Bucket und Präfix gehören in die"
              + " Geltungsbereiche.");
    }
    if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
      throw new InvalidEndpointException(
          "Der Endpoint darf weder Abfrage noch Fragment enthalten.");
    }
    try {
      return new URI(
          scheme.toLowerCase(), null, uri.getHost().toLowerCase(), uri.getPort(), null, null, null);
    } catch (URISyntaxException e) {
      throw new InvalidEndpointException("Der Endpoint ist keine gültige URL.");
    }
  }

  /** Never prints the credentials - the record's generated form would. */
  @Override
  public String toString() {
    return "S3Connection[endpoint="
        + endpoint
        + ", region="
        + region
        + ", pathStyle="
        + pathStyle
        + ", proxy="
        + (proxyHost == null ? "none" : proxyHost + ":" + proxyPort)
        + ", insecureSsl="
        + insecureSsl
        + "]";
  }

  /** Thrown by {@link #normalizeEndpoint} for an address that cannot be an endpoint. */
  public static final class InvalidEndpointException extends RuntimeException {
    public InvalidEndpointException(String message) {
      super(message);
    }
  }
}
