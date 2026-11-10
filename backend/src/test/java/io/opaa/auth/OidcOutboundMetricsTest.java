package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.security.TargetAddressValidator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Discovery and JWK set requests count as target {@code oidc}, without issuer or address. */
class OidcOutboundMetricsTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private HttpServer server;
  private String issuer;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(401, -1);
          exchange.close();
        });
    server.start();
    issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/opaa";
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void aRefusedDiscoveryIsAnAuthErrorOfTheOidcTarget() {
    OidcDiscoveryClient client =
        new OidcDiscoveryClient(
            new OidcAddressPolicy(new TargetAddressValidator(true, List.of("127.0.0.1"))),
            meterRegistry);

    assertThat(catchThrowable(() -> client.fetchDiscovery(issuer)))
        .isInstanceOf(OidcDiscoveryClient.OidcProbeException.class);

    var timer =
        meterRegistry
            .get("opaa.outbound.requests")
            .tag("target", "oidc")
            .tag("kind", "none")
            .tag("outcome", "auth_error")
            .timer();
    assertThat(timer.count()).isEqualTo(1);
    assertThat(timer.getId().getTags())
        .noneMatch(
            tag -> tag.getValue().contains("127.0.0.1") || tag.getValue().contains("realms"));
  }
}
