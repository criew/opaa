package io.opaa.directory.sync.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.directory.sync.keycloak.KeycloakDirectoryProperties;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The directory client counts as target {@code directory} with its provider as {@code kind}. */
class DirectoryOutboundMetricsTest {

  @Test
  void aThrottledAnswerOfTheKeycloakDirectoryIsCounted() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(429, -1);
          exchange.close();
        });
    server.start();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    try (HttpClient client =
        new DirectoryConnectorConfiguration()
            .directoryHttpClient(
                new KeycloakDirectoryProperties(
                    100, 100, 100, 100, Duration.ofSeconds(5), Duration.ofSeconds(5)),
                meterRegistry)) {
      client.send(
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/admin"))
              .build(),
          HttpResponse.BodyHandlers.discarding());
    } finally {
      server.stop(0);
    }

    assertThat(
            meterRegistry
                .get("opaa.outbound.requests")
                .tag("target", "directory")
                .tag("kind", "keycloak")
                .tag("outcome", "throttled")
                .tag("path_class", "background")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get("opaa.outbound.throttled")
                .tag("target", "directory")
                .tag("path_class", "background")
                .counter()
                .count())
        .isEqualTo(1.0);
  }
}
