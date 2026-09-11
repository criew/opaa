package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reading one indexed object back out of a real object store (MinIO in a container, ADR-0027,
 * Entscheidung 5, #1524): the object body with its declared content type, the "no object of this
 * library" answers that become a 404 at the caller, and the store failures that must stay
 * distinguishable from them. Skipped without Docker; the CI runs it.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3OriginalAccessMinioTest {

  private static MinioFixture minio;
  private static String bucket;

  @TempDir Path downloadDirectory;

  @BeforeAll
  static void start() {
    minio = MinioFixture.get();
    bucket = minio.createBucket("opaa-beleg");
    minio.putObject(bucket, "2025/protokoll.pdf", "%PDF-1.4 Protokollinhalt", "application/pdf");
    minio.putObject(bucket, "2024/altes.pdf", "%PDF-1.4 alt", "application/pdf");
  }

  private KnowledgeLibrary library(String endpoint, S3Credentials credentials, S3Scope... scopes) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Belegsprung",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.S3,
            null,
            endpoint,
            null,
            credentials.accessKey() + ":" + credentials.secretKey(),
            false);
    library.updateS3Settings(
        new S3SourceSettings(MinioFixture.REGION, true, List.of(scopes), null, null));
    return library;
  }

  private KnowledgeLibrary library(S3Scope... scopes) {
    return library(minio.endpoint().toString(), minio.rootCredentials(), scopes);
  }

  /**
   * The access with the connector's own bounds, but its downloads under {@link #downloadDirectory}.
   */
  private S3OriginalAccess access(long maxObjectSizeBytes) {
    S3Properties properties =
        new S3Properties(0, maxObjectSizeBytes, null, null, null, 0, downloadDirectory, 0, 0);
    return new S3OriginalAccess(new S3ClientFactory(properties, TargetAddressValidator.disabled()));
  }

  private S3OriginalAccess access() {
    return access(0);
  }

  @Test
  void servesTheObjectBodyWithTheContentTypeTheStoreDeclares() throws Exception {
    Optional<S3Download> download =
        access()
            .download(
                library(S3Scope.of(bucket, "2025/")), "s3://" + bucket + "/2025/protokoll.pdf");

    assertThat(download).isPresent();
    assertThat(Files.readString(download.get().file(), StandardCharsets.UTF_8))
        .isEqualTo("%PDF-1.4 Protokollinhalt");
    assertThat(download.get().contentType()).isEqualTo("application/pdf");
    Files.delete(download.get().file());
  }

  @Test
  void answersEmptyForAnObjectThatHasSinceBeenDeleted() throws Exception {
    minio.putObject(bucket, "2025/vergaenglich.pdf", "%PDF-1.4 weg gleich", "application/pdf");
    minio.deleteObject(bucket, "2025/vergaenglich.pdf");

    Optional<S3Download> download =
        access()
            .download(
                library(S3Scope.of(bucket, "2025/")), "s3://" + bucket + "/2025/vergaenglich.pdf");

    assertThat(download).isEmpty();
  }

  @Test
  void answersEmptyForAKeyOutsideEveryConfiguredScope() throws Exception {
    // The scopes can be narrowed after indexing; a row left over from a wider configuration must
    // not stay readable through this path.
    Optional<S3Download> download =
        access()
            .download(library(S3Scope.of(bucket, "2025/")), "s3://" + bucket + "/2024/altes.pdf");

    assertThat(download).isEmpty();
  }

  @Test
  void answersEmptyForAFilePathThatIsNoObjectLocator() throws Exception {
    assertThat(access().download(library(S3Scope.of(bucket, "")), "/var/opaa/uploads/a.pdf"))
        .isEmpty();
    assertThat(access().download(library(S3Scope.of(bucket, "")), "s3://" + bucket)).isEmpty();
  }

  @Test
  void refusesAnObjectAboveTheSizeBoundWithoutLeavingAPartialFileBehind() throws Exception {
    minio.putObject(bucket, "2025/gross.pdf", "%PDF-1.4 " + "x".repeat(4096), "application/pdf");

    Optional<S3Download> download =
        access(64)
            .download(library(S3Scope.of(bucket, "2025/")), "s3://" + bucket + "/2025/gross.pdf");

    assertThat(download).isEmpty();
    assertThat(downloadDirectory).isEmptyDirectory();
  }

  @Test
  void anUnreachableStoreIsAFailureOfItsOwn() {
    // Port 1 is a privileged port nothing in this test listens on - "the store is offline",
    // deliberately NOT the empty answer a missing object gives.
    KnowledgeLibrary library =
        library("http://127.0.0.1:1", minio.rootCredentials(), S3Scope.of(bucket, "2025/"));

    assertThatThrownBy(() -> access().download(library, "s3://" + bucket + "/2025/protokoll.pdf"))
        .isInstanceOf(S3AccessException.class);
  }

  @Test
  void anEndpointTheTargetValidationRejectsIsRefusedBeforeAnyRequest() {
    // An allowlist narrowed after the library was created (or never opened for this host): the
    // endpoint is loopback, which an enabled validator always blocks.
    S3OriginalAccess blocking =
        new S3OriginalAccess(
            new S3ClientFactory(
                new S3Properties(0, 0, null, null, null, 0, downloadDirectory, 0, 0),
                new TargetAddressValidator(true, List.of())));
    KnowledgeLibrary library = library(S3Scope.of(bucket, "2025/"));

    assertThatThrownBy(() -> blocking.download(library, "s3://" + bucket + "/2025/protokoll.pdf"))
        .isInstanceOf(S3AccessException.TargetBlocked.class);
  }

  @Test
  void aKeyWithoutTheReadRightIsAFailureOfItsOwn() {
    S3Credentials listOnly =
        minio.createUser(
            MinioFixture.policyAllowing(bucket, "s3:ListBucket", "s3:GetBucketLocation"));
    KnowledgeLibrary library =
        library(minio.endpoint().toString(), listOnly, S3Scope.of(bucket, "2025/"));

    assertThatThrownBy(() -> access().download(library, "s3://" + bucket + "/2025/protokoll.pdf"))
        .isInstanceOf(S3AccessException.ReadForbidden.class);
  }
}
