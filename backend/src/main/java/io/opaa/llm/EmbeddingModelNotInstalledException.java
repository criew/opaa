package io.opaa.llm;

import io.opaa.common.ServiceUnavailableException;

/**
 * Thrown by every use of the embedding model before one is installed, so indexing, search and chat
 * are refused with {@code 503} instead of reaching a vector table that does not exist yet. The
 * message tells a pending installation from a completed one without a model.
 */
public class EmbeddingModelNotInstalledException extends ServiceUnavailableException {

  private EmbeddingModelNotInstalledException(String message) {
    super(message);
  }

  /** The installation is still pending; its step chooses the model. */
  static EmbeddingModelNotInstalledException pending() {
    return new EmbeddingModelNotInstalledException(
        "Das Embedding-Modell ist noch nicht eingerichtet. Bitte schließen Sie zuerst die"
            + " Installation ab.");
  }

  /** The installation is completed but has no model; the start-up log names the remedy. */
  static EmbeddingModelNotInstalledException afterInstallation() {
    return new EmbeddingModelNotInstalledException(
        "Diese Installation hat kein Embedding-Modell; Indexierung und Suche sind deshalb"
            + " gesperrt. Ursache und Abhilfe nennt das Protokoll des Starts.");
  }
}
