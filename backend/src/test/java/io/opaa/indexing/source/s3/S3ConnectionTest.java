package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

/** {@link S3Connection}: the endpoint is normalised like every other source address. */
class S3ConnectionTest {

  private static final S3Credentials CREDENTIALS = S3Credentials.parse("AKIA:geheim");

  @Test
  void normalizesTheEndpoint() {
    assertThat(S3Connection.normalizeEndpoint("HTTPS://Minio.Intern.example:9000/"))
        .isEqualTo(URI.create("https://minio.intern.example:9000"));
    assertThat(S3Connection.normalizeEndpoint(" http://localhost:9000 "))
        .isEqualTo(URI.create("http://localhost:9000"));
    assertThat(S3Connection.normalizeEndpoint("https://s3.eu-central-1.amazonaws.com"))
        .isEqualTo(URI.create("https://s3.eu-central-1.amazonaws.com"));
  }

  @Test
  void rejectsAddressesThatAreNoEndpoint() {
    assertThatThrownBy(() -> S3Connection.normalizeEndpoint(""))
        .isInstanceOf(S3Connection.InvalidEndpointException.class)
        .hasMessageContaining("Endpoint");
    assertThatThrownBy(() -> S3Connection.normalizeEndpoint("minio.intern:9000"))
        .isInstanceOf(S3Connection.InvalidEndpointException.class)
        .hasMessageContaining("http://");
    assertThatThrownBy(() -> S3Connection.normalizeEndpoint("ftp://minio.intern"))
        .isInstanceOf(S3Connection.InvalidEndpointException.class);
    assertThatThrownBy(() -> S3Connection.normalizeEndpoint("https://AKIA:geheim@minio.intern"))
        .isInstanceOf(S3Connection.InvalidEndpointException.class)
        .hasMessageContaining("Zugangsdaten");
    assertThatThrownBy(() -> S3Connection.normalizeEndpoint("https://minio.intern/pfad"))
        .isInstanceOf(S3Connection.InvalidEndpointException.class)
        .hasMessageContaining("Pfad");
    assertThatThrownBy(() -> S3Connection.normalizeEndpoint("https://minio.intern?x=1"))
        .isInstanceOf(S3Connection.InvalidEndpointException.class);
  }

  @Test
  void defaultsTheRegionAndNeverPrintsTheCredentials() {
    S3Connection connection =
        new S3Connection(
            URI.create("https://minio.intern:9000"), " ", true, CREDENTIALS, "proxy", 3128, false);

    assertThat(connection.region()).isEqualTo(S3Connection.DEFAULT_REGION);
    assertThat(connection.toString()).doesNotContain("geheim").contains("minio.intern:9000");
  }

  @Test
  void aProxyNeedsAUsablePort() {
    assertThatThrownBy(
            () ->
                new S3Connection(
                    URI.create("https://minio.intern"),
                    "us-east-1",
                    true,
                    CREDENTIALS,
                    "proxy",
                    -1,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("port");
  }

  @Test
  void requiresCredentials() {
    assertThatThrownBy(
            () ->
                new S3Connection(
                    URI.create("https://minio.intern"), "us-east-1", true, null, null, -1, false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
