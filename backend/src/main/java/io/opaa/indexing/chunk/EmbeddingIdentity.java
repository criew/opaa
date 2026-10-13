package io.opaa.indexing.chunk;

import java.util.Objects;

/**
 * The embedding model a vector column was built with: model name, endpoint (never with credentials,
 * {@code null} when unknown or withheld) and vector size. {@code model} is {@code null} when an
 * index predates its recorded identity and its size did not match the configuration.
 */
public record EmbeddingIdentity(String model, String baseUrl, int dimensions) {

  /**
   * Whether vectors of {@code other} are comparable with vectors of this identity: same model and
   * same size. The endpoint is not compared - the same model behind another address embeds alike.
   */
  public boolean sameModelAs(EmbeddingIdentity other) {
    return model != null && Objects.equals(model, other.model) && dimensions == other.dimensions;
  }
}
