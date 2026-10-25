package io.opaa.architecture.fixture.transactioncallback.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Allowed to register transaction synchronizations. */
public final class TransactionCallbacks {

  private TransactionCallbacks() {}

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
