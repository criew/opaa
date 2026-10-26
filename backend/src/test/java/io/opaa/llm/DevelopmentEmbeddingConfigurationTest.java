package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The embedding model of the {@code dev} mode: its own Spring properties first, then the shared
 * address and key, then the defaults; the shipped placeholder key counts as none, and only the
 * {@code dev} mode is active.
 */
class DevelopmentEmbeddingConfigurationTest {

  @Test
  void withoutAnyValueTheDefaultsApply() {
    DevelopmentEmbeddingConfiguration development =
        new DevelopmentEmbeddingConfiguration(new MockEnvironment());

    assertThat(development.endpoint())
        .isEqualTo(new EmbeddingEndpoint("http://localhost:11434/v1", "bge-m3", null, 2048));
    assertThat(development.dimensions()).isEqualTo(1024);
  }

  @Test
  void itsOwnPropertiesComeBeforeTheSharedValues() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(DevelopmentEmbeddingConfiguration.BASE_URL, "http://eigen:1/v1")
            .withProperty(
                DevelopmentEmbeddingConfiguration.SHARED_BASE_URL, "http://gemeinsam:3/v1")
            .withProperty(DevelopmentEmbeddingConfiguration.MODEL, "nomic-embed-text")
            .withProperty(DevelopmentEmbeddingConfiguration.SHARED_API_KEY, "sk-gemeinsam")
            .withProperty(DevelopmentEmbeddingConfiguration.DIMENSIONS, "768");

    DevelopmentEmbeddingConfiguration development =
        new DevelopmentEmbeddingConfiguration(environment);

    assertThat(development.endpoint())
        .isEqualTo(
            new EmbeddingEndpoint("http://eigen:1/v1", "nomic-embed-text", "sk-gemeinsam", 2048));
    assertThat(development.dimensions()).isEqualTo(768);
  }

  @Test
  void theShippedPlaceholderKeyIsNoKey() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(
                DevelopmentEmbeddingConfiguration.SHARED_API_KEY,
                DevelopmentEmbeddingConfiguration.PLACEHOLDER_API_KEY);

    assertThat(new DevelopmentEmbeddingConfiguration(environment).endpoint().apiKey()).isNull();
  }

  @Test
  void onlyTheDevModeIsActive() {
    assertThat(
            new DevelopmentEmbeddingConfiguration(
                    new MockEnvironment()
                        .withProperty(DevelopmentEmbeddingConfiguration.MODE_PROPERTY, "dev"))
                .active())
        .isTrue();
    assertThat(
            new DevelopmentEmbeddingConfiguration(
                    new MockEnvironment()
                        .withProperty(DevelopmentEmbeddingConfiguration.MODE_PROPERTY, "oidc"))
                .active())
        .isFalse();
    assertThat(new DevelopmentEmbeddingConfiguration(new MockEnvironment()).active()).isFalse();
  }
}
