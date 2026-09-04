package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URISyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The one base-address rule every model role shares, and the path appending around it. */
class ModelEndpointUriTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://benutzer:geheim@host/v1",
        "https://benutzer@host/v1",
        "http://benutzer:geheim@host:8081",
        "benutzer:geheim@host/v1",
        // Neither parsable as a URI (a bare '[' in the authority) nor therefore covered by
        // URI#getRawUserInfo - the textual scan is what catches it.
        "https://benutzer:geh[eim@host/v1",
        "https://benutzer:geheim@host/v1?api-version=2024-02-01"
      })
  void anAddressCarryingUserinfoIsRecognizedAndRefused(String baseUrl) {
    assertThat(ModelEndpointUri.containsCredentials(baseUrl)).isTrue();
    assertThatThrownBy(() -> ModelEndpointUri.append(baseUrl, "/rerank"))
        .isInstanceOf(IllegalArgumentException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("geheim"));
  }

  /** An {@code @} outside the authority is a path or query character, not credentials. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://ollama:11434/v1",
        "https://modellserver.example.internal/v1/",
        "https://host/v1?user=a@b.example",
        "https://host/a@b/v1",
        ""
      })
  void anAddressWithoutUserinfoPasses(String baseUrl) {
    assertThat(ModelEndpointUri.containsCredentials(baseUrl)).isFalse();
  }

  @Test
  void aNullAddressIsNotCredentials() {
    assertThat(ModelEndpointUri.containsCredentials(null)).isFalse();
  }

  @Test
  void theAppendedPathNeverDoublesASlashAndKeepsTheQueryLast() throws URISyntaxException {
    assertThat(ModelEndpointUri.append("http://host/v1/", "/rerank"))
        .hasToString("http://host/v1/rerank");
    assertThat(ModelEndpointUri.append("  http://host/v1?api-version=2024-02-01 ", "/rerank"))
        .hasToString("http://host/v1/rerank?api-version=2024-02-01");
  }

  /**
   * The message travels into API responses and log lines: it names the rule and must not reproduce
   * any part of the address it rejects - not the user, not the password, not the host.
   */
  @Test
  void theRejectionTextNamesTheRuleWithoutQuotingTheRejectedAddress() {
    String message = ModelEndpointUri.CREDENTIALS_REJECTED_MESSAGE;

    assertThat(message).contains("Anmeldedaten");
    assertThat(message)
        .doesNotContain("benutzer:geheim")
        .doesNotContain("geheim")
        .doesNotContain("modellserver.example.internal");
  }
}
