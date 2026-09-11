package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.indexing.source.s3.MinioFixture;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The S3 adapter against a real MinIO ({@link MinioFixture}, ADR-0030): store and serve, delete,
 * delete of a missing object, containment against a key of another library and of another
 * organization, the synthetic attachment locator, the working file after release and the local copy
 * after the action. The container's private address is reached with the target validation on and no
 * allowlist entry - the configured endpoint passes on its own (Entscheidung 8). Skipped without
 * Docker; the CI runs it.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3UploadedOriginalStoreMinioTest {

  private static MinioFixture minio;
  private static String bucket;
  private static S3UploadedOriginalStore store;

  @TempDir static Path tempDir;

  private final UUID organizationId = UUID.randomUUID();
  private final UUID libraryId = UUID.randomUUID();

  @BeforeAll
  static void start() {
    minio = MinioFixture.get();
    bucket = minio.createBucket("opaa-ablage");
    store =
        new S3UploadedOriginalStore(
            new UploadS3Properties(
                minio.endpoint().toString(),
                MinioFixture.REGION,
                bucket,
                "uploads/",
                true,
                minio.rootCredentials().accessKey(),
                minio.rootCredentials().secretKey(),
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
  void anUploadIsStoredUnderItsOrganizationAndLibraryPrefixAndServedBackAsAStream()
      throws IOException {
    UploadedOriginalStore.AcceptedUpload accepted =
        store.accept(organizationId, libraryId, ".pdf", bytes("%PDF-1.4 bescheid"));
    UploadedOriginalRef ref = accepted.store();
    accepted.release();

    String libraryPrefix = "uploads/" + organizationId + "/" + libraryId + "/";
    assertThat(ref.locator()).startsWith("s3://" + bucket + "/" + libraryPrefix);
    assertThat(keysUnder(libraryPrefix)).hasSize(1);
    assertThat(accepted.workingFile()).as("the working file is gone after release").doesNotExist();

    Optional<DocumentContent> content =
        store.openForDownload(ref, "bescheid.pdf", "application/pdf");
    assertThat(content).isPresent();
    assertThat(content.get().isStreamed()).isTrue();
    try (InputStream stream = content.get().stream()) {
      assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("%PDF-1.4 bescheid");
    }
  }

  @Test
  void aLocalCopyIsRemovedAfterTheActionOnBothExits() throws IOException {
    UploadedOriginalRef ref = storedOriginal("Vermerk");

    assertThat(store.withLocalFile(ref, this::readString)).contains("Vermerk");
    assertThat(ownTempFiles()).isEmpty();

    assertThatThrownBy(
            () ->
                store.withLocalFile(
                    ref,
                    file -> {
                      throw new java.io.UncheckedIOException(new IOException("aus der Aktion"));
                    }))
        .isInstanceOf(java.io.UncheckedIOException.class);
    assertThat(ownTempFiles()).isEmpty();
    assertThat(exists(keyOf(ref))).isTrue();
  }

  @Test
  void deletingRemovesTheObjectAndDeletingAgainIsNotAnError() throws IOException {
    UploadedOriginalRef ref = storedOriginal("weg damit");
    String key = keyOf(ref);
    assertThat(exists(key)).isTrue();

    store.delete(ref);

    assertThat(exists(key)).isFalse();
    assertThatCode(() -> store.delete(ref)).doesNotThrowAnyException();
    assertThat(store.belongsToLibrary(ref)).isFalse();
    assertThat(store.openForDownload(ref, "x.pdf", null)).isEmpty();
  }

  @Test
  void aKeyOfAnotherLibraryIsNeitherReadNorDeleted() throws IOException {
    UploadedOriginalRef own = storedOriginal("fremdes Original");
    UploadedOriginalRef foreign =
        new UploadedOriginalRef(organizationId, UUID.randomUUID(), own.locator());

    assertThat(store.belongsToLibrary(foreign)).isFalse();
    assertThat(store.openForDownload(foreign, "x.pdf", null)).isEmpty();
    assertThat(store.withLocalFile(foreign, this::readString)).isEmpty();
    store.delete(foreign);

    assertThat(exists(keyOf(own))).as("the other library's original is untouched").isTrue();
  }

  @Test
  void aKeyOfAnotherOrganizationIsNeitherReadNorDeletedUnderTheSameLibraryId() throws IOException {
    // Everything but the organization segment matches: same library id, same object. Against a
    // real store too, that segment alone has to answer "not there" (ADR-0030, Nachtrag zu 4).
    UploadedOriginalRef own = storedOriginal("Original der eigenen Organisation");
    UploadedOriginalRef foreign =
        new UploadedOriginalRef(UUID.randomUUID(), libraryId, own.locator());

    assertThat(store.belongsToLibrary(foreign)).isFalse();
    assertThat(store.openForDownload(foreign, "x.pdf", null)).isEmpty();
    assertThat(store.withLocalFile(foreign, this::readString)).isEmpty();
    store.delete(foreign);

    assertThat(exists(keyOf(own))).as("the other organization's original is untouched").isTrue();
  }

  @Test
  void anAttachmentsSyntheticLocatorResolvesToNothingAndLeavesItsParentAlone() throws IOException {
    UploadedOriginalRef parent = storedOriginal("die Mail mit ihrer Anlage");
    UploadedOriginalRef attachment =
        new UploadedOriginalRef(organizationId, libraryId, parent.locator() + "/0/anlage.pdf");

    assertThat(store.belongsToLibrary(attachment)).isFalse();
    assertThat(store.openForDownload(attachment, "anlage.pdf", null)).isEmpty();
    assertThat(store.withLocalFile(attachment, this::readString)).isEmpty();
    store.delete(attachment);

    assertThat(exists(keyOf(parent))).isTrue();
  }

  @Test
  void aDiscardedUploadLeavesNoObjectAndNoWorkingFile() throws IOException {
    UploadedOriginalStore.AcceptedUpload accepted =
        store.accept(organizationId, libraryId, ".pdf", bytes("verworfen"));
    UploadedOriginalRef ref = accepted.store();

    accepted.discard();

    assertThat(exists(keyOf(ref))).isFalse();
    assertThat(accepted.workingFile()).doesNotExist();
  }

  @Test
  void theProbePassesAgainstTheRunningStore() {
    assertThatCode(store::probe).doesNotThrowAnyException();
    assertThat(new UploadStoreHealthIndicator(store).health().getStatus().getCode())
        .isEqualTo("UP");
  }

  @Test
  void listingWalksALibraryPageByPageAgainstTheRealStore() throws IOException {
    UUID library = UUID.randomUUID();
    UploadS3Properties properties =
        new UploadS3Properties(
            minio.endpoint().toString(),
            MinioFixture.REGION,
            bucket,
            "uploads/",
            true,
            minio.rootCredentials().accessKey(),
            minio.rootCredentials().secretKey(),
            tempDir,
            new UploadS3Properties.TargetValidation(true, List.of()));
    try (S3UploadedOriginalStore paging =
        new S3UploadedOriginalStore(
            properties,
            UploadS3TargetPolicy.of(properties),
            S3UploadedOriginalStore.REQUEST_TIMEOUT,
            S3UploadedOriginalStore.MAX_RETRIES,
            S3UploadedOriginalStore.RETRY_BACKOFF,
            3)) {
      List<String> own = new ArrayList<>();
      for (int i = 0; i < 7; i++) {
        UploadedOriginalStore.AcceptedUpload accepted =
            paging.accept(organizationId, library, ".pdf", bytes("Seite " + i));
        own.add(accepted.store().locator());
        accepted.release();
      }
      storedOriginal("eine andere Bibliothek");
      UploadedOriginalStore.AcceptedUpload otherTenant =
          paging.accept(UUID.randomUUID(), library, ".pdf", bytes("andere Organisation"));
      otherTenant.store();
      otherTenant.release();

      List<UploadedOriginalStore.StoredOriginal> visited = new ArrayList<>();
      paging.forEachStoredOriginal(organizationId, library, visited::add);

      assertThat(visited)
          .extracting(UploadedOriginalStore.StoredOriginal::locator)
          .containsExactlyInAnyOrderElementsOf(own);
      assertThat(visited)
          .allSatisfy(
              original -> {
                assertThat(original.size()).isEqualTo("Seite 0".length());
                assertThat(original.lastModified())
                    .isBetween(Instant.now().minusSeconds(120), Instant.now().plusSeconds(5));
              });
    }
  }

  private UploadedOriginalRef storedOriginal(String content) throws IOException {
    UploadedOriginalStore.AcceptedUpload accepted =
        store.accept(organizationId, libraryId, ".pdf", bytes(content));
    UploadedOriginalRef ref = accepted.store();
    accepted.release();
    return ref;
  }

  private static String keyOf(UploadedOriginalRef ref) {
    return ref.locator().substring(("s3://" + bucket + "/").length());
  }

  private static boolean exists(String key) {
    try {
      minio.admin().headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
      return true;
    } catch (NoSuchKeyException e) {
      return false;
    }
  }

  private static List<String> keysUnder(String prefix) {
    return minio
        .admin()
        .listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
        .contents()
        .stream()
        .map(S3Object::key)
        .toList();
  }

  private static List<Path> ownTempFiles() {
    try (Stream<Path> entries = Files.list(tempDir)) {
      return entries
          .filter(
              p -> p.getFileName().toString().startsWith(S3UploadedOriginalStore.TEMP_FILE_PREFIX))
          .toList();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static InputStream bytes(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  private String readString(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new AssertionError("The handed-out file must be readable", e);
    }
  }
}
