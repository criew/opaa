package io.opaa.library;

import io.opaa.indexing.source.s3.S3RequestGuard;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import software.amazon.awssdk.http.SdkHttpRequest;

/**
 * The target check of the upload storage's client (ADR-0030, Entscheidung 8), after {@code
 * OidcAddressPolicy}: the configured endpoint is always allowed - by scheme, host <em>and</em>
 * port, so the exception releases exactly the one address the operator named and not the loopback
 * for every local service - and under virtual-host addressing the bucket's own host name on it.
 * Every other target passes the {@link TargetAddressValidator} built from {@code
 * opaa.upload.s3.target-validation}.
 */
final class UploadS3TargetPolicy implements S3RequestGuard.TargetPolicy {

  static final String ALLOWLIST_HINT =
      "Interne Adressen gibt der Betrieb über OPAA_UPLOAD_S3_TARGET_VALIDATION_ALLOWLIST frei.";

  private final TargetAddressValidator validator;
  private final String scheme;
  private final String endpointHost;
  private final String bucketHost;
  private final int port;

  UploadS3TargetPolicy(
      TargetAddressValidator validator, URI endpoint, String bucket, boolean pathStyle) {
    this.validator = validator;
    this.scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
    this.endpointHost = endpoint.getHost().toLowerCase(Locale.ROOT);
    this.bucketHost = pathStyle ? null : bucket.toLowerCase(Locale.ROOT) + "." + endpointHost;
    this.port = endpoint.getPort() < 0 ? defaultPort(scheme) : endpoint.getPort();
  }

  static UploadS3TargetPolicy of(UploadS3Properties properties) {
    UploadS3Properties.TargetValidation validation = properties.targetValidation();
    return new UploadS3TargetPolicy(
        new TargetAddressValidator(validation.enabled(), validation.allowlist()),
        properties.endpointUri(),
        properties.bucket(),
        properties.pathStyle());
  }

  @Override
  public void validate(SdkHttpRequest request) throws IOException {
    if (isConfiguredEndpoint(request)) {
      return;
    }
    validator.validateHost(request.host());
  }

  private boolean isConfiguredEndpoint(SdkHttpRequest request) {
    String host = request.host() == null ? "" : request.host().toLowerCase(Locale.ROOT);
    String requestScheme =
        request.protocol() == null ? "" : request.protocol().toLowerCase(Locale.ROOT);
    int requestPort = request.port() < 0 ? defaultPort(requestScheme) : request.port();
    return scheme.equals(requestScheme)
        && port == requestPort
        && (host.equals(endpointHost) || host.equals(bucketHost));
  }

  private static int defaultPort(String scheme) {
    return "https".equals(scheme) ? 443 : 80;
  }
}
