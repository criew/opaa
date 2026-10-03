package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The folder contract against the reference store with propagating folder markers and file ids that
 * survive a rename - the shape of Nextcloud. A store whose markers stop at their own folder fails
 * it.
 */
class InMemoryFileStoreFolderContractTest extends FileStoreFolderContract {

  @Override
  protected Fixture fixture() {
    return InMemoryFileStoreContractTest.fixtureOver(
        new InMemoryFileStore().withFolderMarkers().withStableIds());
  }

  @Test
  void theContractCatchesAStoreWhoseMarkersDoNotPropagate() throws Exception {
    FileStoreFolderContract broken =
        new FileStoreFolderContract() {
          @Override
          protected Fixture fixture() {
            return InMemoryFileStoreContractTest.fixtureOver(
                new InMemoryFileStore().withShallowFolderMarkers().withStableIds());
          }
        };
    broken.setUpContract();

    assertThatThrownBy(broken::aChangeDeepDownReachesTheRunAndAnUnchangedTreeIsLeftAlone)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("reaches the root");
  }
}
