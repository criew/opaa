package io.opaa.indexing.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import io.opaa.sourceaccess.UntrustedCertificate;
import io.opaa.test.LoopbackTls;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** A self-signed certificate fails the connection test with the hint to add the own authority. */
class ConnectorChecksUntrustedCertificateTest {

  @Test
  void aSelfSignedCertificateReadsAsUntrusted() throws Exception {
    HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(LoopbackTls.serverContext()));
    server.start();
    try {
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

      assertThat(ConnectorChecks.translateConnectionError(failure))
          .isEqualTo(UntrustedCertificate.MESSAGE);
    } finally {
      server.stop(0);
    }
  }
}
