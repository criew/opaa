package io.opaa.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one place that registers a {@link TransactionSynchronization}: work deferred to the end of
 * the caller's transaction. "Active" means {@link
 * TransactionSynchronizationManager#isSynchronizationActive()}; without it the {@code ...OrNow}
 * variants run the work at once on the calling thread, so it is never silently dropped.
 */
public final class TransactionCallbacks {

  private TransactionCallbacks() {}

  /**
   * Runs {@code work} once the active transaction has committed, never after a rollback; runs it at
   * once without active synchronization. For work that must only follow persisted state.
   */
  public static void afterCommitOrNow(Runnable work) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      work.run();
      return;
    }
    afterCommit(work);
  }

  /**
   * Runs {@code work} once the active transaction has finished, after a commit and after a rollback
   * alike; runs it at once without active synchronization. For cache evictions, where a stale entry
   * is wrong on either outcome.
   */
  public static void afterCompletionOrNow(Runnable work) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      work.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            work.run();
          }
        });
  }

  /**
   * Runs {@code work} once the active transaction has committed, never after a rollback. Without
   * active synchronization it throws {@link IllegalStateException}, as {@link
   * TransactionSynchronizationManager#registerSynchronization} does: for callers that require a
   * transaction ({@code MANDATORY}) and must not run the work outside one.
   */
  public static void afterCommit(Runnable work) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            work.run();
          }
        });
  }
}
