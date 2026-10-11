package io.opaa.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;

class ProxyRefusalTest {

  @Test
  void recognizesTheJdkClientsRefusedTunnel() {
    assertThat(ProxyRefusal.describe(new IOException("Tunnel failed, got: 403")))
        .hasValueSatisfying(
            message ->
                assertThat(message)
                    .startsWith("Der Ausgangsproxy hat die Verbindung zum Ziel abgelehnt")
                    .contains("Status 403")
                    .contains("Ausnahmeliste"));
  }

  @Test
  void recognizesOkHttpsRefusedTunnelAndAMissingProxyLogin() {
    assertThat(ProxyRefusal.describe(new IOException("Unexpected response code for CONNECT: 502")))
        .hasValueSatisfying(message -> assertThat(message).contains("Status 502"));
    assertThat(ProxyRefusal.describe(new IOException("Failed to authenticate with proxy")))
        .hasValueSatisfying(message -> assertThat(message).contains("Status 407"));
  }

  @Test
  void looksIntoTheCauses() {
    IOException wrapped =
        new IOException("wrapper", new RuntimeException("CONNECT refused by proxy: HTTP/1.1 403"));

    assertThat(ProxyRefusal.describe(wrapped)).isPresent();
  }

  @Test
  void anyOtherFailureIsNoRefusal() {
    assertThat(ProxyRefusal.describe(new ConnectException("Connection refused"))).isEmpty();
  }
}
