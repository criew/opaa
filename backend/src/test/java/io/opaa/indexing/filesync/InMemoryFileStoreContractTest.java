package io.opaa.indexing.filesync;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The contract against the reference store: it holds without any connector, and it fails for a
 * store that reports its first page as the last one.
 */
class InMemoryFileStoreContractTest extends FileStoreContract {

  @Override
  protected Fixture fixture() {
    return fixtureOver(new InMemoryFileStore());
  }

  static Fixture fixtureOver(InMemoryFileStore store) {
    store.container("A").container("B");
    return new Fixture() {
      @Override
      public void put(int container, String name, String text) {
        store.put(containerKey(container), name, text);
      }

      @Override
      public void put(int container, String name, byte[] bytes, String mediaType) {
        store.put(containerKey(container), name, bytes, mediaType);
      }

      @Override
      public void remove(int container, String name) {
        store.remove(containerKey(container), name);
      }

      @Override
      public void denyListing(int container) {
        store.denyListing(containerKey(container));
      }

      @Override
      public void denyReading(int container) {
        store.denyReading(containerKey(container));
      }

      @Override
      public void rejectCredentials() {
        store.rejectCredentials();
      }

      @Override
      public String containerKey(int container) {
        return container == 0 ? "A" : "B";
      }

      @Override
      public String filePath(int container, String name) {
        return store.filePathOf(containerKey(container), name);
      }

      @Override
      public void changed(int container, String name) {
        store.changed(containerKey(container), name);
      }

      @Override
      public void moveAcross(int from, String name, int to) {
        store.moveAcross(containerKey(from), name, containerKey(to));
      }

      @Override
      public void expireCursors() {
        store.expireCursors();
      }

      @Override
      public void move(int container, String from, String to) {
        store.move(containerKey(container), from, to);
      }

      @Override
      public FileStore open(int pageSize) {
        return store.pageSize(pageSize);
      }
    };
  }

  @Test
  void theContractCatchesAStoreThatEndsItsListingAfterTheFirstPage() throws Exception {
    FileStoreContract broken =
        new FileStoreContract() {
          @Override
          protected Fixture fixture() {
            return fixtureOver(new InMemoryFileStore().endAfterFirstPage());
          }
        };
    broken.setUpContract();

    assertThatThrownBy(broken::aListingOverSeveralPagesIsFollowedToItsLastPage)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("every page");
  }
}
