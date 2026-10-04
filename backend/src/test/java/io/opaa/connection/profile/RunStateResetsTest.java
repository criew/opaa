package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.TestSourceConnectors;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector.SourceChange;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The note to discard a run state again stands from the moment the change makes it, inside its
 * transaction, so a run ending before the commit is caught too; a rollback takes it back.
 */
class RunStateResetsTest {

  private final ProfileProbeSourceConnector connector = new ProfileProbeSourceConnector();
  private final KnowledgeLibrary library = mock(KnowledgeLibrary.class);
  private final UUID libraryId = UUID.randomUUID();
  private RunStateResets resets;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    SourceConnectorRegistry registry = TestSourceConnectors.connectors().with(connector).registry();
    ObjectProvider<SourceConnectorRegistry> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(registry);
    resets = new RunStateResets(mock(PlatformTransactionManager.class), provider);
    when(library.getId()).thenReturn(libraryId);
    when(library.getSourceType()).thenReturn(ProfileProbeSourceConnector.TYPE);
    TransactionSynchronizationManager.initSynchronization();
  }

  @AfterEach
  void clearSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void aRunEndingBeforeTheChangeCommitsHasItsStateDiscardedAgain() {
    resets.repeatAfterRun(library, false, Set.of("edition"));

    resets.runEnded(library);

    assertThat(connector.sourceChanges())
        .containsExactly(new SourceChange(libraryId, false, Set.of("edition")));
  }

  @Test
  void aRolledBackChangeLeavesNoNote() {
    resets.repeatAfterRun(library, false, Set.of("edition"));
    for (TransactionSynchronization synchronization :
        TransactionSynchronizationManager.getSynchronizations()) {
      synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    }
    TransactionSynchronizationManager.clearSynchronization();

    resets.runEnded(library);

    assertThat(connector.sourceChanges()).isEmpty();
  }
}
