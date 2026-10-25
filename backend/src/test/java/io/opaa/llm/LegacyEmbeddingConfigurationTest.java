package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The configuration an older installation ran with: the takeover keys first, then the former {@code
 * spring.ai.openai.embedding.*} binding, then the shared address and key, then the former defaults;
 * the shipped placeholder key counts as none.
 */
class LegacyEmbeddingConfigurationTest {

  @Test
  void withoutAnyValueTheFormerDefaultsApply() {
    LegacyEmbeddingConfiguration legacy = new LegacyEmbeddingConfiguration(new MockEnvironment());

    assertThat(legacy.endpoint())
        .isEqualTo(new EmbeddingEndpoint("http://localhost:11434/v1", "bge-m3", null, 2048));
    assertThat(legacy.dimensions()).isEqualTo(1024);
  }

  @Test
  void theTakeoverKeysComeBeforeTheFormerBindingAndTheSharedValues() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(LegacyEmbeddingConfiguration.BASE_URL, "http://eigen:1/v1")
            .withProperty(LegacyEmbeddingConfiguration.BOUND_BASE_URL, "http://gebunden:2/v1")
            .withProperty(LegacyEmbeddingConfiguration.SHARED_BASE_URL, "http://gemeinsam:3/v1")
            .withProperty(LegacyEmbeddingConfiguration.BOUND_MODEL, "nomic-embed-text")
            .withProperty(LegacyEmbeddingConfiguration.SHARED_API_KEY, "sk-gemeinsam")
            .withProperty(LegacyEmbeddingConfiguration.MAX_INPUT_TOKENS, "8192")
            .withProperty(LegacyEmbeddingConfiguration.BOUND_DIMENSIONS, "768");

    LegacyEmbeddingConfiguration legacy = new LegacyEmbeddingConfiguration(environment);

    assertThat(legacy.endpoint())
        .isEqualTo(
            new EmbeddingEndpoint("http://eigen:1/v1", "nomic-embed-text", "sk-gemeinsam", 8192));
    assertThat(legacy.dimensions()).isEqualTo(768);
  }

  @Test
  void theShippedPlaceholderKeyIsNoKey() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(
                LegacyEmbeddingConfiguration.SHARED_API_KEY,
                LegacyEmbeddingConfiguration.PLACEHOLDER_API_KEY);

    assertThat(new LegacyEmbeddingConfiguration(environment).endpoint().apiKey()).isNull();
  }

  @Test
  void aKeyIsTakenOverWithoutSurroundingWhitespace() {
    MockEnvironment environment =
        new MockEnvironment().withProperty(LegacyEmbeddingConfiguration.API_KEY, " sk-geheim\n");

    assertThat(new LegacyEmbeddingConfiguration(environment).endpoint().apiKey())
        .isEqualTo("sk-geheim");
  }
}
