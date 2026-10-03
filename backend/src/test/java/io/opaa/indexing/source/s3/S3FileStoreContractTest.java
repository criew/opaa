package io.opaa.indexing.source.s3;

import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.filesync.FileStoreContract;
import io.opaa.s3.S3AccessException;
import java.util.List;

/** The file store contract for S3 over {@link FakeS3ObjectStore}: one bucket per container. */
class S3FileStoreContractTest extends FileStoreContract {

  private static final List<String> BUCKETS = List.of("vertrag-eins", "vertrag-zwei");

  @Override
  protected Fixture fixture() {
    FakeS3ObjectStore store = new FakeS3ObjectStore().bucket(BUCKETS.get(0)).bucket(BUCKETS.get(1));
    S3SourceSettings settings =
        new S3SourceSettings(
            "eu-central-1",
            true,
            List.of(S3Scope.of(BUCKETS.get(0), ""), S3Scope.of(BUCKETS.get(1), "")),
            null,
            null);
    return new Fixture() {
      @Override
      public void put(int container, String name, String text) {
        store.put(BUCKETS.get(container), name, text, "text/plain");
      }

      @Override
      public void put(int container, String name, byte[] bytes, String mediaType) {
        store.put(BUCKETS.get(container), name, bytes, mediaType);
      }

      @Override
      public void remove(int container, String name) {
        store.remove(BUCKETS.get(container), name);
      }

      @Override
      public void denyListing(int container) {
        String bucket = BUCKETS.get(container);
        store.failBucket(bucket, () -> new S3AccessException.ListForbidden(bucket));
      }

      @Override
      public String containerKey(int container) {
        return BUCKETS.get(container);
      }

      @Override
      public String filePath(int container, String name) {
        return S3ObjectRef.filePath(BUCKETS.get(container), name);
      }

      @Override
      public FileStore open() {
        return new S3FileStore(store, settings);
      }
    };
  }
}
