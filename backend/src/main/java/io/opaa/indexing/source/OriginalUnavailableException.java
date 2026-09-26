package io.opaa.indexing.source;

/**
 * The storage behind an original cannot be reached right now - a temporary condition the caller
 * answers with 503, never with "does not exist". The message is for the log only; it may name
 * configuration a reader of the document does not see.
 */
public class OriginalUnavailableException extends RuntimeException {

  public OriginalUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
