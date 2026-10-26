package io.opaa.llm;

import java.util.Optional;

/**
 * The embedding identity the vector index records ({@code embedding_index}), a port implemented
 * where the index lives. Read by the self-installation of the {@code dev} mode, which on an
 * existing table only installs the model the stored vectors were built with or are being
 * re-embedded to.
 */
public interface RecordedEmbeddingIndex {

  /**
   * @param model the model the embedding column was built with; {@code null} when unknown
   * @param targetModel the model of a running re-embedding; {@code null} when the index is current
   */
  record Recorded(String model, String targetModel) {}

  /** The recorded identity, empty when no start recorded one. */
  Optional<Recorded> recorded();
}
