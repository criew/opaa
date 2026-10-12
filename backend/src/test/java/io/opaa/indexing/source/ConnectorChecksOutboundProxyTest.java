package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.opaa.sourceaccess.SourceHttpClientFactory;
import io.opaa.test.OutboundProxyRoute;
import io.opaa.test.RecordingHttpProxy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** A tunnel the outbound proxy refuses reads as such, not as an unreachable address. */
class ConnectorChecksOutboundProxyTest {

  @Test
  void aRefusedTunnelNamesTheOutboundProxy() throws Exception {
    try (RecordingHttpProxy proxy = new RecordingHttpProxy();
        OutboundProxyRoute route = OutboundProxyRoute.through(proxy.address())) {
      proxy.refuseTunnels(403);
      HttpClient client = SourceHttpClientFactory.buildHttpClient();

      IOException failure =
          catchThrowableOfType(
              IOException.class,
              () ->
                  client.send(
                      HttpRequest.newBuilder(URI.create("https://wiki.example.invalid/")).build(),
                      HttpResponse.BodyHandlers.discarding()));

      assertThat(ConnectorChecks.translateConnectionError(failure))
          .startsWith("Der Ausgangsproxy hat die Verbindung zum Ziel abgelehnt (Status 403)");
      assertThat(proxy.requests()).containsExactly("CONNECT wiki.example.invalid:443");
    }
  }

  /** The JDK client hands a proxy's {@code 407} back as an answer, not as an exception. */
  @Test
  void aProxyAskingForALoginIsNamedAsSuch() throws Exception {
    try (RecordingHttpProxy proxy = new RecordingHttpProxy();
        OutboundProxyRoute route = OutboundProxyRoute.through(proxy.address())) {
      proxy.refuseTunnels(407);
      HttpClient client = SourceHttpClientFactory.buildHttpClient();

      HttpResponse<Void> response =
          client.send(
              HttpRequest.newBuilder(URI.create("https://wiki.example.invalid/")).build(),
              HttpResponse.BodyHandlers.discarding());

      assertThat(response.statusCode()).isEqualTo(407);
      assertThat(ConnectorChecks.unexpectedStatus(response.statusCode()))
          .startsWith("Der Ausgangsproxy hat die Verbindung zum Ziel abgelehnt (Status 407)");
      assertThat(ConnectorChecks.unexpectedStatus(500))
          .isEqualTo("Der Server antwortete mit HTTP 500.");
    }
  }
}
