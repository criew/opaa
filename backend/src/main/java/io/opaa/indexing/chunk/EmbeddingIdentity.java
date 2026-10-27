package io.opaa.indexing.chunk;

import java.util.Objects;

/**
 * The embedding model a vector column was built with: model name, endpoint (never with credentials,
 * {@code null} when unknown or withheld), vector size and generation. {@code model} is {@code null}
 * when an index predates its recorded identity and its size did not match the configuration.
 */
public record EmbeddingIdentity(String model, String baseUrl, int dimensions, long generation) {

  /**
   * Whether vectors of {@code other} are comparable with vectors of this identity: same model, same
   * size and same generation (ADR-0061, Entscheidung 2). The endpoint is not compared - the same
   * model behind another address embeds alike.
   */
  public boolean sameModelAs(EmbeddingIdentity other) {
    return model != null
        && Objects.equals(model, other.model)
        && dimensions == other.dimensions
        && generation == other.generation;
  }

  public EmbeddingIdentity withGeneration(long replacement) {
    return new EmbeddingIdentity(model, baseUrl, dimensions, replacement);
  }

  public EmbeddingIdentity withBaseUrl(String replacement) {
    return new EmbeddingIdentity(model, replacement, dimensions, generation);
  }
}
