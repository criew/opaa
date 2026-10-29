package io.opaa.common;

/**
 * Which {@link Error}s a caller must not book as the failure of a single unit of work (one
 * document, one request) and pass on instead: a {@link VirtualMachineError} other than {@link
 * StackOverflowError} ({@link OutOfMemoryError} above all), after which the process itself is no
 * longer trustworthy, and a {@link LinkageError}, which means a broken deployment and would fail
 * every unit alike. A {@link StackOverflowError} unwinds cleanly and is caused by the one input.
 */
public final class FatalErrors {

  private FatalErrors() {}

  public static boolean isFatal(Throwable thrown) {
    return isProcessFatal(thrown) || thrown instanceof LinkageError;
  }

  /**
   * Whether {@code thrown} leaves the process untrustworthy, so that not even the unit's own state
   * may be written any more; a {@link LinkageError} is fatal but still lets that state be written.
   */
  public static boolean isProcessFatal(Throwable thrown) {
    return thrown instanceof VirtualMachineError && !(thrown instanceof StackOverflowError);
  }

  /** Rethrows {@code thrown} when it {@link #isFatal is fatal}; returns otherwise. */
  public static void rethrowIfFatal(Throwable thrown) {
    if (isFatal(thrown)) {
      throw (Error) thrown;
    }
  }

  /**
   * Rethrows {@code thrown} when it {@link #isProcessFatal is process-fatal}; returns otherwise.
   */
  public static void rethrowIfProcessFatal(Throwable thrown) {
    if (isProcessFatal(thrown)) {
      throw (Error) thrown;
    }
  }
}
