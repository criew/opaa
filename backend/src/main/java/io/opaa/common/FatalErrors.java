package io.opaa.common;

/**
 * Which {@link Error}s a caller must not book as the failure of a single unit of work (one
 * document, one request) and pass on instead: a {@link VirtualMachineError} other than {@link
 * StackOverflowError} ({@link OutOfMemoryError} above all), after which the process itself is no
 * longer trustworthy, and a {@link LinkageError}, which means a broken deployment and would fail
 * every unit alike. A {@link StackOverflowError} unwinds cleanly and is caused by the one input, as
 * is the {@link #isMissingOptionalCodec missing class of an optional codec}.
 */
public final class FatalErrors {

  /** zstd-jni, which commons-compress loads only for an entry that declares zstd compression. */
  private static final String ZSTD_CODEC_PACKAGE = "com/github/luben/zstd/";

  private FatalErrors() {}

  public static boolean isFatal(Throwable thrown) {
    return isProcessFatal(thrown)
        || (thrown instanceof LinkageError && !isMissingOptionalCodec(thrown));
  }

  /**
   * Whether {@code thrown} reports the class of a codec the deployment deliberately does not ship
   * (zstd-jni, a native library): a library loads it only when one input asks for that codec, such
   * as a ZIP entry with compression method 93, so the error is caused by that input alone.
   */
  public static boolean isMissingOptionalCodec(Throwable thrown) {
    return thrown instanceof NoClassDefFoundError
        && thrown.getMessage() != null
        && thrown.getMessage().replace('.', '/').startsWith(ZSTD_CODEC_PACKAGE);
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
