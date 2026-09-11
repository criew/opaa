package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.sourceaccess.TargetAddressValidator;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;

/**
 * The upload storage's target check (ADR-0030, Entscheidung 8): the configured endpoint passes by
 * scheme, host and port without a lookup - which is what makes {@code http://minio:9000} in a
 * Compose network work without an allowlist entry - and everything else, a different port or scheme
 * on the same host included, goes through the validator.
 */
class UploadS3TargetPolicyTest {

  private static final TargetAddressValidator STRICT = new TargetAddressValidator(true, List.of());

  private static SdkHttpRequest request(String scheme, String host, int port) {
    SdkHttpRequest.Builder builder =
        SdkHttpRequest.builder()
            .protocol(scheme)
            .host(host)
            .method(SdkHttpMethod.HEAD)
            .encodedPath("/ablage");
    if (port > 0) {
      builder.port(port);
    }
    return builder.build();
  }

  @Test
  void theConfiguredEndpointPassesWithoutResolvingItsName() {
    // "minio" resolves nowhere on a developer machine; going through the validator would fail
    // with "Host unbekannt" - the endpoint must not go through it at all.
    UploadS3TargetPolicy policy =
        new UploadS3TargetPolicy(STRICT, URI.create("http://minio:9000"), "ablage", true);

    assertThatCode(() -> policy.validate(request("http", "minio", 9000)))
        .doesNotThrowAnyException();
    assertThatCode(() -> policy.validate(request("HTTP", "MINIO", 9000)))
        .doesNotThrowAnyException();
  }

  @Test
  void anotherPortOrSchemeOnTheSameHostIsNotTheEndpoint() {
    UploadS3TargetPolicy policy =
        new UploadS3TargetPolicy(STRICT, URI.create("http://minio:9000"), "ablage", true);

    assertThatThrownBy(() -> policy.validate(request("http", "minio", 9001)))
        .isInstanceOf(TargetAddressValidator.UnknownTargetHostException.class);
    assertThatThrownBy(() -> policy.validate(request("https", "minio", 9000)))
        .isInstanceOf(TargetAddressValidator.UnknownTargetHostException.class);
  }

  @Test
  void aStrayTargetIsRefusedByTheValidator() {
    UploadS3TargetPolicy policy =
        new UploadS3TargetPolicy(STRICT, URI.create("http://minio:9000"), "ablage", true);

    assertThatThrownBy(() -> policy.validate(request("http", "169.254.169.254", 80)))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class)
        .hasMessageContaining("169.254.169.254");
  }

  @Test
  void theSchemesDefaultPortCountsAsTheEndpointsPort() {
    UploadS3TargetPolicy policy =
        new UploadS3TargetPolicy(STRICT, URI.create("https://s3.example"), "ablage", true);

    assertThatCode(() -> policy.validate(request("https", "s3.example", 443)))
        .doesNotThrowAnyException();
    assertThatCode(() -> policy.validate(request("https", "s3.example", -1)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> policy.validate(request("https", "s3.example", 8443)))
        .isInstanceOf(TargetAddressValidator.UnknownTargetHostException.class);
  }

  @Test
  void underVirtualHostAddressingTheBucketsOwnHostNameIsTheEndpointToo() {
    UploadS3TargetPolicy virtualHost =
        new UploadS3TargetPolicy(STRICT, URI.create("https://s3.example"), "ablage", false);
    UploadS3TargetPolicy pathStyle =
        new UploadS3TargetPolicy(STRICT, URI.create("https://s3.example"), "ablage", true);

    assertThatCode(() -> virtualHost.validate(request("https", "ablage.s3.example", 443)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> pathStyle.validate(request("https", "ablage.s3.example", 443)))
        .isInstanceOf(TargetAddressValidator.UnknownTargetHostException.class);
  }

  @Test
  void anAllowlistedTargetPassesTheValidatorLikeAnywhereElse() {
    UploadS3TargetPolicy policy =
        new UploadS3TargetPolicy(
            new TargetAddressValidator(true, List.of("127.0.0.1")),
            URI.create("http://minio:9000"),
            "ablage",
            true);

    assertThatCode(() -> policy.validate(request("http", "127.0.0.1", 9000)))
        .doesNotThrowAnyException();
  }
}
