package io.opaa.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.s3.S3TestFixture;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

/**
 * The S3 adapter against a versioned bucket of a real object store ({@link S3TestFixture}): a
 * deletion leaves neither an older version nor a delete marker behind, and what a plain {@code
 * DeleteObject} left there is counted and removed per library. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3UploadedOriginalStoreVersioningIntegrationTest {

  private static S3TestFixture fixture;
  private static String bucket;
  private static S3UploadedOriginalStore store;

  @TempDir static Path tempDir;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID libraryId = UUID.randomUUID();

  @BeforeAll
  static void start() {
    fixture = S3TestFixture.get();
    bucket = fixture.createBucket("opaa-versioniert");
    fixture
        .admin()
        .putBucketVersioning(
            PutBucketVersioningRequest.builder()
                .bucket(bucket)
                .versioningConfiguration(
                    VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.ENABLED)
                        .build())
                .build());
    store =
        new S3UploadedOriginalStore(
            new UploadS3Properties(
                fixture.endpoint().toString(),
                S3TestFixture.REGION,
                bucket,
                "uploads/",
                true,
                fixture.rootCredentials().accessKey(),
                fixture.rootCredentials().secretKey(),
                tempDir,
                new UploadS3Properties.TargetValidation(true, List.of())));
  }

  @AfterAll
  static void stop() {
    if (store != null) {
      store.close();
    }
  }

  @Test
  void deletingAnOriginalRemovesEveryVersionAndLeavesNoDeleteMarker() throws IOException {
    UploadedOriginalRef ref = storedOriginal("Bescheid, erste Fassung");
    String key = keyOf(ref);
    // a second version of the same key, as a restore or a copy by the operator would write it
    fixture.putObject(bucket, key, "Bescheid, zweite Fassung", "application/pdf");
    assertThat(versionsOf(key)).hasSize(2);

    store.delete(ref);

    assertThat(versionsOf(key)).as("no version and no delete marker of the key is left").isEmpty();
    assertThat(store.countRetainedVersions(organizationId, libraryId)).isZero();
  }

  @Test
  void aDiscardedUploadLeavesNoVersionBehind() throws IOException {
    UploadedOriginalStore.AcceptedUpload accepted =
        store.accept(organizationId, libraryId, ".pdf", bytes("abgelehnt"));
    String key = keyOf(accepted.store());

    accepted.discard();

    assertThat(versionsOf(key)).isEmpty();
  }

  @Test
  void whatAPlainDeletionLeftIsCountedAndErasedWhileCurrentOriginalsStay() throws IOException {
    UploadedOriginalRef deleted = storedOriginal("nur mit Löschmarker entfernt");
    String deletedKey = keyOf(deleted);
    fixture.deleteObject(bucket, deletedKey);
    UploadedOriginalRef current = storedOriginal("bleibt");
    UUID otherLibrary = UUID.randomUUID();
    UploadedOriginalRef foreign =
        store.accept(organizationId, otherLibrary, ".pdf", bytes("andere Bibliothek")).store();
    fixture.deleteObject(bucket, keyOf(foreign));

    List<String> visible = new ArrayList<>();
    store.forEachStoredOriginal(
        organizationId, libraryId, original -> visible.add(original.locator()));
    assertThat(visible).as("the listing of current originals").containsExactly(current.locator());
    assertThat(store.countRetainedVersions(organizationId, libraryId))
        .as("the older version and the delete marker")
        .isEqualTo(2);

    assertThat(store.eraseRetainedVersions(organizationId, libraryId)).isEqualTo(2);

    assertThat(store.countRetainedVersions(organizationId, libraryId)).isZero();
    assertThat(versionsOf(deletedKey)).isEmpty();
    assertThat(versionsOf(keyOf(current))).hasSize(1);
    assertThat(store.countRetainedVersions(organizationId, otherLibrary))
        .as("another library's versions are not touched")
        .isEqualTo(2);
  }

  private UploadedOriginalRef storedOriginal(String content) throws IOException {
    UploadedOriginalStore.AcceptedUpload accepted =
        store.accept(organizationId, libraryId, ".pdf", bytes(content));
    UploadedOriginalRef ref = accepted.store();
    accepted.release();
    return ref;
  }

  /** Every version and delete marker of exactly {@code key}. */
  private static List<String> versionsOf(String key) {
    ListObjectVersionsResponse listing =
        fixture
            .admin()
            .listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(bucket).prefix(key).build());
    List<String> versions = new ArrayList<>();
    listing.versions().stream()
        .filter(version -> version.key().equals(key))
        .forEach(version -> versions.add("version " + version.versionId()));
    listing.deleteMarkers().stream()
        .filter(marker -> marker.key().equals(key))
        .forEach(marker -> versions.add("marker " + marker.versionId()));
    return versions;
  }

  private static String keyOf(UploadedOriginalRef ref) {
    return ref.locator().substring(("s3://" + bucket + "/").length());
  }

  private static ByteArrayInputStream bytes(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }
}
