package io.opaa.config;

import io.opaa.llm.ModelEndpointUri;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Refuses to start when the base URL for a function is blank or carries credentials.
 *
 * <p>{@code openai} is a protocol name, not a vendor name: locally operated model servers (Ollama
 * included, via its own {@code /v1} endpoint) expose the same API, and since #762 it is the only
 * connection path Spring AI wires here - {@code spring.ai.model.chat}/{@code embedding} are fixed
 * to {@code openai}, not a choice anymore. {@code application.yml} therefore does carry a default
 * base URL (the local Ollama endpoint), unlike before #762: the default itself already stays inside
 * the organisation, so inheriting it is safe. This guard now protects a narrower, still-real
 * failure mode instead: an operator who sets {@code OPAA_OPENAI_BASE_URL} (or one of the
 * per-function variables) to an explicitly blank value overrides that default with an empty string
 * - {@code ${VAR:default}} only falls back to {@code default} when {@code VAR} is entirely unset,
 * not when it is set and empty - and a blank base URL would otherwise fail deep inside the OpenAI
 * client with a far less obvious error.
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
    String baseUrl = environment.getProperty(baseUrlProperty);
    if (ModelEndpointUri.containsCredentials(baseUrl)) {
      // #1147: the address reaches log files on its own - Spring's ResourceAccessException carries
      // the target URI in its message, and io.opaa.indexing.FileProcessingService logs the whole
      // stack trace of a failed embedding call. The address itself is never named here.
      throw new IllegalStateException(
          """
          The base URL configured for the %s function carries credentials \
          (https://user:secret@host/v1). Configure the address without them and supply the access \
          key via OPAA_OPENAI_API_KEY or %s instead - a base URL is written to log files and \
          status output and is not treated as a secret anywhere. \
          See docs/handbuch/deployment.md."""
              .formatted(function, specificVariable.replace("_BASE_URL", "_API_KEY")));
    }
    if (StringUtils.hasText(baseUrl)) {
      return;
    }
    throw new IllegalStateException(
        """
        No base URL is configured for the %s function. "openai" is the name of the API protocol, \
        not of a target: locally operated model servers speak it too, and application.yml's own \
        default already points at one (Ollama). This means the configured value is explicitly \
        blank rather than missing - check OPAA_OPENAI_BASE_URL (applies to chat and embedding) and \
        %s (applies to %s only) for an empty override. See docs/handbuch/deployment.md."""
            .formatted(function, specificVariable, function));
  }
}
