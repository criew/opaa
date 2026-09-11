package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3ServiceClientConfiguration;

/**
 * The shared client build (ADR-0030, Entscheidung 8) applies what was learned about S3-compatible
 * stores to every client it builds: checksums only where required, the endpoint and region as
 * given, the attempt timeout, the retry count, and the guard as interceptor. Building sends
 * nothing; the configuration is read back from the client.
 */
class S3SdkClientTest {

  private static S3ClientSettings settings(int maxRetries) {
    return new S3ClientSettings(
        URI.create("http://minio.intern:9000"),
        "eu-central-1",
        true,
        AwsBasicCredentials.create("AKIA", "geheim:mit:doppelpunkt"),
        null,
        0,
        false,
        Duration.ofSeconds(9),
        maxRetries,
        Duration.ofMillis(100));
  }

  @Test
  void everyClientCarriesTheCompatibilitySettings() {
    S3RequestGuard guard = S3RequestGuard.targetCheckOnly(request -> {});

    try (S3SdkClient client = S3SdkClient.open(settings(4), guard)) {
      S3ServiceClientConfiguration configuration = client.s3().serviceClientConfiguration();

      assertThat(configuration.requestChecksumCalculation())
          .isEqualTo(RequestChecksumCalculation.WHEN_REQUIRED);
      assertThat(configuration.responseChecksumValidation())
          .isEqualTo(ResponseChecksumValidation.WHEN_REQUIRED);
      assertThat(configuration.endpointOverride()).contains(URI.create("http://minio.intern:9000"));
      assertThat(configuration.region()).isEqualTo(Region.of("eu-central-1"));
      assertThat(configuration.overrideConfiguration().apiCallAttemptTimeout())
          .contains(Duration.ofSeconds(9));
      assertThat(configuration.overrideConfiguration().retryStrategy())
          .isPresent()
          .get()
          .satisfies(strategy -> assertThat(strategy.maxAttempts()).isEqualTo(5));
      assertThat(configuration.overrideConfiguration().executionInterceptors()).contains(guard);
    }
  }

  @Test
  void zeroRetriesMeansASingleAttempt() {
    try (S3SdkClient client =
        S3SdkClient.open(settings(0), S3RequestGuard.targetCheckOnly(request -> {}))) {
      assertThat(client.s3().serviceClientConfiguration().overrideConfiguration().retryStrategy())
          .get()
          .satisfies(strategy -> assertThat(strategy.maxAttempts()).isEqualTo(1));
    }
  }

  @Test
  void theSettingsRefuseWhatNoClientCanBeBuiltFromAndNeverPrintTheSecret() {
    assertThatThrownBy(
            () ->
                new S3ClientSettings(
                    URI.create("http://minio:9000"),
                    null,
                    true,
                    AwsBasicCredentials.create("AKIA", "geheim"),
                    "proxy",
                    0,
                    false,
                    Duration.ofSeconds(1),
                    1,
                    Duration.ofMillis(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("proxy");

    S3ClientSettings settings = settings(1);
    assertThat(settings.region()).isEqualTo("eu-central-1");
    assertThat(settings.toString()).doesNotContain("geheim").doesNotContain("AKIA");
    assertThat(
            new S3ClientSettings(
                    URI.create("http://minio:9000"),
                    " ",
                    true,
                    AwsBasicCredentials.create("AKIA", "geheim"),
                    null,
                    0,
                    false,
                    Duration.ofSeconds(1),
                    1,
                    Duration.ofMillis(1))
                .region())
        .isEqualTo(S3Connection.DEFAULT_REGION);
  }

  @Test
  void aLibraryConnectionMapsOntoTheSettingsWithItsCredentialsAndBounds() {
    S3Connection connection =
        new S3Connection(
            URI.create("https://s3.example"),
            "eu-west-1",
            false,
            new S3Credentials("AKIA", "geheim", "token"),
            "proxy.intern",
            3128,
            true);
    S3Properties properties =
        new S3Properties(0, 0, Duration.ofSeconds(11), 4, Duration.ofMillis(250), 0, null, 0, 0);

    S3ClientSettings settings = S3ClientSettings.of(connection, properties);

    assertThat(settings.endpoint()).isEqualTo(URI.create("https://s3.example"));
    assertThat(settings.region()).isEqualTo("eu-west-1");
    assertThat(settings.pathStyle()).isFalse();
    assertThat(settings.credentials().accessKeyId()).isEqualTo("AKIA");
    assertThat(settings.credentials().secretAccessKey()).isEqualTo("geheim");
    assertThat(settings.credentials())
        .isInstanceOf(software.amazon.awssdk.auth.credentials.AwsSessionCredentials.class);
    assertThat(settings.proxyHost()).isEqualTo("proxy.intern");
    assertThat(settings.proxyPort()).isEqualTo(3128);
    assertThat(settings.insecureSsl()).isTrue();
    assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(11));
    assertThat(settings.maxRetries()).isEqualTo(4);
    assertThat(settings.retryBackoff()).isEqualTo(Duration.ofMillis(250));
  }
}
