package io.opaa.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Refuses to start when an OpenAI-compatible provider is selected without a base URL.
 *
 * <p>{@code openai} is a protocol name, not a vendor name: locally operated model servers expose
 * the same API. The configuration therefore no longer carries a default base URL, because
 * inheriting one would silently point an installation that meant to stay local at a target outside
 * the organisation. Whoever selects the provider states the address.
 *
 * <p>The check only applies to the function that actually selected the provider. Ollama — the
 * default for both chat and embedding — starts without any additional configuration.
 */
@Configuration
public class OpenAiBaseUrlGuard {

  static final String OPENAI_PROVIDER = "openai";

  private final Environment environment;

  public OpenAiBaseUrlGuard(Environment environment) {
    this.environment = environment;
  }

  @PostConstruct
  void rejectMissingBaseUrl() {
    check("chat", "spring.ai.openai.chat.base-url", "OPAA_OPENAI_CHAT_BASE_URL");
    check("embedding", "spring.ai.openai.embedding.base-url", "OPAA_OPENAI_EMBEDDING_BASE_URL");
  }

  private void check(String function, String baseUrlProperty, String specificVariable) {
    String provider = environment.getProperty("spring.ai.model." + function);
    if (!OPENAI_PROVIDER.equalsIgnoreCase(provider)) {
      return;
    }
    if (StringUtils.hasText(environment.getProperty(baseUrlProperty))) {
      return;
    }
    throw new IllegalStateException(
        """
        The %s provider is set to "openai" but no base URL is configured. "openai" is the name of \
        the API protocol, not of a target: locally operated model servers speak it too, so there \
        is no default address. Set OPAA_OPENAI_BASE_URL (applies to chat and embedding) or %s \
        (applies to %s only). See docs/deployment.md."""
            .formatted(function, specificVariable, function));
  }
}
