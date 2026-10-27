package io.opaa.llm;

import io.opaa.compliance.DataFlowCarrier;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * The embedding client together with the generation of the model behind it (ADR-0061, Entscheidung
 * 2). A caller pins one client for a whole write or search: every vector it gets from {@link
 * Pinned#client()} belongs to {@link Pinned#generation()}, and it hands that generation to the
 * check that decides which column the vector may go to.
 */
@DataFlowCarrier
public interface EmbeddingClients {

  /** The generation of a fresh installation and of every model before generations existed. */
  long INITIAL_GENERATION = 1;

  /**
   * A client bound to one model row as it was read.
   *
   * @param maxInputTokens the input limit of that row's endpoint
   */
  record Pinned(EmbeddingModel client, long generation, int maxInputTokens) {}

  /**
   * The client of the installed model.
   *
   * @throws EmbeddingModelNotInstalledException before an embedding model is installed
   */
  Pinned pin();

  /** A fixed client under {@link #INITIAL_GENERATION}, for a caller without an installed model. */
  static EmbeddingClients fixed(EmbeddingModel client, int maxInputTokens) {
    Pinned pinned = new Pinned(client, INITIAL_GENERATION, maxInputTokens);
    return () -> pinned;
  }
}
