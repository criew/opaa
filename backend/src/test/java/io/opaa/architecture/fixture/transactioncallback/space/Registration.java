package io.opaa.architecture.fixture.transactioncallback.space;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Registers a synchronization it did not declare. */
public class Registration {

  void register(TransactionSynchronization synchronization) {
    TransactionSynchronizationManager.registerSynchronization(synchronization);
  }
}
