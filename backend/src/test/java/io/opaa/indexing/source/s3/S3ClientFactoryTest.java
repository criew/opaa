package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.sourceaccess.TargetAddressValidator;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link S3ClientFactory} (ADR-0027, Entscheidung 8): the endpoint, the proxy and - under
 * virtual-host addressing - every bucket host are checked by {@link TargetAddressValidator} before
 * the first request leaves, and a rejection names the allowlist variable. No server is involved:
 * building a client sends nothing.
 */
class S3ClientFactoryTest {

  private static final S3Credentials CREDENTIALS = S3Credentials.parse("AKIA:geheim");

  private static S3Connection connection(String endpoint, boolean pathStyle, String proxyHost) {
    return new S3Connection(
        URI.create(endpoint), "us-east-1", pathStyle, CREDENTIALS, proxyHost, 3128, false);
  }

  @Test
  void rejectsAPrivateEndpointWithoutAllowlistEntryBeforeTheFirstRequest() {
    S3ClientFactory factory =
        new S3ClientFactory(S3Properties.defaults(), new TargetAddressValidator(true, List.of()));

    assertThatThrownBy(
            () ->
                factory.create(
                    connection("http://10.0.0.5:9000", true, null),
                    List.of(S3Scope.of("docs", ""))))
        .isInstanceOf(S3AccessException.TargetBlocked.class)
        .hasMessageContaining("10.0.0.5")
        .hasMessageContaining("OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST");
  }

  @Test
  void anAllowlistedEndpointPasses() throws Exception {
    S3ClientFactory factory =
        new S3ClientFactory(
            S3Properties.defaults(), new TargetAddressValidator(true, List.of("10.0.0.5")));

    try (S3ObjectStore store =
        factory.create(connection("http://10.0.0.5:9000", true, null), List.of())) {
      assertThat(store).isNotNull();
      assertThat(store.meter().requests()).isZero();
    }
  }

  @Test
  void rejectsAPrivateProxy() {
    S3ClientFactory factory =
        new S3ClientFactory(
            S3Properties.defaults(), new TargetAddressValidator(true, List.of("10.0.0.5")));

    assertThatThrownBy(
            () ->
                factory.create(
                    connection("http://10.0.0.5:9000", true, "10.0.0.9"),
                    List.of(S3Scope.of("docs", ""))))
        .isInstanceOf(S3AccessException.TargetBlocked.class)
        .hasMessageContaining("10.0.0.9")
        .hasMessageContaining("OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST");
  }

  @Test
  void underVirtualHostAddressingEveryBucketHostIsCheckedToo() {
    // "minio.intern" is allowlisted, "docs.minio.intern" - the host the SDK actually contacts for
    // bucket "docs" - is not and does not resolve, so the check fails before any request.
    S3ClientFactory factory =
        new S3ClientFactory(
            S3Properties.defaults(), new TargetAddressValidator(true, List.of("minio.intern")));

    assertThatThrownBy(
            () ->
                factory.create(
                    connection("https://minio.intern", false, null),
                    List.of(S3Scope.of("docs", "2025/"))))
        .isInstanceOf(S3AccessException.TargetBlocked.class)
        .hasMessageContaining("docs.minio.intern")
        .hasMessageContaining("OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST");
  }

  @Test
  void underPathStyleAddressingOnlyTheEndpointIsChecked() throws Exception {
    S3ClientFactory factory =
        new S3ClientFactory(
            S3Properties.defaults(), new TargetAddressValidator(true, List.of("minio.intern")));

    try (S3ObjectStore store =
        factory.create(
            connection("https://minio.intern", true, null), List.of(S3Scope.of("docs", "")))) {
      assertThat(store).isNotNull();
    }
  }

  @Test
  void aRunClientCarriesTheConfiguredBudgetAndAProbeClientNone() throws Exception {
    S3Properties properties = new S3Properties(0, 0, null, 0, null, 7);
    S3ClientFactory factory = new S3ClientFactory(properties, TargetAddressValidator.disabled());
    S3Connection connection = connection("http://localhost:1", true, null);

    try (AwsSdkS3ObjectStore run =
            (AwsSdkS3ObjectStore) factory.createForRun(connection, List.of());
        AwsSdkS3ObjectStore probe = (AwsSdkS3ObjectStore) factory.create(connection, List.of())) {
      assertThat(run.requestBudget()).isEqualTo(7);
      assertThat(probe.requestBudget()).isZero();
    }
  }
}
