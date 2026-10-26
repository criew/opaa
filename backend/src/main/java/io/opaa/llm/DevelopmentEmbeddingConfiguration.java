package io.opaa.llm;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The embedding model the {@code dev} mode installs itself with (ADR-0060, Entscheidung 7): the
 * Spring properties {@code spring.ai.openai.embedding.*} and {@code
 * spring.ai.vectorstore.pgvector.dimensions} that development, tests and the evaluation harnesses
 * set, then the shared {@code spring.ai.openai.*} address and key, then fixed defaults. No
 * environment variable binds them, and no other mode reads them: an operated installation gets its
 * embedding model from the installation.
 */
@InstallationWide
@NoDataImpact("the embedding model of the dev mode, never of an operated installation")
@Component
class DevelopmentEmbeddingConfiguration {

  static final String MODE_PROPERTY = "opaa.auth.mode";
  static final String DEV_MODE = "dev";

  static final String BASE_URL = "spring.ai.openai.embedding.base-url";
  static final String MODEL = "spring.ai.openai.embedding.model";
  static final String API_KEY = "spring.ai.openai.embedding.api-key";
  static final String DIMENSIONS = "spring.ai.vectorstore.pgvector.dimensions";

  /** The shared base address and key; {@code application.yml} resolves them for chat as well. */
  static final String SHARED_BASE_URL = "spring.ai.openai.base-url";

  static final String SHARED_API_KEY = "spring.ai.openai.api-key";

  static final String DEFAULT_MODEL = "bge-m3";
  static final String DEFAULT_BASE_URL = "http://localhost:11434/v1";
  static final int DEFAULT_DIMENSIONS = 1024;

  /** The placeholder key the configuration ships; Ollama ignores it, so it means none. */
  static final String PLACEHOLDER_API_KEY = "sk-placeholder";

  private final Environment environment;

  DevelopmentEmbeddingConfiguration(Environment environment) {
    this.environment = environment;
  }

  /** Whether this instance runs in the {@code dev} mode, the only one that installs itself. */
  boolean active() {
    return DEV_MODE.equals(environment.getProperty(MODE_PROPERTY));
  }

  /** The endpoint of the {@code dev} mode. */
  EmbeddingEndpoint endpoint() {
    String baseUrl = first(BASE_URL, SHARED_BASE_URL);
    String model = first(MODEL);
    String apiKey = first(API_KEY, SHARED_API_KEY);
    return new EmbeddingEndpoint(
        baseUrl == null ? DEFAULT_BASE_URL : baseUrl,
        model == null ? DEFAULT_MODEL : model,
        apiKey == null || PLACEHOLDER_API_KEY.equals(apiKey.strip()) ? null : apiKey.strip(),
        EmbeddingEndpoint.DEFAULT_MAX_INPUT_TOKENS);
  }

  /** The vector size an existing table of the {@code dev} mode was created with. */
  int dimensions() {
    String value = first(DIMENSIONS);
    return value == null ? DEFAULT_DIMENSIONS : Integer.parseInt(value.trim());
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
