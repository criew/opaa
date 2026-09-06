package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.s3.MinioFixture;
import io.opaa.indexing.source.s3.S3ClientFactory;
import io.opaa.indexing.source.s3.S3Credentials;
import io.opaa.indexing.source.s3.S3Properties;
import io.opaa.indexing.source.s3.S3Scope;
import io.opaa.indexing.source.s3.S3SourceSettings;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The connection test against a real MinIO (ADR-0027, #1376): per scope the three steps and the
 * count, the rights a restricted key lacks named by right, refused credentials named as such, and
 * the bucket listing - MinIO filters it for a restricted key rather than refusing it. Skipped
 * without Docker; the CI runs it.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3ConnectionServiceMinioTest {

  private static MinioFixture minio;
  private static String bucket;
  private static S3ConnectionService service;

  @BeforeAll
  static void start() throws Exception {
    minio = MinioFixture.get();
    bucket = minio.createBucket("opaa-sonde");
    minio.putObject(bucket, "2025/protokoll.pdf", "%PDF-1.4 protokoll", "application/pdf");
    minio.putObject(bucket, "2025/anlage.docx", "anlage", "application/octet-stream");
    minio.putMany(bucket, "viele/", 1100, null);
    service =
        new S3ConnectionService(
            new S3ClientFactory(S3Properties.defaults(), TargetAddressValidator.disabled()));
  }

  private static S3SourceSettings settings(S3Scope... scopes) {
    return new S3SourceSettings(null, true, List.of(scopes), null, null);
  }

  private static String endpoint() {
    return minio.endpoint().toString();
  }

  @Test
  void rootCredentialsPassEveryStepAndCountTheFirstPage() throws Exception {
    S3ConnectionService.Probe probe =
        service.probe(
            endpoint() + "/",
            null,
            minio.rootCredentials().stored(),
            false,
            settings(S3Scope.of(bucket, "2025/"), S3Scope.of(bucket, "viele")));

    assertThat(probe.reachable()).isTrue();
    assertThat(probe.credentialsVerified()).isTrue();
    assertThat(probe.message()).contains("Alle 2 Bereiche").contains("mindestens");
    assertThat(probe.objectCount()).isEqualTo(2 + 1000);
    assertThat(probe.scopes()).hasSize(2);
    S3ScopeCheck small = probe.scopes().get(0);
    assertThat(small.passed()).isTrue();
    assertThat(small.prefix()).isEqualTo("2025/");
    assertThat(small.readAllowed()).isTrue();
    assertThat(small.objectCount()).isEqualTo(2);
    assertThat(small.objectCountIsLowerBound()).isFalse();
    S3ScopeCheck many = probe.scopes().get(1);
    assertThat(many.objectCount()).isEqualTo(1000);
    assertThat(many.objectCountIsLowerBound()).isTrue();
  }

  @Test
  void aKeyThatMayListButNotReadIsToldWhichRightIsMissing() throws Exception {
    S3Credentials listOnly =
        minio.createUser(
            MinioFixture.policyAllowing(bucket, "s3:ListBucket", "s3:GetBucketLocation"));

    S3ConnectionService.Probe probe =
        service.probe(
            endpoint(), null, listOnly.stored(), false, settings(S3Scope.of(bucket, "2025/")));

    assertThat(probe.reachable()).isFalse();
    assertThat(probe.credentialsVerified()).isTrue();
    assertThat(probe.message()).contains(bucket + "/2025/").contains("s3:GetObject");
    S3ScopeCheck check = probe.scopes().get(0);
    assertThat(check.bucketReachable()).isTrue();
    assertThat(check.listAllowed()).isTrue();
    assertThat(check.readAllowed()).isFalse();
    assertThat(check.objectCount()).isEqualTo(2);
    assertThat(probe.objectCount()).isNull();
  }

  @Test
  void aKeyWithoutRightsOnTheBucketIsToldSoForAnExistingAndAMissingBucket() throws Exception {
    String other = minio.createBucket("opaa-fremd");
    S3Credentials otherOnly = minio.createUser(MinioFixture.policyAllowing(other, "s3:ListBucket"));

    S3ConnectionService.Probe probe =
        service.probe(
            endpoint(),
            null,
            otherOnly.stored(),
            false,
            settings(S3Scope.of(bucket, ""), S3Scope.of("opaa-gibtsnicht", "")));

    assertThat(probe.reachable()).isFalse();
    assertThat(probe.message()).contains("s3:ListBucket").contains("1 weitere Bereiche");
    assertThat(probe.scopes().get(0).listAllowed()).isFalse();
    // a restricted key cannot tell a missing bucket from a forbidden one - the store answers 403
    // either way, so the scope fails and its message names the bucket
    assertThat(probe.scopes().get(1).passed()).isFalse();
    assertThat(probe.scopes().get(1).listAllowed()).isFalse();
    assertThat(probe.scopes().get(1).message()).contains("opaa-gibtsnicht");
  }

  @Test
  void refusedCredentialsEndTheTestAsSuchWithoutEchoingTheSecret() throws Exception {
    S3ConnectionService.Probe probe =
        service.probe(
            endpoint(),
            null,
            "falscher-key:falsches-geheimnis",
            false,
            settings(S3Scope.of(bucket, "")));

    assertThat(probe.reachable()).isFalse();
    assertThat(probe.credentialsVerified()).isFalse();
    assertThat(probe.message())
        .contains("Zugangsdaten abgelehnt")
        .doesNotContain("falsches-geheimnis")
        .doesNotContain("falscher-key");
  }

  @Test
  void listsTheBucketsTheKeyMaySee() throws Exception {
    S3BucketListResult root =
        service.listBuckets(endpoint(), null, minio.rootCredentials().stored(), false, null, true);
    assertThat(root.permitted()).isTrue();
    assertThat(root.buckets()).contains(bucket);

    // MinIO filters the listing for a restricted key instead of refusing it
    S3Credentials restricted =
        minio.createUser(MinioFixture.policyAllowing(bucket, "s3:ListBucket"));
    S3BucketListResult filtered =
        service.listBuckets(endpoint(), null, restricted.stored(), false, null, true);
    assertThat(filtered.permitted()).isTrue();
    assertThat(filtered.buckets()).containsExactly(bucket);

    assertThatThrownBy(
            () -> service.listBuckets(endpoint(), null, "falsch:geheim-0815", false, null, true))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Zugangsdaten abgelehnt")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("geheim-0815"));
  }
}
