package io.opaa.sourceaccess;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the {@link HttpClient} and {@code Authorization} header shared by every source-access
 * caller (crawling, downloads, RSS fetches, the source connection test) - one place for proxy and
 * TLS configuration instead of a copy per caller.
 */
public final class SourceHttpClientFactory {

  private static final Logger log = LoggerFactory.getLogger(SourceHttpClientFactory.class);

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
   */
  public static HttpClient buildHttpClient(String proxyHost, int proxyPort, boolean insecureSsl) {
    HttpClient.Builder builder =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(30));

    if (proxyHost != null && !proxyHost.isBlank()) {
      builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
    }

    if (insecureSsl) {
      try {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(
            null,
            new TrustManager[] {
              new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() {
                  return new X509Certificate[0];
                }

                public void checkClientTrusted(X509Certificate[] c, String a) {}

                public void checkServerTrusted(X509Certificate[] c, String a) {}
              }
            },
            new SecureRandom());
        builder.sslContext(sslContext);
      } catch (NoSuchAlgorithmException | KeyManagementException e) {
        log.warn("Failed to create insecure SSL context: {}", e.getMessage());
      }
    }

    return builder.build();
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
