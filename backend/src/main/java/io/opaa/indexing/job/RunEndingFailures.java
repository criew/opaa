package io.opaa.indexing.job;

/**
 * What ends a run instead of counting as the failure of the item being processed. Connectors reach
 * {@link #rethrow} through {@code IndexingRun#rethrowRunEnding}, the core below {@code source}
 * ({@code AttachmentIndexer}) directly.
 */
public final class RunEndingFailures {

  private RunEndingFailures() {}

  /**
   * Lets what must end the run pass an item catch: an {@link InterruptedException} (itself or as a
   * cause, with the interrupt flag restored), a {@link RequestBudgetExhaustedException} and an
   * unchecked {@link EndsRun}. Every other failure returns to the caller, which records it as the
   * item's own.
   */
  public static void rethrow(Throwable failure) throws InterruptedException {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw interrupted;
      }
      if (t instanceof RequestBudgetExhaustedException exhausted) {
        throw exhausted;
      }
    }
    RuntimeException ending = endingCause(failure);
    if (ending != null) {
      throw ending;
    }
  }

  /**
   * The unchecked {@link EndsRun} {@code failure} is or carries as a cause, {@code null} for none -
   * for a wrapper (an SDK, a future) that must let it through unchanged.
   */
  public static RuntimeException endingCause(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof EndsRun && t instanceof RuntimeException ending) {
        return ending;
      }
    }
    return null;
  }
}
