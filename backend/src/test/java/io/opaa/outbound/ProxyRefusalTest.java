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
  void aProxyThatCannotReachTheTargetNamesATypoAsPossibleCause() {
    assertThat(ProxyRefusal.describe(new IOException("Tunnel failed, got: 502")))
        .hasValueSatisfying(
            message ->
                assertThat(message)
                    .startsWith("Der Ausgangsproxy hat das Ziel nicht erreicht (Status 502)")
                    .contains("Tippfehler"));
  }

  @Test
  void onlyA407AnswerIsTheProxys() {
    assertThat(ProxyRefusal.describeStatus(407))
        .hasValueSatisfying(message -> assertThat(message).contains("Status 407"));
    assertThat(ProxyRefusal.describeStatus(403)).isEmpty();
    assertThat(ProxyRefusal.describeStatus(502)).isEmpty();
  }

  @Test
  void aStatusFailureOfThe407IsTheProxysAndRecognizedDownTheCauses() {
    IOException refused = ProxyRefusal.statusFailure(407, "HTTP 407 for URL: https://x.example");
    assertThat(refused).isInstanceOf(ProxyRefusal.RefusedException.class);
    assertThat(refused.getMessage()).contains("Status 407").doesNotContain("https://x.example");
    assertThat(ProxyRefusal.describe(new IOException("wrapper", refused)))
        .contains(refused.getMessage());

    IOException other = ProxyRefusal.statusFailure(500, "HTTP 500 for URL: https://x.example");
    assertThat(other).isNotInstanceOf(ProxyRefusal.RefusedException.class);
    assertThat(ProxyRefusal.describe(other)).isEmpty();
  }

  @Test
  void aFailureMessageFallsBackUnlessTheProxyRefused() {
    assertThat(
            ProxyRefusal.failureMessage(
                new IOException("Tunnel failed, got: 403"), "Der Anbieter ist nicht erreichbar."))
        .startsWith("Der Ausgangsproxy hat die Verbindung zum Ziel abgelehnt (Status 403)");
    assertThat(
            ProxyRefusal.failureMessage(
                new ConnectException("Connection refused"), "Der Anbieter ist nicht erreichbar."))
        .isEqualTo("Der Anbieter ist nicht erreichbar.");
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
