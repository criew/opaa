package io.opaa.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * {@link TransactionCallbacks}, driven through {@link TransactionSynchronizationManager} the way a
 * transaction manager does: with active synchronization the work waits for its phase, without it
 * the {@code ...OrNow} variants run it at once and the strict variant refuses.
 */
class TransactionCallbacksTest {

  private final AtomicInteger runs = new AtomicInteger();

  @AfterEach
  void clearSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void afterCommitOrNowRunsAtOnceWithoutSynchronization() {
    TransactionCallbacks.afterCommitOrNow(runs::incrementAndGet);

    assertThat(runs).hasValue(1);
  }

  @Test
  void afterCommitOrNowWaitsForTheCommit() {
    TransactionSynchronizationManager.initSynchronization();

    TransactionCallbacks.afterCommitOrNow(runs::incrementAndGet);
    assertThat(runs).hasValue(0);

    commit();
    assertThat(runs).hasValue(1);
  }

  @Test
  void afterCommitOrNowSkipsTheWorkOnRollback() {
    TransactionSynchronizationManager.initSynchronization();

    TransactionCallbacks.afterCommitOrNow(runs::incrementAndGet);
    rollback();

    assertThat(runs).hasValue(0);
  }

  @Test
  void afterCompletionOrNowRunsAtOnceWithoutSynchronization() {
    TransactionCallbacks.afterCompletionOrNow(runs::incrementAndGet);

    assertThat(runs).hasValue(1);
  }

  @Test
  void afterCompletionOrNowWaitsForTheCommit() {
    TransactionSynchronizationManager.initSynchronization();

    TransactionCallbacks.afterCompletionOrNow(runs::incrementAndGet);
    assertThat(runs).hasValue(0);

    commit();
    assertThat(runs).hasValue(1);
  }

  @Test
  void afterCompletionOrNowRunsTheWorkOnRollbackToo() {
    TransactionSynchronizationManager.initSynchronization();

    TransactionCallbacks.afterCompletionOrNow(runs::incrementAndGet);
    rollback();

    assertThat(runs).hasValue(1);
  }

  @Test
  void afterCommitWaitsForTheCommitAndSkipsARollback() {
    TransactionSynchronizationManager.initSynchronization();
    TransactionCallbacks.afterCommit(runs::incrementAndGet);
    commit();
    assertThat(runs).hasValue(1);

    TransactionSynchronizationManager.initSynchronization();
    TransactionCallbacks.afterCommit(runs::incrementAndGet);
    rollback();
    assertThat(runs).hasValue(1);
  }

  @Test
  void afterCommitRefusesWithoutSynchronization() {
    assertThatThrownBy(() -> TransactionCallbacks.afterCommit(runs::incrementAndGet))
        .isInstanceOf(IllegalStateException.class);
    assertThat(runs).hasValue(0);
  }

  private static void commit() {
    var synchronizations = TransactionSynchronizationManager.getSynchronizations();
    TransactionSynchronizationUtils.invokeAfterCommit(synchronizations);
    TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationUtils.invokeAfterCompletion(
        synchronizations, TransactionSynchronization.STATUS_COMMITTED);
  }

  private static void rollback() {
    var synchronizations = TransactionSynchronizationManager.getSynchronizations();
    TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationUtils.invokeAfterCompletion(
        synchronizations, TransactionSynchronization.STATUS_ROLLED_BACK);
  }
}
