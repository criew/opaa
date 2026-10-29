package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.opaa.s3.S3AccessException;
import io.opaa.s3.S3Connection;
import io.opaa.s3.S3Credentials;
import io.opaa.security.TargetAddressValidator;
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

  private static S3Connection connection(String endpoint, boolean pathStyle) {
    return new S3Connection(URI.create(endpoint), "us-east-1", pathStyle, CREDENTIALS);
  }

  @Test
  void rejectsAPrivateEndpointWithoutAllowlistEntryBeforeTheFirstRequest() {
    S3ClientFactory factory =
        new S3ClientFactory(S3Properties.defaults(), new TargetAddressValidator(true, List.of()));

    assertThatThrownBy(
            () ->
                factory.create(
                    connection("http://10.0.0.5:9000", true), List.of(S3Scope.of("docs", ""))))
        .isInstanceOf(S3AccessException.TargetBlocked.class)
        .hasMessageContaining("10.0.0.5")
        .hasMessageContaining("OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST");
  }

  /** A blocked endpoint and one whose name does not resolve answer in the same words. */
  @Test
  void aBlockedAndAnUnresolvableEndpointAnswerAlike() {
    S3ClientFactory factory =
        new S3ClientFactory(S3Properties.defaults(), new TargetAddressValidator(true, List.of()));
    String unknown = "no-such-minio.invalid.opaa-test";

    Throwable blocked =
        catchThrowable(
            () ->
                factory.create(
                    connection("http://10.0.0.5:9000", true), List.of(S3Scope.of("docs", ""))));
    Throwable unresolved =
        catchThrowable(
            () ->
                factory.create(
                    connection("http://" + unknown + ":9000", true),
                    List.of(S3Scope.of("docs", ""))));

    assertThat(blocked).isInstanceOf(S3AccessException.TargetBlocked.class);
    assertThat(unresolved).isInstanceOf(S3AccessException.Unreachable.class);
    assertThat(unresolved.getMessage().replace(unknown, "<host>"))
        .isEqualTo(blocked.getMessage().replace("10.0.0.5", "<host>"));
  }

  @Test
  void anAllowlistedEndpointPasses() throws Exception {
    S3ClientFactory factory =
        new S3ClientFactory(
            S3Properties.defaults(), new TargetAddressValidator(true, List.of("10.0.0.5")));

    try (S3ObjectStore store =
        factory.create(connection("http://10.0.0.5:9000", true), List.of())) {
      assertThat(store).isNotNull();
      assertThat(store.meter().requests()).isZero();
    }
  }

  @Test
  void underVirtualHostAddressingEveryBucketHostIsCheckedToo() {
    // "minio.intern" is allowlisted, "docs.minio.intern" - the host the SDK actually contacts for
    // bucket "docs" - is not: it is checked, does not resolve, and the failure names it as
    // unreachable before any request - in the words and with the hint of a blocked range.
    S3ClientFactory factory =
        new S3ClientFactory(
            S3Properties.defaults(), new TargetAddressValidator(true, List.of("minio.intern")));

    assertThatThrownBy(
            () ->
                factory.create(
                    connection("https://minio.intern", false),
                    List.of(S3Scope.of("docs", "2025/"))))
        .isInstanceOf(S3AccessException.Unreachable.class)
        .hasMessageContaining("docs.minio.intern")
        .hasMessageContaining("nicht zugelassen oder nicht erreichbar")
        .hasMessageContaining("OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST");
  }

  @Test
  void anAllowlistEntryWithThePortAdmitsTheEndpointAndItsBucketHosts() throws Exception {
    S3ClientFactory factory =
        new S3ClientFactory(
            S3Properties.defaults(),
            new TargetAddressValidator(true, List.of("10.0.0.5:9000", "docs.minio.intern:443")));

    try (S3ObjectStore store =
        factory.create(connection("http://10.0.0.5:9000", true), List.of(S3Scope.of("docs", "")))) {
      assertThat(store).isNotNull();
    }
    // the bucket host is checked with the endpoint's port; it is listed, so nothing is resolved
    S3ClientFactory virtualHosts =
        new S3ClientFactory(
            S3Properties.defaults(),
            new TargetAddressValidator(true, List.of("minio.intern", "docs.minio.intern:443")));
    try (S3ObjectStore store =
        virtualHosts.create(
            connection("https://minio.intern", false), List.of(S3Scope.of("docs", "")))) {
      assertThat(store).isNotNull();
    }
    assertThatThrownBy(
            () ->
                new S3ClientFactory(
                        S3Properties.defaults(),
                        new TargetAddressValidator(true, List.of("10.0.0.5:9001")))
                    .create(connection("http://10.0.0.5:9000", true), List.of()))
        .isInstanceOf(S3AccessException.TargetBlocked.class);
  }

  @Test
  void underPathStyleAddressingOnlyTheEndpointIsChecked() throws Exception {
    S3ClientFactory factory =
        new S3ClientFactory(
            S3Properties.defaults(), new TargetAddressValidator(true, List.of("minio.intern")));

    try (S3ObjectStore store =
        factory.create(connection("https://minio.intern", true), List.of(S3Scope.of("docs", "")))) {
      assertThat(store).isNotNull();
    }
  }

  @Test
  void aRunClientCarriesTheConfiguredBudgetAndAProbeClientNone() throws Exception {
    S3Properties properties = new S3Properties(0, 0, null, null, null, 7, null, 0, 0);
    S3ClientFactory factory = new S3ClientFactory(properties, TargetAddressValidator.disabled());
    S3Connection connection = connection("http://localhost:1", true);

    try (AwsSdkS3ObjectStore run =
            (AwsSdkS3ObjectStore) factory.createForRun(connection, List.of());
        AwsSdkS3ObjectStore probe = (AwsSdkS3ObjectStore) factory.create(connection, List.of())) {
      assertThat(run.requestBudget()).isEqualTo(7);
      assertThat(probe.requestBudget()).isZero();
    }
  }
}
