package io.opaa.llm;

import io.opaa.common.ServiceUnavailableException;

/**
 * Thrown by every use of the embedding model before one is installed, so indexing, search and chat
 * are refused with {@code 503} instead of reaching a vector table that does not exist yet.
 */
public class EmbeddingModelNotInstalledException extends ServiceUnavailableException {

  EmbeddingModelNotInstalledException() {
    super(
        "Das Embedding-Modell ist noch nicht eingerichtet. Bitte schließen Sie zuerst die"
            + " Installation ab.");
  }
}
