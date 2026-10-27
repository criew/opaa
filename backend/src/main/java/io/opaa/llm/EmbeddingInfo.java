package io.opaa.llm;

/**
 * The installed embedding model - provider, model identifier, vector size and generation - as
 * {@code embedding_model} holds it. A change goes through {@link EmbeddingModelAdministration},
 * never through this record.
 *
 * <p>{@code provider} is always {@code openai} - the OpenAI-compatible protocol is the only
 * connection path, Ollama included, via its own {@code /v1} endpoint. The field stays part of this
 * record (and the API response built from it), since a genuinely different connection path is not
 * ruled out.
 *
 * <p>{@code baseUrl} is the effective endpoint, or {@code null} when it carries credentials - it is
 * shown to administrators and must never reproduce them. {@link #NOT_INSTALLED} stands for an
 * installation without an embedding model yet: no model, no address, size {@code 0}.
 */
public record EmbeddingInfo(
    String provider, String model, int dimensions, String baseUrl, long generation) {

  public static final EmbeddingInfo NOT_INSTALLED = new EmbeddingInfo("openai", null, 0, null, 0);

  /** A model of the initial generation, as every model before a first change. */
  public EmbeddingInfo(String provider, String model, int dimensions, String baseUrl) {
    this(provider, model, dimensions, baseUrl, EmbeddingClients.INITIAL_GENERATION);
  }

  /** Whether an embedding model is installed. */
  public boolean installed() {
    return model != null;
  }
}
