package io.opaa.connection.profile;

import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Run state a profile change discarded while a run of its library was going: that run writes its
 * state again as it goes, so its connector discards it once more when the run ends. Held in memory
 * - one instance runs (ADR-0021); a reset noted after its run already ended costs the next run one
 * more full sync, never a mixed state.
 */
@Component
public class RunStateResets {

  private final Map<UUID, Reset> pending = new ConcurrentHashMap<>();
  private final TransactionTemplate transactions;

  /** Looked up per call: the core's port reaches this class, and the connectors the core. */
  private final ObjectProvider<SourceConnectorRegistry> connectors;

  RunStateResets(
      PlatformTransactionManager transactionManager,
      ObjectProvider<SourceConnectorRegistry> connectors) {
    this.transactions = new TransactionTemplate(transactionManager);
    this.connectors = connectors;
  }

  /**
   * Repeats, once the going run of {@code library} ends, the discard of its run state for {@code
   * changed} - noted after the caller's transaction commits, nothing when it rolls back.
   */
  void repeatAfterRun(KnowledgeLibrary library, boolean addressChanged, Set<String> changed) {
    Runnable note =
        () -> pending.merge(library.getId(), new Reset(addressChanged, changed), Reset::and);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              note.run();
            }
          });
    } else {
      note.run();
    }
  }

  /** The run of {@code library} ended: a reset noted for it is carried out now. */
  public void runEnded(KnowledgeLibrary library) {
    Reset reset = pending.remove(library.getId());
    if (reset != null) {
      transactions.executeWithoutResult(
          status ->
              connectors
                  .getObject()
                  .connector(library.getSourceType())
                  .onSourceChanged(library, reset.addressChanged(), reset.changed()));
    }
  }

  private record Reset(boolean addressChanged, Set<String> changed) {

    Reset and(Reset other) {
      Set<String> both = new LinkedHashSet<>(changed);
      both.addAll(other.changed);
      return new Reset(addressChanged || other.addressChanged, Set.copyOf(both));
    }
  }
}
