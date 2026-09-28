package io.opaa.indexing.job;

/** What ends a run instead of counting as the failure of the item being processed. */
public final class RunEndingFailures {

  private RunEndingFailures() {}

  /**
   * Lets what must end the run pass an item catch: an {@link InterruptedException} (itself or as a
   * cause, with the interrupt flag restored) and a {@link RequestBudgetExhaustedException}. Every
   * other failure returns to the caller, which records it as the item's own.
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
  }
}
