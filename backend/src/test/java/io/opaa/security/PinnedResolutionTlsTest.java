package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.StandardConstants;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A bound name changes only where a client connects, not what TLS checks (ADR-0063): the client
 * still sends the name as SNI and verifies the certificate against the name, not against the pinned
 * address. The name exists in no DNS; only its binding to loopback makes it reachable.
 */
class PinnedResolutionTlsTest {

  private static final String NAME = "tls.rebind.opaa.test";
  private static final String PASSWORD = "changeit";
  private static final int POLICY = ResolutionPins.policyOf(List.of());

  private HttpsServer server;

  @AfterEach
  void tearDown() {
    ResolutionPins.process().release(NAME, POLICY);
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void aBoundNameIsSentAsSniAndItsCertificateIsAccepted() throws Exception {
    KeyStore certificate = selfSigned("dns:" + NAME);
    AtomicReference<String> sni = start(certificate);
    ResolutionPins.process().admit(NAME, POLICY, List.of(InetAddress.getByName("127.0.0.1")));

    HttpResponse<Void> response = get(certificate);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(sni.get()).isEqualTo(NAME);
  }

  @Test
  void aCertificateForThePinnedAddressButNotTheNameIsRefused() throws Exception {
    KeyStore certificate = selfSigned("ip:127.0.0.1,dns:other.rebind.opaa.test");
    AtomicReference<String> sni = start(certificate);
    ResolutionPins.process().admit(NAME, POLICY, List.of(InetAddress.getByName("127.0.0.1")));

    SSLHandshakeException failure =
        catchThrowableOfType(SSLHandshakeException.class, () -> get(certificate));

    assertThat(failure).as("the certificate names the address, not the name").isNotNull();
    assertThat(sni.get()).isEqualTo(NAME);
  }

  private AtomicReference<String> start(KeyStore certificate) throws Exception {
    AtomicReference<String> sni = new AtomicReference<>();
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(certificate, PASSWORD.toCharArray());
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keys.getKeyManagers(), null, null);
    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(
        new HttpsConfigurator(context) {
          @Override
          public void configure(HttpsParameters params) {
            SSLParameters parameters = getSSLContext().getDefaultSSLParameters();
            parameters.setSNIMatchers(List.of(new Recording(sni)));
            params.setSSLParameters(parameters);
          }
        });
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    return sni;
  }

  /** A client that trusts exactly {@code certificate}, checking host names as in production. */
  private HttpResponse<Void> get(KeyStore certificate) throws Exception {
    TrustManagerFactory trust =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(certificate);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, trust.getTrustManagers(), null);
    try (HttpClient client =
        HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(5)).build()) {
      return client.send(
          HttpRequest.newBuilder(
                  URI.create("https://" + NAME + ":" + server.getAddress().getPort() + "/"))
              .timeout(Duration.ofSeconds(10))
              .build(),
          HttpResponse.BodyHandlers.discarding());
    }
  }

  /** Records the host name of the client's SNI and accepts it. */
  private static final class Recording extends SNIMatcher {

    private final AtomicReference<String> sni;

    Recording(AtomicReference<String> sni) {
      super(StandardConstants.SNI_HOST_NAME);
      this.sni = sni;
    }

    @Override
    public boolean matches(SNIServerName serverName) {
      sni.set(new SNIHostName(serverName.getEncoded()).getAsciiName());
      return true;
    }
  }

  private static KeyStore selfSigned(String subjectAlternativeNames) throws Exception {
    Path file = Files.createTempFile("opaa-pinned-tls-", ".p12");
    Files.delete(file);
    try {
      String keytool =
          System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
      Process process =
          new ProcessBuilder(
                  keytool,
                  "-genkeypair",
                  "-alias",
                  "server",
                  "-keyalg",
                  "RSA",
                  "-keysize",
                  "2048",
                  "-validity",
                  "2",
                  "-dname",
                  "CN=opaa-test",
                  "-ext",
                  "SAN=" + subjectAlternativeNames,
                  "-storetype",
                  "PKCS12",
                  "-keystore",
                  file.toString(),
                  "-storepass",
                  PASSWORD,
                  "-keypass",
                  PASSWORD)
              .redirectErrorStream(true)
              .start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly();
        throw new IllegalStateException("keytool failed: " + output);
      }
      KeyStore store = KeyStore.getInstance("PKCS12");
      try (InputStream in = Files.newInputStream(file)) {
        store.load(in, PASSWORD.toCharArray());
      }
      return store;
    } finally {
      Files.deleteIfExists(file);
    }
  }
}
