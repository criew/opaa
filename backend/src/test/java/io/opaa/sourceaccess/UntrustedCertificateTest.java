package io.opaa.sourceaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.opaa.test.LoopbackTls;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The source-access client checks every server certificate against the JVM's truststore: a
 * certificate no known authority issued is refused, and the refusal reads as an untrusted
 * certificate with the hint to add the own authority. The loopback certificate is self-signed and
 * trusted by no default truststore.
 */
class UntrustedCertificateTest {

  private HttpsServer server;

  @BeforeEach
  void start() throws IOException {
    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(LoopbackTls.serverContext()));
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void aCertificateOfAnUnknownAuthorityIsRefused() {
    IOException failure =
        catchThrowableOfType(
            IOException.class,
            () ->
                SourceHttpClientFactory.buildHttpClient()
                    .send(
                        HttpRequest.newBuilder(
                                URI.create(
                                    "https://127.0.0.1:" + server.getAddress().getPort() + "/"))
                            .timeout(Duration.ofSeconds(10))
                            .build(),
                        HttpResponse.BodyHandlers.discarding()));

    assertThat(failure).isNotNull();
    assertThat(failure).isInstanceOf(SSLHandshakeException.class);
    assertThat(UntrustedCertificate.causes(failure)).isTrue();
    assertThat(UntrustedCertificate.describe(failure)).contains(UntrustedCertificate.MESSAGE);
  }

  @Test
  void theMessageNamesTheOwnAuthority() {
    assertThat(UntrustedCertificate.MESSAGE)
        .contains("Zertifikat")
        .contains("eigene Zertifizierungsstelle")
        .contains("ergänzen");
  }

  @Test
  void anotherFailureIsNoCertificateRefusal() {
    assertThat(UntrustedCertificate.describe(new ConnectException("refused"))).isEmpty();
  }
}
