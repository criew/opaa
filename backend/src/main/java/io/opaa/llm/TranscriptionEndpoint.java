package io.opaa.llm;

/**
 * The transcription model role as the administration stored it (ADR-0060, Entscheidung 10), built
 * like {@link RerankEndpoint}, with the language passed to the model: an ISO-639-1 code, empty to
 * let the model detect it. {@link #toString()} never names the key or userinfo of the address.
 */
public record TranscriptionEndpoint(
    boolean enabled, String baseUrl, String model, String apiKey, String language) {

  /** The language of the factory default. */
  public static final String DEFAULT_LANGUAGE = "de";

  /** The factory default: off, unbound, German. */
  public static final TranscriptionEndpoint FACTORY_DEFAULT =
      new TranscriptionEndpoint(false, "", "", "", DEFAULT_LANGUAGE);

  public TranscriptionEndpoint {
    baseUrl = baseUrl == null ? "" : baseUrl.strip();
    model = model == null ? "" : model.strip();
    apiKey = apiKey == null ? "" : apiKey.strip();
    language = language == null ? "" : language.strip();
  }

  /** Whether both endpoint fields are present. */
  public boolean bound() {
    return !baseUrl.isEmpty() && !model.isEmpty();
  }

  /** Endpoint and model as far as they may be logged: never the key, never userinfo. */
  public String describeWithoutSecrets() {
    if (!bound()) {
      return "(no endpoint configured)";
    }
    return model + " @ " + safeBaseUrl();
  }

  String safeBaseUrl() {
    return ModelEndpointUri.containsCredentials(baseUrl)
        ? "(base URL with credentials - rejected)"
        : baseUrl;
  }

  @Override
  public String toString() {
    return "TranscriptionEndpoint[enabled="
        + enabled
        + ", baseUrl="
        + safeBaseUrl()
        + ", model="
        + model
        + ", apiKey=***, language="
        + language
        + "]";
  }
}
