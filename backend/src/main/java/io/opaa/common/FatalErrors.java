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
    return (thrown instanceof VirtualMachineError && !(thrown instanceof StackOverflowError))
        || thrown instanceof LinkageError;
  }

  /** Rethrows {@code thrown} when it {@link #isFatal is fatal}; returns otherwise. */
  public static void rethrowIfFatal(Throwable thrown) {
    if (isFatal(thrown)) {
      throw (Error) thrown;
    }
  }
}
