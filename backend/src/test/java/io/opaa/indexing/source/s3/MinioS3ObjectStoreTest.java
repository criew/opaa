package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.sourceaccess.TargetAddressValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The adapter against a real MinIO (ADR-0027, Entscheidung 9): pagination past 1000 keys,
 * path-style addressing, the ETag as MD5 of a simple upload, folder markers, the byte ceiling, a
 * missing bucket, and the rights a restricted key lacks - listing without reading, and neither - as
 * {@link S3ObjectStore#testAccess} reports them. Skipped without Docker; the CI runs it.
 */
@Testcontainers(disabledWithoutDocker = true)
class MinioS3ObjectStoreTest {

  private static MinioFixture minio;
  private static String bucket;
  private static S3ObjectStore root;

  @BeforeAll
  static void start() throws Exception {
    minio = MinioFixture.get();
    bucket = minio.createBucket("opaa-zugriff");
    minio.putObject(bucket, "2025/protokolle/sitzung.pdf", "%PDF-1.4 sitzung", "application/pdf");
    minio.putObject(bucket, "2025/protokolle/", new byte[0], "application/x-directory");
    minio.putObject(
        bucket,
        "2024/alt.docx",
        "alt",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    minio.putObject(bucket, "gross.bin", new byte[4096], "application/octet-stream");
    minio.putMany(bucket, "viele/", 1100, null);
    root =
        store(
            minio.rootCredentials(),
            new S3Properties(0, 0, Duration.ofSeconds(10), null, null, 0, null, 0, 0));
  }

  @AfterAll
  static void stop() {
    if (root != null) {
      root.close();
    }
  }

  private static S3ObjectStore store(S3Credentials credentials, S3Properties properties)
      throws Exception {
    return new S3ClientFactory(properties, TargetAddressValidator.disabled())
        .create(minio.connection(credentials), List.of(S3Scope.of(bucket, "")));
  }

  @Test
  void aPrivateEndpointIsRefusedWithoutAnAllowlistEntryAndReachedWithOne() throws Exception {
    // Assurance (ADR-0027, Entscheidung 8): the container's loopback address is exactly the
    // private target the validation blocks; only an operator's allowlist entry opens it.
    S3Properties properties = S3Properties.defaults();
    List<S3Scope> scopes = List.of(S3Scope.of(bucket, ""));
    assertThatThrownBy(
            () ->
                new S3ClientFactory(properties, new TargetAddressValidator(true, List.of()))
                    .create(minio.connection(minio.rootCredentials()), scopes))
        .isInstanceOf(S3AccessException.TargetBlocked.class)
        .hasMessageContaining(TargetAddressValidator.ALLOWLIST_HINT);
    try (S3ObjectStore allowed =
        new S3ClientFactory(
                properties, new TargetAddressValidator(true, List.of(minio.endpoint().getHost())))
            .create(minio.connection(minio.rootCredentials()), scopes)) {
      assertThat(allowed.listObjects(S3Scope.of(bucket, "2024/"), null).objects())
          .extracting(S3ObjectSummary::key)
          .containsExactly("2024/alt.docx");
    }
  }

  @Test
  void listsMoreThanAThousandKeysAcrossPages() throws Exception {
    List<String> keys = new ArrayList<>();
    String token = null;
    int pages = 0;
    do {
      S3ListPage page = root.listObjects(S3Scope.of(bucket, "viele"), token);
      page.objects().forEach(o -> keys.add(o.key()));
      token = page.nextContinuationToken();
      pages++;
    } while (token != null);

    assertThat(keys).hasSize(1100).isSorted().allMatch(k -> k.startsWith("viele/"));
    assertThat(pages).isEqualTo(2);
  }

  @Test
  void aSmallerPageSizeFollowsMoreTokens() throws Exception {
    try (S3ObjectStore small =
        store(minio.rootCredentials(), new S3Properties(300, 0, null, null, null, 0, null, 0, 0))) {
      int pages = 0;
      int objects = 0;
      String token = null;
      do {
        S3ListPage page = small.listObjects(S3Scope.of(bucket, "viele/"), token);
        objects += page.objects().size();
        token = page.nextContinuationToken();
        pages++;
      } while (token != null);
      assertThat(objects).isEqualTo(1100);
      assertThat(pages).isEqualTo(4);
      assertThat(small.meter().requests()).isEqualTo(4);
    }
  }

  @Test
  void listingCarriesTagSizeTimestampAndFolderMarkers() throws Exception {
    S3ListPage page = root.listObjects(S3Scope.of(bucket, "2025/protokolle"), null);

    assertThat(page.isLast()).isTrue();
    assertThat(page.objects())
        .extracting(S3ObjectSummary::key)
        .containsExactly("2025/protokolle/", "2025/protokolle/sitzung.pdf");
    S3ObjectSummary marker = page.objects().get(0);
    assertThat(marker.isFolderMarker()).isTrue();
    S3ObjectSummary pdf = page.objects().get(1);
    assertThat(pdf.isFolderMarker()).isFalse();
    assertThat(pdf.eTag()).isEqualTo(md5("%PDF-1.4 sitzung"));
    assertThat(pdf.size()).isEqualTo("%PDF-1.4 sitzung".length());
    assertThat(pdf.lastModified()).isNotNull();
    assertThat(pdf.storageClass()).isEqualTo("STANDARD");
    assertThat(pdf.isArchived()).isFalse();
  }

  @Test
  void headAndGetAgreeWithTheListing() throws Exception {
    S3ObjectHead head = root.headObject(bucket, "2025/protokolle/sitzung.pdf");
    assertThat(head.contentType()).isEqualTo("application/pdf");
    assertThat(head.eTag()).isEqualTo(md5("%PDF-1.4 sitzung"));
    assertThat(head.size()).isEqualTo("%PDF-1.4 sitzung".length());
    assertThat(head.archived()).isFalse();

    S3Download download = root.getObject(bucket, "2025/protokolle/sitzung.pdf", 1024);
    try {
      assertThat(Files.readString(download.file())).isEqualTo("%PDF-1.4 sitzung");
      assertThat(download.eTag()).isEqualTo(head.eTag());
      assertThat(download.contentType()).isEqualTo("application/pdf");
      assertThat(download.file().toString()).endsWith(".pdf");
    } finally {
      Files.deleteIfExists(download.file());
    }
    assertThat(root.meter().bytesDownloaded()).isGreaterThanOrEqualTo("%PDF-1.4 sitzung".length());
  }

  @Test
  void theByteCeilingRefusesAnOversizeObjectWithoutKeepingAFile() throws Exception {
    Path tempDir = Files.createTempDirectory("opaa-s3-test-");
    try (S3ObjectStore store =
        store(
            minio.rootCredentials(),
            new S3Properties(0, 1024, null, null, null, 0, tempDir, 0, 0))) {
      assertThatThrownBy(() -> store.getObject(bucket, "gross.bin", 1024))
          .isInstanceOf(S3AccessException.ObjectTooLarge.class);
      assertThatThrownBy(() -> store.getObject(bucket, "gross.bin"))
          .isInstanceOf(S3AccessException.ObjectTooLarge.class);
      assertThat(Files.list(tempDir).toList()).as("no partial file survives").isEmpty();
    } finally {
      Files.deleteIfExists(tempDir);
    }
  }

  @Test
  void aMissingObjectAndAMissingBucketAreDistinctFindings() throws Exception {
    assertThatThrownBy(() -> root.headObject(bucket, "gibtsnicht.pdf"))
        .isInstanceOf(S3AccessException.ObjectNotFound.class);
    assertThatThrownBy(() -> root.listObjects(S3Scope.of("opaa-gibtsnicht", ""), null))
        .isInstanceOf(S3AccessException.BucketNotFound.class);
    assertThat(root.testAccess(S3Scope.of("opaa-gibtsnicht", "")).bucketReachable()).isFalse();
  }

  @Test
  void rootCredentialsPassEveryStepOfTheAccessCheck() throws Exception {
    S3AccessCheck check = root.testAccess(S3Scope.of(bucket, "2025/"));

    assertThat(check.passed()).isTrue();
    assertThat(check.bucketReachable()).isTrue();
    assertThat(check.listAllowed()).isTrue();
    assertThat(check.readAllowed()).isTrue();
    assertThat(check.objectCount()).isEqualTo(2);
    assertThat(check.objectCountIsLowerBound()).isFalse();
    assertThat(root.listBuckets()).isInstanceOf(S3BucketListing.Listed.class);
    assertThat(((S3BucketListing.Listed) root.listBuckets()).names()).contains(bucket);
  }

  @Test
  void aKeyThatMayListButNotReadIsReportedAsSuch() throws Exception {
    S3Credentials listOnly =
        minio.createUser(
            MinioFixture.policyAllowing(bucket, "s3:ListBucket", "s3:GetBucketLocation"));
    try (S3ObjectStore store = store(listOnly, S3Properties.defaults())) {
      S3AccessCheck check = store.testAccess(S3Scope.of(bucket, "2024/"));

      assertThat(check.bucketReachable()).isTrue();
      assertThat(check.listAllowed()).isTrue();
      assertThat(check.readAllowed()).isFalse();
      assertThat(check.failure())
          .isInstanceOf(S3AccessException.ReadForbidden.class)
          .hasMessageContaining("s3:GetObject");
      assertThatThrownBy(() -> store.getObject(bucket, "2024/alt.docx", 1024))
          .isInstanceOf(S3AccessException.ReadForbidden.class);
      // MinIO filters ListBuckets to the buckets the key may see instead of refusing it
      assertThat(store.listBuckets())
          .isInstanceOf(S3BucketListing.Listed.class)
          .extracting(l -> ((S3BucketListing.Listed) l).names())
          .isEqualTo(List.of(bucket));
    }
  }

  @Test
  void aKeyWithoutRightsOnTheBucketCannotListIt() throws Exception {
    String other = minio.createBucket("opaa-fremd");
    S3Credentials otherOnly = minio.createUser(MinioFixture.policyAllowing(other, "s3:ListBucket"));
    try (S3ObjectStore store = store(otherOnly, S3Properties.defaults())) {
      S3AccessCheck check = store.testAccess(S3Scope.of(bucket, ""));

      assertThat(check.listAllowed()).isFalse();
      assertThat(check.failure())
          .isInstanceOf(S3AccessException.ListForbidden.class)
          .hasMessageContaining("s3:ListBucket");
      assertThatThrownBy(() -> store.listObjects(S3Scope.of(bucket, ""), null))
          .isInstanceOf(S3AccessException.ListForbidden.class);
    }
  }

  @Test
  void wrongCredentialsAreRefusedAsSuch() throws Exception {
    try (S3ObjectStore store =
        store(
            new S3Credentials("falscher-key", "falsches-geheimnis", null),
            S3Properties.defaults())) {
      assertThatThrownBy(() -> store.listObjects(S3Scope.of(bucket, ""), null))
          .isInstanceOf(S3AccessException.Authentication.class)
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain("falsches-geheimnis"));

      // the probe names refused credentials, not a missing s3:ListBucket
      S3AccessCheck check = store.testAccess(S3Scope.of(bucket, ""));
      assertThat(check.bucketReachable()).isFalse();
      assertThat(check.failure()).isInstanceOf(S3AccessException.Authentication.class);
    }
  }

  private static String md5(String text) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
  }
}
