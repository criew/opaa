package io.opaa.sourceaccess;

import io.opaa.compliance.DataFlowCarrier;
import io.opaa.observability.MeteredHttpClient;
import io.opaa.observability.OutboundMetrics;
import io.opaa.observability.OutboundTarget;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Builds the {@link HttpClient} and {@code Authorization} header shared by every source-access
 * caller (crawling, downloads, RSS fetches, the source connection test) - one place for the client
 * configuration instead of a copy per caller.
 */
@DataFlowCarrier
public final class SourceHttpClientFactory {

  private SourceHttpClientFactory() {}

  /**
   * Builds the {@link HttpClient} shared by every indexing/connection-test caller of this package.
   * {@code Redirect.NEVER}: the JDK's built-in redirect handling resends every request header -
   * {@code Authorization} included - to whatever host a {@code 3xx} response names, regardless of
   * the source configuration's own credentials ever having been meant for that host. Callers that
   * need to follow a redirect at all use {@link RedirectFollowingFetcher#sendFollowingRedirects},
   * which re-validates the target host/scheme on every hop and drops or refuses {@code
   * Authorization} the moment it stops matching, depending on the caller's {@link
   * RedirectFollowingFetcher.RedirectPolicy}.
   *
   * <p>The client asks the JVM's default {@code ProxySelector}, which is the outbound proxy ({@code
   * io.opaa.outbound.OutboundProxySelector}). The server certificate is always checked against the
   * JVM's truststore; there is no way to skip it (see {@link UntrustedCertificate}).
   *
   * <p>Every request counts in {@code opaa.outbound.requests} with target {@code connector} and
   * {@code kind}, the connector type in lower case ({@code confluence}, {@code msgraph}).
   */
  public static HttpClient buildHttpClient(String kind) {
    return MeteredHttpClient.of(
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(30))
            .build(),
        OutboundMetrics.global(OutboundTarget.CONNECTOR, kind));
  }

  public static String buildAuthHeader(String username, String password) {
    if (username != null && password != null) {
      String credentials = username + ":" + password;
      return "Basic "
          + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
    return null;
  }
}
