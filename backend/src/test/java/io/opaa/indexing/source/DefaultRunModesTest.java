package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.test.SourceTypes;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link DefaultRunModes}: the executor's default, except a full run for a library whose sync state
 * was written under other settings or before settings were kept - where the executor knows one.
 */
class DefaultRunModesTest {

  private final SourceSyncStateRepository states = mock(SourceSyncStateRepository.class);
  private final LibrarySourceConnectionResolver resolver = new LibrarySourceConnectionResolver();
  private final DefaultRunModes modes =
      new DefaultRunModes(resolver, states, SyncStateBasis.WHOLE_SETTINGS);
  private final KnowledgeLibrary library =
      KnowledgeLibrary.ownedByUser(
          UUID.randomUUID(),
          "Wiki",
          null,
          UUID.randomUUID(),
          SourceTypes.CONFLUENCE,
          null,
          "https://wiki.example.org",
          null,
          null,
          false);
  private final SourceIndexingExecutor incremental =
      executor(
          Map.of(
              IndexingRunMode.FULL,
              VanishedDocumentPolicy.REMOVE_ON_ABSENCE,
              IndexingRunMode.INCREMENTAL,
              VanishedDocumentPolicy.KEEP_ON_ABSENCE));

  private static SourceIndexingExecutor executor(
      Map<IndexingRunMode, VanishedDocumentPolicy> runModes) {
    SourceIndexingExecutor executor = mock(SourceIndexingExecutor.class);
    when(executor.runModes()).thenReturn(runModes);
    when(executor.defaultRunMode(any(), any())).thenReturn(IndexingRunMode.INCREMENTAL);
    return executor;
  }

  private void stateUnder(String basis) {
    SourceSyncState state = new SourceSyncState(library.getId());
    if (basis != null) {
      state.adoptSettingsBasis(basis);
    }
    when(states.findByLibraryId(library.getId())).thenReturn(Optional.of(state));
  }

  private String currentBasis() {
    return SyncStateBasis.WHOLE_SETTINGS.current(library, resolver);
  }

  @Test
  void aStateUnderTheCurrentSettingsKeepsTheExecutorsChoice() {
    stateUnder(currentBasis());

    assertThat(modes.of(incremental, library)).isEqualTo(IndexingRunMode.INCREMENTAL);
  }

  // regression guard for #2268: an anchor of other settings made the next run a wasted one
  @Test
  void aStateUnderOtherSettingsMakesTheNextRunAFullOne() {
    stateUnder(currentBasis());
    library.updateSourceSettings("{\"spaces\":[\"HR\"]}");

    assertThat(modes.of(incremental, library)).isEqualTo(IndexingRunMode.FULL);
  }

  @Test
  void aStateWithoutABasisMakesTheNextRunAFullOne() {
    stateUnder(null);

    assertThat(modes.of(incremental, library)).isEqualTo(IndexingRunMode.FULL);
  }

  @Test
  void withoutAStateTheExecutorDecides() {
    when(states.findByLibraryId(library.getId())).thenReturn(Optional.empty());

    assertThat(modes.of(incremental, library)).isEqualTo(IndexingRunMode.INCREMENTAL);
  }

  @Test
  void anExecutorWithoutAFullRunKeepsItsChoice() {
    stateUnder(null);
    SourceIndexingExecutor windowOnly =
        executor(Map.of(IndexingRunMode.INCREMENTAL, VanishedDocumentPolicy.KEEP_ON_ABSENCE));

    assertThat(modes.of(windowOnly, library)).isEqualTo(IndexingRunMode.INCREMENTAL);
  }

  @Test
  void anotherConnectedAccountMakesTheNextRunAFullOne() {
    stateUnder(currentBasis());
    SourceConnectionResolver otherAccount =
        new LibrarySourceConnectionResolver() {
          @Override
          public String connectedAccount(KnowledgeLibrary candidate) {
            return "neu@example.org";
          }
        };

    assertThat(
            new DefaultRunModes(otherAccount, states, SyncStateBasis.WHOLE_SETTINGS)
                .of(incremental, library))
        .isEqualTo(IndexingRunMode.FULL);
  }
}
