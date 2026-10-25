package io.opaa.llm;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The embedding model an installation from before ADR-0060 ran with, read from {@code
 * opaa.embedding.takeover.*} (the variables {@code OPAA_OPENAI_EMBEDDING_*} and {@code
 * OPAA_PGVECTOR_DIMENSIONS}), then the former bindings {@code spring.ai.openai.embedding.*} and
 * {@code spring.ai.vectorstore.pgvector.dimensions}, then the shared {@code spring.ai.openai.*}
 * address and key, then the former defaults. Read only by the one-time takeover into {@code
 * embedding_model}; once that row exists, none of them has any effect.
 *
 * <p><b>Ablaufdatum:</b> einmalige Übernahme für Bestandsinstallationen, entfällt, sobald jede
 * Installation über Assistent oder Antwortdatei eingerichtet wird.
 */
@InstallationWide
@NoDataImpact("read once to take over an installation from before ADR-0060")
@Component
class LegacyEmbeddingConfiguration {

  static final String BASE_URL = "opaa.embedding.takeover.base-url";
  static final String MODEL = "opaa.embedding.takeover.model";
  static final String API_KEY = "opaa.embedding.takeover.api-key";
  static final String MAX_INPUT_TOKENS = "opaa.embedding.takeover.max-input-tokens";
  static final String DIMENSIONS = "opaa.embedding.takeover.dimensions";

  /** The properties the variables were bound to before ADR-0060; set directly, they still count. */
  static final String BOUND_BASE_URL = "spring.ai.openai.embedding.base-url";

  static final String BOUND_MODEL = "spring.ai.openai.embedding.model";
  static final String BOUND_API_KEY = "spring.ai.openai.embedding.api-key";
  static final String BOUND_DIMENSIONS = "spring.ai.vectorstore.pgvector.dimensions";

  /** The shared base address and key; {@code application.yml} still resolves them for chat. */
  static final String SHARED_BASE_URL = "spring.ai.openai.base-url";

  static final String SHARED_API_KEY = "spring.ai.openai.api-key";

  static final String DEFAULT_MODEL = "bge-m3";
  static final String DEFAULT_BASE_URL = "http://localhost:11434/v1";
  static final int DEFAULT_DIMENSIONS = 1024;

  /** The placeholder key the configuration shipped; Ollama ignores it, so it means none. */
  static final String PLACEHOLDER_API_KEY = "sk-placeholder";

  private final Environment environment;

  LegacyEmbeddingConfiguration(Environment environment) {
    this.environment = environment;
  }

  /** The endpoint the installation was configured with. */
  EmbeddingEndpoint endpoint() {
    String baseUrl = first(BASE_URL, BOUND_BASE_URL, SHARED_BASE_URL);
    String model = first(MODEL, BOUND_MODEL);
    String apiKey = first(API_KEY, BOUND_API_KEY, SHARED_API_KEY);
    return new EmbeddingEndpoint(
        baseUrl == null ? DEFAULT_BASE_URL : baseUrl,
        model == null ? DEFAULT_MODEL : model,
        PLACEHOLDER_API_KEY.equals(apiKey) ? null : apiKey,
        number(EmbeddingEndpoint.DEFAULT_MAX_INPUT_TOKENS, MAX_INPUT_TOKENS));
  }

  /** The vector size the installation was configured with. */
  int dimensions() {
    return number(DEFAULT_DIMENSIONS, DIMENSIONS, BOUND_DIMENSIONS);
  }

  private int number(int fallback, String... keys) {
    String value = first(keys);
    return value == null ? fallback : Integer.parseInt(value.trim());
  }

  /** The first of {@code keys} with a value, or {@code null}. */
  private String first(String... keys) {
    for (String key : keys) {
      String value = environment.getProperty(key);
      if (StringUtils.hasText(value)) {
        return value;
      }
    }
    return null;
  }
}
