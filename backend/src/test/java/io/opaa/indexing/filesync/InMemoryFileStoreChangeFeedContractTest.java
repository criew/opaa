package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The change log contract against the reference store, and its counterproofs: it fails for a feed
 * that names the new start on its first page and for one that swallows an expired cursor.
 */
class InMemoryFileStoreChangeFeedContractTest extends FileStoreChangeFeedContract {

  @Override
  protected Fixture fixture() {
    return InMemoryFileStoreContractTest.fixtureOver(
        new InMemoryFileStore().withChangeFeed().withGlobalIds());
  }

  @Test
  void theContractCatchesAFeedThatMovesItsCursorBeforeTheLastPage() throws Exception {
    FileStoreChangeFeedContract broken =
        new FileStoreChangeFeedContract() {
          @Override
          protected Fixture fixture() {
            return InMemoryFileStoreContractTest.fixtureOver(
                new InMemoryFileStore().withChangeFeed().withEarlyNewStart());
          }
        };
    broken.setUpContract();

    assertThatThrownBy(broken::aStreamIsReadToItsLastPageBeforeItsCursorMovesOn)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("every page of the stream");
  }

  @Test
  void theContractCatchesAFeedThatSwallowsAnExpiredCursor() throws Exception {
    FileStoreChangeFeedContract broken =
        new FileStoreChangeFeedContract() {
          @Override
          protected Fixture fixture() {
            return InMemoryFileStoreContractTest.fixtureOver(
                new InMemoryFileStore().withChangeFeed().withSwallowedExpiry());
          }
        };
    broken.setUpContract();

    assertThatThrownBy(broken::anExpiredCursorDropsItsStreamAndTheNextRunIsAFullSync)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("expired streams are dropped");
  }
}
