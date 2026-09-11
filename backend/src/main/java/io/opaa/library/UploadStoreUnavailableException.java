package io.opaa.library;

import io.opaa.common.ServiceUnavailableException;

/**
 * The storage of uploaded originals cannot be reached or refuses the application right now
 * (ADR-0030, Entscheidung 9) - a temporary condition the caller must not mistake for "this original
 * does not exist", so it is a {@code 503} with a German message, never the {@code 404} an
 * unresolvable original answers with. The message carries no detail of the failure; that goes to
 * the log.
 */
public class UploadStoreUnavailableException extends ServiceUnavailableException {

  static final String MESSAGE =
      "Die Ablage der Originaldokumente ist derzeit nicht erreichbar. Bitte später erneut"
          + " versuchen.";

  public UploadStoreUnavailableException() {
    super(MESSAGE);
  }
}
