package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code opaa.upload.s3.*} (ADR-0030, Entscheidung 8 and 9): a missing required value names itself,
 * the credentials carry no format rule of their own and never print, and the defaults are the ones
 * an on-premises object store needs.
 */
class UploadS3PropertiesTest {

  private static UploadS3Properties properties(
      String endpoint, String bucket, String accessKey, String secretKey) {
    return new UploadS3Properties(
        endpoint, null, bucket, null, null, accessKey, secretKey, null, null);
  }

  @Test
  void everyMissingRequiredValueIsNamedWithItsEnvironmentVariable() {
    assertThatThrownBy(() -> properties(null, "b", "a", "s").requireComplete())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("opaa.upload.s3.endpoint")
        .hasMessageContaining("OPAA_UPLOAD_S3_ENDPOINT");
    assertThatThrownBy(() -> properties("http://minio:9000", " ", "a", "s").requireComplete())
        .hasMessageContaining("opaa.upload.s3.bucket")
        .hasMessageContaining("OPAA_UPLOAD_S3_BUCKET");
    assertThatThrownBy(() -> properties("http://minio:9000", "b", null, "s").requireComplete())
        .hasMessageContaining("opaa.upload.s3.access-key")
        .hasMessageContaining("OPAA_UPLOAD_S3_ACCESS_KEY");
    assertThatThrownBy(() -> properties("http://minio:9000", "b", "a", "").requireComplete())
        .hasMessageContaining("opaa.upload.s3.secret-key")
        .hasMessageContaining("OPAA_UPLOAD_S3_SECRET_KEY");
  }

  @Test
  void anEndpointThatIsNoEndpointIsRefusedByName() {
    assertThatThrownBy(() -> properties("minio:9000", "b", "a", "s").requireComplete())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("opaa.upload.s3.endpoint")
        .hasMessageContaining("http://");
    assertThatThrownBy(() -> properties("http://minio:9000/pfad", "b", "a", "s").requireComplete())
        .hasMessageContaining("Pfad");
  }

  @Test
  void aSecretWithAColonIsAcceptedUnlikeALibrarysStoredCredentials() {
    // The connector's S3Credentials refuse a colon because of its storage format; that rule does
    // not reach an environment value (ADR-0030, Entscheidung 8).
    UploadS3Properties properties = properties("http://minio:9000", "b", "ak:ey", "se:cr:et");

    assertThatCode(properties::requireComplete).doesNotThrowAnyException();
    assertThat(properties.accessKey()).isEqualTo("ak:ey");
    assertThat(properties.secretKey()).isEqualTo("se:cr:et");
  }

  @Test
  void theDefaultsFitAnOnPremisesStore() {
    UploadS3Properties properties = properties("HTTP://Minio:9000", "ablage", "a", "s");

    assertThat(properties.region()).isEqualTo("us-east-1");
    assertThat(properties.pathStyle()).isTrue();
    assertThat(properties.keyPrefix()).isEmpty();
    assertThat(properties.tempDirectory()).isEqualTo(Path.of(System.getProperty("java.io.tmpdir")));
    assertThat(properties.targetValidation().enabled()).isTrue();
    assertThat(properties.targetValidation().allowlist()).isEmpty();
    assertThat(properties.endpointUri()).isEqualTo(URI.create("http://minio:9000"));
  }

  @Test
  void anEmptyTempDirectoryFallsBackToTheJvmsOne() {
    UploadS3Properties properties =
        new UploadS3Properties(
            "http://minio:9000", "", "b", " uploads/ ", false, "a", "s", Path.of(""), null);

    assertThat(properties.tempDirectory()).isEqualTo(Path.of(System.getProperty("java.io.tmpdir")));
    assertThat(properties.keyPrefix()).isEqualTo("uploads/");
    assertThat(properties.pathStyle()).isFalse();
  }

  @Test
  void settingOnlyTheAllowlistKeepsTheValidationOn() {
    UploadS3Properties.TargetValidation validation =
        new UploadS3Properties.TargetValidation(null, List.of("minio"));

    assertThat(validation.enabled()).isTrue();
  }

  @Test
  void neitherKeyAppearsInTheStringForm() {
    UploadS3Properties properties =
        properties("http://minio:9000", "b", "AKIALEAKTEST", "hochgeheim-4711");

    assertThat(properties.toString())
        .doesNotContain("AKIALEAKTEST")
        .doesNotContain("hochgeheim-4711")
        .contains("minio:9000");
  }
}
