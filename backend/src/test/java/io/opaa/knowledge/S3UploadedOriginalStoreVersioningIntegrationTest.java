package io.opaa.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.s3.S3Credentials;
import io.opaa.s3.S3TestFixture;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
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
 * deletion leaves neither an older version nor a delete marker behind, what a plain {@code
 * DeleteObject} left there is counted and removed per library - on one page and over many - and
 * credentials without {@code s3:ListBucketVersions} answer "none" only for a bucket that was never
 * versioned. Skipped without Docker.
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
  void whatPlainDeletionsLeftIsCountedAndErasedWhileCurrentOriginalsStay() throws IOException {
    assertRetainedVersionsAreErased(store);
  }

  @Test
  void retainedVersionsAreErasedCompletelyWhenTheListingRunsOverManyPages() throws IOException {
    // One entry per page: erasing a delete marker while paging would make the older version of
    // the same key current on a later page and leave it there.
    S3UploadedOriginalStore paging = storeFor(bucket, fixture.rootCredentials(), 1);
    try {
      assertRetainedVersionsAreErased(paging);
    } finally {
      paging.close();
    }
  }

  @Test
  void
      withoutTheRightToListVersionsANeverVersionedBucketRetainsNoneAndAVersionedOneIsUnavailable() {
    String unversioned = fixture.createBucket("opaa-unversioniert");
    String[] rights = {
      "s3:ListBucket", "s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:GetBucketVersioning"
    };
    S3UploadedOriginalStore plain =
        storeFor(
            unversioned,
            fixture.createUser(S3TestFixture.policyAllowing(unversioned, rights)),
            S3UploadedOriginalStore.LIST_PAGE_SIZE);
    S3UploadedOriginalStore versioned =
        storeFor(
            bucket,
            fixture.createUser(S3TestFixture.policyAllowing(bucket, rights)),
            S3UploadedOriginalStore.LIST_PAGE_SIZE);
    try {
      assertThat(plain.countRetainedVersions(organizationId, libraryId)).isZero();
      assertThatThrownBy(() -> versioned.countRetainedVersions(organizationId, libraryId))
          .isInstanceOf(UploadStoreUnavailableException.class);
    } finally {
      plain.close();
      versioned.close();
    }
  }

  /**
   * Key K with two versions under a delete marker, a second key with one version under a marker, a
   * current original that stays, and a deleted original of another library that is not touched.
   */
  private void assertRetainedVersionsAreErased(S3UploadedOriginalStore subject) throws IOException {
    UUID library = UUID.randomUUID();
    UploadedOriginalRef twice = storedOriginal(library, "erste Fassung");
    String twiceKey = keyOf(twice);
    fixture.putObject(bucket, twiceKey, "zweite Fassung", "application/pdf");
    fixture.deleteObject(bucket, twiceKey);
    UploadedOriginalRef once = storedOriginal(library, "nur mit Löschmarker entfernt");
    fixture.deleteObject(bucket, keyOf(once));
    UploadedOriginalRef current = storedOriginal(library, "bleibt");
    UUID otherLibrary = UUID.randomUUID();
    UploadedOriginalRef foreign = storedOriginal(otherLibrary, "andere Bibliothek");
    fixture.deleteObject(bucket, keyOf(foreign));

    List<String> visible = new ArrayList<>();
    subject.forEachStoredOriginal(
        organizationId, library, original -> visible.add(original.locator()));
    assertThat(visible).as("the listing of current originals").containsExactly(current.locator());
    assertThat(subject.countRetainedVersions(organizationId, library))
        .as("three older versions and two delete markers")
        .isEqualTo(5);

    assertThat(subject.eraseRetainedVersions(organizationId, library)).isEqualTo(5);

    assertThat(subject.countRetainedVersions(organizationId, library)).isZero();
    assertThat(versionsOf(twiceKey)).isEmpty();
    assertThat(versionsOf(keyOf(once))).isEmpty();
    assertThat(versionsOf(keyOf(current))).hasSize(1);
    assertThat(subject.countRetainedVersions(organizationId, otherLibrary))
        .as("another library's versions are not touched")
        .isEqualTo(2);
  }

  private static S3UploadedOriginalStore storeFor(
      String bucketName, S3Credentials credentials, int listPageSize) {
    UploadS3Properties properties =
        new UploadS3Properties(
            fixture.endpoint().toString(),
            S3TestFixture.REGION,
            bucketName,
            "uploads/",
            true,
            credentials.accessKey(),
            credentials.secretKey(),
            tempDir,
            new UploadS3Properties.TargetValidation(true, List.of()));
    return new S3UploadedOriginalStore(
        properties,
        UploadS3TargetPolicy.of(properties),
        S3UploadedOriginalStore.REQUEST_TIMEOUT,
        1,
        Duration.ofMillis(1),
        listPageSize);
  }

  private UploadedOriginalRef storedOriginal(UUID library, String content) throws IOException {
    UploadedOriginalStore.AcceptedUpload accepted =
        store.accept(organizationId, library, ".pdf", bytes(content));
    UploadedOriginalRef ref = accepted.store();
    accepted.release();
    return ref;
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
