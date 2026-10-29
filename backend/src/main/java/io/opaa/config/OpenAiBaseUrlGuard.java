package io.opaa.config;

import io.opaa.llm.ModelEndpointUri;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Refuses to start the {@code dev} mode when the base URL of its chat model is blank or carries
 * credentials. Only that mode reads the address, for the chat model it installs itself with; every
 * other mode gets its models from the installation, so the guard is not even registered there. The
 * embedding model's address is checked by the probe embedding of {@code
 * io.opaa.llm.EmbeddingModelService}.
 *
 * <p>{@code ${VAR:default}} only falls back to its default when {@code VAR} is unset: an {@code
 * OPAA_OPENAI_BASE_URL} set but empty overrides the local default with an empty string, which would
 * otherwise fail deep inside the OpenAI client with a far less obvious error.
 */
@Configuration
@Profile("dev")
public class OpenAiBaseUrlGuard {

  static final String OPENAI_PROVIDER = "openai";

  private final Environment environment;

  public OpenAiBaseUrlGuard(Environment environment) {
    this.environment = environment;
  }

  @PostConstruct
  void rejectMissingBaseUrl() {
    check("chat", "spring.ai.openai.base-url", null);
  }

  /** {@code specificVariable} {@code null}: the function reads the shared address only. */
  private void check(String function, String baseUrlProperty, String specificVariable) {
    String provider = environment.getProperty("spring.ai.model." + function);
    if (!OPENAI_PROVIDER.equalsIgnoreCase(provider)) {
      return;
    }
    String baseUrl = environment.getProperty(baseUrlProperty);
    if (ModelEndpointUri.containsCredentials(baseUrl)) {
      // The address reaches log files on its own - Spring's ResourceAccessException carries the
      // target URI in its message, and io.opaa.indexing.document.DocumentIngestService logs the
      // whole stack trace of a failed embedding call. The address itself is never named here.
      throw new IllegalStateException(
          """
          The base URL configured for the %s function carries credentials \
          (https://user:secret@host/v1). Configure the address without them and supply the access \
          key via %s instead - a base URL is written to log files and status output and is not \
          treated as a secret anywhere. See docs/handbuch/betrieb/modelle.md."""
              .formatted(
                  function,
                  specificVariable == null
                      ? "OPAA_OPENAI_API_KEY"
                      : "OPAA_OPENAI_API_KEY or "
                          + specificVariable.replace("_BASE_URL", "_API_KEY")));
    }
    if (StringUtils.hasText(baseUrl)) {
      return;
    }
    throw new IllegalStateException(
        """
        No base URL is configured for the %s function. "openai" is the name of the API protocol, \
        not of a target: locally operated model servers speak it too, and application.yml's own \
        default already points at one (Ollama). This means the configured value is explicitly \
        blank rather than missing - check %s for an empty override. See \
        docs/handbuch/betrieb/modelle.md."""
            .formatted(
                function,
                specificVariable == null
                    ? "OPAA_OPENAI_BASE_URL"
                    : "OPAA_OPENAI_BASE_URL (shared) and %s (applies to %s only)"
                        .formatted(specificVariable, function)));
  }
}
