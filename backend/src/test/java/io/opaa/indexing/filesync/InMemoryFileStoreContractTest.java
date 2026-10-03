package io.opaa.indexing.filesync;

/** The contract against the reference store: it holds without any connector. */
class InMemoryFileStoreContractTest extends FileStoreContract {

  @Override
  protected Fixture fixture() {
    InMemoryFileStore store = new InMemoryFileStore().container("A").container("B");
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
      public String containerKey(int container) {
        return container == 0 ? "A" : "B";
      }

      @Override
      public String filePath(int container, String name) {
        return InMemoryFileStore.filePath(containerKey(container), name);
      }

      @Override
      public FileStore open() {
        return store;
      }
    };
  }
}
