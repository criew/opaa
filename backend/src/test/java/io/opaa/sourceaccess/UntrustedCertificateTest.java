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
import java.security.cert.CertificateException;
import java.time.Duration;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
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

  /**
   * A certificate the JVM trusts but issued for another name: the loopback certificate covers
   * {@code 127.0.0.1} and {@code localhost}, not {@code 127.0.0.2}.
   */
  @Test
  void aTrustedCertificateForAnotherNameIsRefusedToo() throws IOException {
    HttpsServer anyAddress = HttpsServer.create(new InetSocketAddress(0), 0);
    anyAddress.setHttpsConfigurator(new HttpsConfigurator(LoopbackTls.serverContext()));
    anyAddress.start();
    LoopbackTls.trustInThisJvm();
    try {
      IOException failure =
          catchThrowableOfType(
              IOException.class,
              () ->
                  SourceHttpClientFactory.buildHttpClient()
                      .send(
                          HttpRequest.newBuilder(
                                  URI.create(
                                      "https://127.0.0.2:"
                                          + anyAddress.getAddress().getPort()
                                          + "/"))
                              .timeout(Duration.ofSeconds(10))
                              .build(),
                          HttpResponse.BodyHandlers.discarding()));

      assertThat(failure).isInstanceOf(SSLHandshakeException.class);
      assertThat(UntrustedCertificate.describe(failure)).contains(UntrustedCertificate.MESSAGE);
    } finally {
      LoopbackTls.restore();
      anyAddress.stop(0);
    }
  }

  /** Apache HttpClient 5 reports another name without a {@code CertificateException} cause. */
  @Test
  void aPeerNotVerifiedForItsNameIsAnUntrustedCertificate() {
    assertThat(
            UntrustedCertificate.causes(
                new IOException(
                    new SSLPeerUnverifiedException(
                        "Certificate for <s3.intern> doesn't match any of the subject alternative"
                            + " names: [other.intern]"))))
        .isTrue();
  }

  /** Only a TLS failure counts: a certificate problem elsewhere is not the server's certificate. */
  @Test
  void aCertificateProblemOutsideATlsHandshakeDoesNotCount() {
    assertThat(
            UntrustedCertificate.causes(
                new IOException(new CertificateException("unreadable PEM in a key file"))))
        .isFalse();
    assertThat(UntrustedCertificate.causes(new SSLException("Unsupported SSL message"))).isFalse();
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
