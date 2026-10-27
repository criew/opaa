package io.opaa.indexing.chunk;

/**
 * A vector embedded under a generation that no column of the index carries any more: a model
 * change, a discard or a switch came between embedding and writing (ADR-0061, Entscheidung 6).
 * Nothing was written; the writer embeds again with the current client.
 */
public class EmbeddingGenerationChangedException extends RuntimeException {

  public EmbeddingGenerationChangedException(long generation) {
    super("No column of the vector index carries embedding generation " + generation);
  }
}
