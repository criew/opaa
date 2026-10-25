package io.opaa.architecture.fixture.transactioncallback.group;

import io.opaa.architecture.fixture.transactioncallback.common.TransactionCallbacks;
import org.springframework.transaction.support.TransactionSynchronization;

/** Declares its own synchronization beside a use of the helper. */
public class Eviction {

  void viaHelper(Runnable work) {
    TransactionCallbacks.afterCommit(work);
  }

  TransactionSynchronization ownSynchronization(Runnable work) {
    return new TransactionSynchronization() {
      @Override
      public void afterCompletion(int status) {
        work.run();
      }
    };
  }
}
