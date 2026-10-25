package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.llm.EmbeddingInfoService;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OpaaPropertyVariantIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Verifies that the application context loads when chat and embedding point at different
 * openai-compatible base URLs. Chat is the one autoconfigured provider ({@code openai}); the
 * embedding model is no autoconfiguration but the installed one, which a database without one takes
 * over at start-up from the former {@code spring.ai.openai.embedding.*} binding.
 *
 * <p>The embedding base URL is stated explicitly by this class's signature, different from the
 * canonical signature's, to prove the takeover reads it. The address is never called - the
 * embedding model is a fake.
 */
@OpaaPropertyVariantIntegrationTest
class MixedProviderConfigurationTest {

  @Autowired private Environment environment;
  @Autowired private EmbeddingInfoService embeddingInfo;

  @Test
  void contextLoadsWithDifferentChatAndEmbeddingBaseUrls() {
    assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("openai");
    assertThat(environment.getProperty("spring.ai.model.embedding")).isEqualTo("none");
    assertThat(environment.getProperty("spring.ai.openai.chat.base-url"))
        .isEqualTo(OpaaIntegrationTest.CHAT_BASE_URL);
    assertThat(embeddingInfo.getEmbeddingInfo().baseUrl())
        .isEqualTo("http://model-server.invalid:8000/v1");
  }
}
