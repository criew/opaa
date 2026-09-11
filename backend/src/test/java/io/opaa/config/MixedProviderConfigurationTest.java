package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaPropertyVariantIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Verifies that the application context loads when chat and embedding point at different
 * openai-compatible base URLs - since #762 there is only one provider ({@code openai}), but chat
 * and embedding remain independently configurable functions (different endpoints, different
 * models).
 *
 * <p>The embedding base URL is stated explicitly by this class's signature even though {@code
 * application.yml} already defaults it to a local Ollama endpoint, to prove the override actually
 * takes effect. The address is never called - the embedding model is a fake.
 */
@OpaaPropertyVariantIntegrationTest
class MixedProviderConfigurationTest {

  @Autowired private Environment environment;

  @Test
  void contextLoadsWithDifferentChatAndEmbeddingBaseUrls() {
    assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("openai");
    assertThat(environment.getProperty("spring.ai.model.embedding")).isEqualTo("openai");
    assertThat(environment.getProperty("spring.ai.openai.chat.base-url"))
        .isEqualTo("http://localhost:11434/v1");
    assertThat(environment.getProperty("spring.ai.openai.embedding.base-url"))
        .isEqualTo("http://model-server.invalid:8000/v1");
  }
}
