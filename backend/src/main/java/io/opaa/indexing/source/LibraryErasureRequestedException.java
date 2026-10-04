package io.opaa.indexing.source;

import io.opaa.indexing.job.EndsRun;

/**
 * The library of a running run is being erased: the run ends at its next access to the secret, past
 * every item catch ({@link EndsRun}), and writes nothing of the source any more.
 */
public class LibraryErasureRequestedException extends RuntimeException implements EndsRun {

  static final String MESSAGE = "Die Bibliothek wird gelöscht.";

  public LibraryErasureRequestedException() {
    super(MESSAGE);
  }
}
