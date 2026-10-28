package io.opaa.llm;

import io.opaa.common.ServiceUnavailableException;

/**
 * Thrown by every use of the embedding model before one is installed, so indexing, upload and
 * search are refused with {@code 503} and {@link #CODE} instead of reaching a vector table that
 * does not exist yet. A regular state, not an outage: an installation may skip its embedding model
 * and set it later in the model administration. The message tells a pending installation from that.
 */
public class EmbeddingModelNotInstalledException extends ServiceUnavailableException {

  /** The {@code code} of the response; the chat and the library pages show their notice for it. */
  public static final String CODE = "EMBEDDING_MODEL_NOT_CONFIGURED";

  private EmbeddingModelNotInstalledException(String message) {
    super(message, CODE);
  }

  /** The installation is still pending; its step chooses the model. */
  static EmbeddingModelNotInstalledException pending() {
    return new EmbeddingModelNotInstalledException(
        "Das Embedding-Modell ist noch nicht eingerichtet. Bitte schließen Sie zuerst die"
            + " Installation ab.");
  }

  /** The installation is completed without an embedding model; the administration sets one. */
  static EmbeddingModelNotInstalledException afterInstallation() {
    return new EmbeddingModelNotInstalledException(
        "Es ist noch kein Embedding-Modell eingerichtet; Indexierung, Upload und Suche stehen erst"
            + " danach bereit. Die Systemverwaltung legt es unter Modelle → Einbettung fest.");
  }
}
