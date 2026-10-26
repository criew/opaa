package io.opaa.llm;

/**
 * The rerank model role as the administration stored it (ADR-0060, Entscheidung 10): the switch
 * states the intent, address and model the how, the optional key is in plain text. {@link
 * #toString()} never names the key or userinfo of the address.
 *
 * <p><b>{@link #enabled} is deliberately separate from the endpoint fields</b>: reranking being off
 * must be a statement, never the indistinguishable consequence of a missing address
 * (docs/features/hybrid-retrieval.md, "'Aus' muss eine Aussage sein, kein Zustand").
 */
public record RerankEndpoint(boolean enabled, String baseUrl, String model, String apiKey) {

  /** The factory default: off and unbound. */
  public static final RerankEndpoint FACTORY_DEFAULT = new RerankEndpoint(false, "", "", "");

  public RerankEndpoint {
    baseUrl = baseUrl == null ? "" : baseUrl.strip();
    model = model == null ? "" : model.strip();
    apiKey = apiKey == null ? "" : apiKey.strip();
  }

  /** Whether both endpoint fields are present - the role is bound to a model. */
  public boolean bound() {
    return !baseUrl.isEmpty() && !model.isEmpty();
  }

  /** Endpoint and model as far as they may be logged or stored: never the key. */
  public String describeWithoutSecrets() {
    if (!bound()) {
      return "(keine Endpunktangaben)";
    }
    return model + " @ " + safeBaseUrl();
  }

  /** The base address as far as it may be shown; one carrying userinfo is replaced. */
  String safeBaseUrl() {
    return ModelEndpointUri.containsCredentials(baseUrl)
        ? "(Basis-Adresse mit Anmeldedaten - abgelehnt)"
        : baseUrl;
  }

  @Override
  public String toString() {
    return "RerankEndpoint[enabled="
        + enabled
        + ", baseUrl="
        + safeBaseUrl()
        + ", model="
        + model
        + ", apiKey=***]";
  }
}
