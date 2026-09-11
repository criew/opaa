package io.opaa.indexing.source.s3;

import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

/**
 * Everything {@link S3SdkClient} needs to build one SDK client against one endpoint - the shared
 * input of the connector's per-library store and the upload storage's long-lived client (ADR-0030,
 * Entscheidung 8). Credentials are the SDK's own type here, so a caller's credential format (the
 * connector's stored {@code accessKey:secretKey} string, the upload storage's two environment
 * values) stays the caller's business.
 *
 * @param endpoint the address every request goes to, scheme and host and port
 * @param region the signing region; blank falls back to {@link S3Connection#DEFAULT_REGION}
 * @param pathStyle {@code endpoint/bucket/key} when {@code true}, {@code bucket.endpoint/key}
 *     otherwise
 * @param proxyHost {@code null} without a proxy; {@code proxyPort} is ignored then
 * @param insecureSsl trust every certificate, for a self-signed endpoint
 * @param requestTimeout one value for connection, socket and per-attempt timeout
 * @param maxRetries retries after a throttled or transiently failed attempt; {@code 0} turns
 *     retries off
 * @param retryBackoff the base of the exponential, capped backoff between retries
 */
public record S3ClientSettings(
    URI endpoint,
    String region,
    boolean pathStyle,
    AwsCredentials credentials,
    String proxyHost,
    int proxyPort,
    boolean insecureSsl,
    Duration requestTimeout,
    int maxRetries,
    Duration retryBackoff) {

  public S3ClientSettings {
    if (endpoint == null) {
      throw new IllegalArgumentException("S3 client settings need an endpoint");
    }
    if (credentials == null) {
      throw new IllegalArgumentException("S3 client settings need credentials");
    }
    if (region == null || region.isBlank()) {
      region = S3Connection.DEFAULT_REGION;
    } else {
      region = region.strip();
    }
    if (proxyHost != null && proxyHost.isBlank()) {
      proxyHost = null;
    }
    if (proxyHost != null && (proxyPort < 1 || proxyPort > 65535)) {
      throw new IllegalArgumentException("a proxy needs a port in 1..65535, got " + proxyPort);
    }
    if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("requestTimeout must be positive, got " + requestTimeout);
    }
    if (maxRetries < 0) {
      throw new IllegalArgumentException("maxRetries must not be negative, got " + maxRetries);
    }
    if (retryBackoff == null || retryBackoff.isZero() || retryBackoff.isNegative()) {
      throw new IllegalArgumentException("retryBackoff must be positive, got " + retryBackoff);
    }
  }

  /** The settings of a library's connection under the access layer's bounds. */
  public static S3ClientSettings of(S3Connection connection, S3Properties properties) {
    S3Credentials c = connection.credentials();
    AwsCredentials credentials =
        c.hasSessionToken()
            ? AwsSessionCredentials.create(c.accessKey(), c.secretKey(), c.sessionToken())
            : AwsBasicCredentials.create(c.accessKey(), c.secretKey());
    return new S3ClientSettings(
        connection.endpoint(),
        connection.region(),
        connection.pathStyle(),
        credentials,
        connection.proxyHost(),
        connection.proxyPort(),
        connection.insecureSsl(),
        properties.requestTimeout(),
        properties.maxRetries(),
        properties.retryBackoff());
  }

  public boolean hasProxy() {
    return proxyHost != null;
  }

  /** Never prints the credentials - the record's generated form would. */
  @Override
  public String toString() {
    return "S3ClientSettings[endpoint="
        + endpoint
        + ", region="
        + region
        + ", pathStyle="
        + pathStyle
        + ", proxy="
        + (proxyHost == null ? "none" : proxyHost + ":" + proxyPort)
        + ", insecureSsl="
        + insecureSsl
        + ", requestTimeout="
        + requestTimeout
        + ", maxRetries="
        + maxRetries
        + "]";
  }
}
