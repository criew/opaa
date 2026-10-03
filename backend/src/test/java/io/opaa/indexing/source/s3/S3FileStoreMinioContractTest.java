package io.opaa.indexing.source.s3;

import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.filesync.FileStoreContract;
import io.opaa.s3.S3Credentials;
import io.opaa.s3.S3TestFixture;
import io.opaa.security.TargetAddressValidator;
import java.util.List;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The file store contract for S3 against a real object store ({@link S3TestFixture}): one fresh
 * bucket per container; a denied container is a bucket the run's key may not list. Skipped without
 * Docker; the CI runs it.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3FileStoreMinioContractTest extends FileStoreContract {

  @Override
  protected Fixture fixture() {
    S3TestFixture fixture = S3TestFixture.get();
    List<String> buckets =
        List.of(fixture.createBucket("vertrag-eins"), fixture.createBucket("vertrag-zwei"));
    S3SourceSettings settings =
        new S3SourceSettings(
            S3TestFixture.REGION,
            true,
            List.of(S3Scope.of(buckets.get(0), ""), S3Scope.of(buckets.get(1), "")),
            null,
            null);
    S3Properties properties = S3Properties.defaults();
    S3ClientFactory factory = new S3ClientFactory(properties, TargetAddressValidator.disabled());
    return new Fixture() {
      private S3Credentials credentials = fixture.rootCredentials();

      @Override
      public void put(int container, String name, String text) {
        fixture.putObject(buckets.get(container), name, text, "text/plain");
      }

      @Override
      public void put(int container, String name, byte[] bytes, String mediaType) {
        fixture.putObject(buckets.get(container), name, bytes, mediaType);
      }

      @Override
      public void remove(int container, String name) {
        fixture.deleteObject(buckets.get(container), name);
      }

      @Override
      public void denyListing(int container) {
        credentials =
            fixture.createUser(
                S3TestFixture.policyAllowing(
                    buckets.get(1 - container),
                    "s3:ListBucket",
                    "s3:GetObject",
                    "s3:GetBucketLocation"));
      }

      @Override
      public String containerKey(int container) {
        return buckets.get(container);
      }

      @Override
      public String filePath(int container, String name) {
        return S3ObjectRef.filePath(buckets.get(container), name);
      }

      @Override
      public FileStore open() throws Exception {
        return new S3FileStore(
            factory.createForRun(fixture.connection(credentials), settings.scopes()), settings);
      }
    };
  }
}
