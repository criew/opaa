package io.opaa.sourceaccess;

import io.opaa.security.AddressCheckingHttpClient;
import io.opaa.security.ConnectionAddressResolver;
import io.opaa.security.TargetAddressValidator;
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
   * Every connection it opens resolves the target through {@code validator} at connect time and
   * connects only to an address that passed it ({@link AddressCheckingHttpClient}). It never
   * follows a redirect: the JDK's built-in redirect handling would resend every request header -
   * {@code Authorization} included - to whatever host a {@code 3xx} response names. Callers that
   * need to follow a redirect use {@link RedirectFollowingFetcher#sendFollowingRedirects}, which
   * re-validates the target host/scheme on every hop and drops or refuses {@code Authorization} the
   * moment it stops matching, depending on the caller's {@link
   * RedirectFollowingFetcher.RedirectPolicy}.
   */
  public static HttpClient buildHttpClient(
      TargetAddressValidator validator, String proxyHost, int proxyPort, boolean insecureSsl) {
    AddressCheckingHttpClient.Builder builder =
        AddressCheckingHttpClient.newBuilder(ConnectionAddressResolver.of(validator))
            .connectTimeout(Duration.ofSeconds(30))
            .proxy(proxyHost, proxyPort);

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
