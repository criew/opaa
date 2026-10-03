package io.opaa.indexing.source.s3;

import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.filesync.FileStoreContract;
import io.opaa.s3.S3Credentials;
import io.opaa.s3.S3TestFixture;
import io.opaa.security.TargetAddressValidator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The file store contract for S3 against a real object store ({@link S3TestFixture}): one fresh
 * bucket per container; a denied container or read is a right the run's key lacks, refused
 * credentials an unknown key. Skipped without Docker; the CI runs it.
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
    return new Fixture() {
      private final Set<Integer> unlistable = new HashSet<>();
      private final Set<Integer> unreadable = new HashSet<>();
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
        unlistable.add(container);
        credentials = fixture.createUser(policy());
      }

      @Override
      public void denyReading(int container) {
        unreadable.add(container);
        credentials = fixture.createUser(policy());
      }

      @Override
      public void rejectCredentials() {
        credentials = new S3Credentials("unbekannter-schluessel", "falsches-geheimnis", null);
      }

      /** Listing and reading for every bucket the scenario has not denied them on. */
      private String policy() {
        List<String> statements = new ArrayList<>();
        for (int container = 0; container < buckets.size(); container++) {
          String bucket = buckets.get(container);
          if (!unlistable.contains(container)) {
            statements.add(statement("\"s3:ListBucket\",\"s3:GetBucketLocation\"", bucket));
          }
          if (!unreadable.contains(container)) {
            statements.add(statement("\"s3:GetObject\"", bucket + "/*"));
          }
        }
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + String.join(",", statements) + "]}";
      }

      private static String statement(String actions, String resource) {
        return "{\"Effect\":\"Allow\",\"Action\":["
            + actions
            + "],\"Resource\":[\"arn:aws:s3:::"
            + resource
            + "\"]}";
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
      public FileStore open(int pageSize) throws Exception {
        S3Properties defaults = S3Properties.defaults();
        S3Properties properties =
            new S3Properties(
                pageSize,
                defaults.maxObjectSizeBytes(),
                defaults.requestTimeout(),
                0,
                defaults.retryBackoff(),
                defaults.requestBudgetPerRun(),
                defaults.tempDirectory(),
                defaults.maxObjectsPerRun(),
                defaults.downloadConcurrency());
        return new S3FileStore(
            new S3ClientFactory(properties, TargetAddressValidator.disabled())
                .createForRun(fixture.connection(credentials), settings.scopes()),
            settings);
      }
    };
  }
}
