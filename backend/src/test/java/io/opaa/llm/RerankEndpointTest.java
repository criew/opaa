package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The binding rules of the stored rerank endpoint, and the one rule about its access key. */
class RerankEndpointTest {

  private static final String KEY = "s3cret-key";

  @Test
  void aRoleIsBoundOnlyWithBothEndpointAndModel() {
    assertThat(endpoint("http://localhost:8081/v1", "m").bound()).isTrue();
    assertThat(endpoint("http://localhost:8081/v1", "").bound()).isFalse();
    assertThat(endpoint("", "m").bound()).isFalse();
    assertThat(endpoint(null, null).bound()).isFalse();
  }

  /** Whitespace-only values are absent values, not configuration. */
  @Test
  void blankValuesAreNormalizedAway() {
    RerankEndpoint endpoint = endpoint("   ", "  ");

    assertThat(endpoint.baseUrl()).isEmpty();
    assertThat(endpoint.model()).isEmpty();
    assertThat(endpoint.bound()).isFalse();
  }

  @Test
  void theFactoryDefaultIsOffAndUnbound() {
    assertThat(RerankEndpoint.FACTORY_DEFAULT.enabled()).isFalse();
    assertThat(RerankEndpoint.FACTORY_DEFAULT.bound()).isFalse();
  }

  /** A record's generated {@code toString} would print the key verbatim. */
  @Test
  void neitherTheDescriptionNorTheStringFormCarriesTheKey() {
    RerankEndpoint endpoint = endpoint("http://localhost:8081/v1", "bge-reranker");

    assertThat(endpoint.toString()).doesNotContain(KEY);
    assertThat(endpoint.describeWithoutSecrets()).doesNotContain(KEY).contains("bge-reranker");
  }

  @Test
  void neitherTheDescriptionNorTheStringFormCarriesCredentialsFromTheBaseUrl() {
    RerankEndpoint endpoint =
        endpoint("https://benutzer:geheim@reranker.example.internal/v1", "bge-reranker");

    assertThat(endpoint.describeWithoutSecrets()).doesNotContain("benutzer:geheim");
    assertThat(endpoint.toString()).doesNotContain("benutzer:geheim");
  }

  @Test
  void anUnboundRoleDescribesItselfWithoutPretendingToHaveAnEndpoint() {
    assertThat(endpoint("", "").describeWithoutSecrets()).isEqualTo("(keine Endpunktangaben)");
  }

  private static RerankEndpoint endpoint(String baseUrl, String model) {
    return new RerankEndpoint(true, baseUrl, model, KEY);
  }
}
