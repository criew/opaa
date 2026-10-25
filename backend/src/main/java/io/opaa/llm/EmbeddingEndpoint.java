package io.opaa.llm;

/**
 * An OpenAI-compatible embedding endpoint as entered or carried over: address, model, the optional
 * key in plain text and the input limit in tokens. Lives only for a probe or an installation;
 * {@link #toString()} never names the key.
 */
public record EmbeddingEndpoint(
    String baseUrl, String modelIdentifier, String apiKey, int maxInputTokens) {

  /** The input limit of every Ollama model as pulled, used when none is given. */
  public static final int DEFAULT_MAX_INPUT_TOKENS = 2048;

  public EmbeddingEndpoint {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl must not be blank");
    }
    if (modelIdentifier == null || modelIdentifier.isBlank()) {
      throw new IllegalArgumentException("modelIdentifier must not be blank");
    }
    if (maxInputTokens <= 0) {
      throw new IllegalArgumentException("maxInputTokens must be positive, got " + maxInputTokens);
    }
    baseUrl = baseUrl.trim();
    modelIdentifier = modelIdentifier.trim();
    apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
  }

  @Override
  public String toString() {
    return "EmbeddingEndpoint[baseUrl="
        + ModelEndpointUri.withoutSecrets(baseUrl)
        + ", modelIdentifier="
        + modelIdentifier
        + ", apiKeySet="
        + (apiKey != null)
        + ", maxInputTokens="
        + maxInputTokens
        + "]";
  }
}
