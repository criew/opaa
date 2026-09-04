package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The binding rules of the rerank role's configuration, and the one rule about its access key. */
class RerankPropertiesTest {

  private static final String KEY = "s3cret-key";

  @Test
  void aRoleIsBoundOnlyWithBothEndpointAndModel() {
    assertThat(properties("http://localhost:8081/v1", "m").bound()).isTrue();
    assertThat(properties("http://localhost:8081/v1", "").bound()).isFalse();
    assertThat(properties("", "m").bound()).isFalse();
    assertThat(properties("", "").bound()).isFalse();
  }

  /** Whitespace-only values are absent values, not configuration. */
  @Test
  void blankValuesAreNormalizedAway() {
    RerankProperties properties = properties("   ", "  ");

    assertThat(properties.baseUrl()).isEmpty();
    assertThat(properties.model()).isEmpty();
    assertThat(properties.bound()).isFalse();
  }

  @Test
  void aMissingOrNonPositiveTimeoutFallsBackToTheCpuTunedDefault() {
    Duration expected = Duration.ofSeconds(RerankProperties.DEFAULT_TIMEOUT_SECONDS);
    assertThat(new RerankProperties(true, "u", "m", "", null).timeout()).isEqualTo(expected);
    assertThat(new RerankProperties(true, "u", "m", "", Duration.ZERO).timeout())
        .isEqualTo(expected);
    assertThat(new RerankProperties(true, "u", "m", "", Duration.ofSeconds(-5)).timeout())
        .isEqualTo(expected);
  }

  /**
   * A record's generated {@code toString} would print the key verbatim - the shortest path from an
   * accidental log statement to a key in a log file.
   */
  @Test
  void neitherTheDescriptionNorTheStringFormCarriesTheKey() {
    RerankProperties properties = properties("http://localhost:8081/v1", "bge-reranker");

    assertThat(properties.toString()).doesNotContain(KEY);
    assertThat(properties.describeWithoutSecrets()).doesNotContain(KEY);
    assertThat(properties.describeWithoutSecrets()).contains("bge-reranker");
  }

  /**
   * A base address carrying userinfo is credentials, and both of these forms are meant to be safe
   * to log - so neither may reproduce it, not even the host part around it.
   */
  @Test
  void neitherTheDescriptionNorTheStringFormCarriesCredentialsFromTheBaseUrl() {
    RerankProperties properties =
        properties("https://benutzer:geheim@reranker.example.internal/v1", "bge-reranker");

    assertThat(properties.describeWithoutSecrets()).doesNotContain("benutzer:geheim");
    assertThat(properties.toString()).doesNotContain("benutzer:geheim");
  }

  @Test
  void anUnboundRoleDescribesItselfWithoutPretendingToHaveAnEndpoint() {
    assertThat(properties("", "").describeWithoutSecrets()).isEqualTo("(keine Endpunktangaben)");
  }

  private static RerankProperties properties(String baseUrl, String model) {
    return new RerankProperties(true, baseUrl, model, KEY, Duration.ofSeconds(5));
  }
}
