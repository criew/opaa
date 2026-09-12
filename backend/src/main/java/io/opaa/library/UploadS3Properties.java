package io.opaa.library;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The S3 storage of uploaded originals, {@code opaa.upload.s3.*} (ADR-0030, Entscheidung 8 and 9):
 * bound whenever the application starts, but only read - and only then required to be complete
 * ({@link #requireComplete}) - when {@code opaa.upload.store=s3} selects the adapter. Access key
 * and secret are two separate values without any format rule of their own, and neither appears in a
 * log line or an exception message.
 *
 * @param endpoint the object store's address, {@code http}/{@code https} with host and port
 * @param region the signing region; blank falls back to the SDK-neutral {@code us-east-1}
 * @param bucket the one bucket every original lies in
 * @param keyPrefix prepended to every key, empty by default; {@code
 *     <keyPrefix><organizationId>/<libraryId>/<uuid>} mirrors the directory layout of the
 *     filesystem adapter (ADR-0030, Entscheidung 4 and its addendum)
 * @param pathStyle {@code endpoint/bucket/key} when {@code true} (MinIO, Ceph, the default), {@code
 *     bucket.endpoint/key} otherwise (AWS, Hetzner)
 * @param tempDirectory where working files and local copies are written; {@code null} means the
 *     JVM's temporary directory
 * @param targetValidation the SSRF check of every request's target, in its own namespace so
 *     switching the connectors' check off never switches this one off (ADR-0030, Entscheidung 8);
 *     the configured endpoint itself always passes
 */
@ConfigurationProperties(prefix = "opaa.upload.s3")
public record UploadS3Properties(
    String endpoint,
    String region,
    String bucket,
    String keyPrefix,
    Boolean pathStyle,
    String accessKey,
    String secretKey,
    Path tempDirectory,
    TargetValidation targetValidation) {

  public static final String DEFAULT_REGION = "us-east-1";

  public UploadS3Properties {
    endpoint = blankToNull(endpoint);
    region = blankToNull(region) == null ? DEFAULT_REGION : region.strip();
    bucket = blankToNull(bucket);
    keyPrefix = keyPrefix == null ? "" : keyPrefix.strip();
    if (pathStyle == null) {
      pathStyle = true;
    }
    accessKey = blankToNull(accessKey);
    secretKey = blankToNull(secretKey);
    if (tempDirectory == null || tempDirectory.toString().isBlank()) {
      tempDirectory = Path.of(System.getProperty("java.io.tmpdir"));
    }
    if (targetValidation == null) {
      targetValidation = new TargetValidation(true, List.of());
    }
  }

  /**
   * The endpoint as a URI, normalised like a library's endpoint: scheme and host lower-case, no
   * path, query, fragment or user info.
   *
   * @throws IllegalStateException naming the property when the value is not an endpoint
   */
  public URI endpointUri() {
    try {
      return io.opaa.indexing.source.s3.S3Connection.normalizeEndpoint(endpoint);
    } catch (io.opaa.indexing.source.s3.S3Connection.InvalidEndpointException e) {
      throw new IllegalStateException(
          "opaa.upload.s3.endpoint (OPAA_UPLOAD_S3_ENDPOINT) is not a usable endpoint: "
              + e.getMessage(),
          e);
    }
  }

  /**
   * Refuses the start when a value the adapter cannot do without is missing, naming it - the stance
   * of {@code AuthProfileGuard} (ADR-0005): a misconfigured storage is found out at startup, not at
   * the first upload.
   */
  public void requireComplete() {
    require(endpoint, "endpoint");
    require(bucket, "bucket");
    require(accessKey, "access-key");
    require(secretKey, "secret-key");
    endpointUri();
  }

  private static void require(String value, String property) {
    if (value == null) {
      throw new IllegalStateException(
          "opaa.upload.store=s3 needs opaa.upload.s3."
              + property
              + " (OPAA_UPLOAD_S3_"
              + property.toUpperCase(Locale.ROOT).replace('-', '_')
              + "). See docs/handbuch/deployment.md.");
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  /** Never prints the credentials - the record's generated form would. */
  @Override
  public String toString() {
    return "UploadS3Properties[endpoint="
        + endpoint
        + ", region="
        + region
        + ", bucket="
        + bucket
        + ", keyPrefix="
        + keyPrefix
        + ", pathStyle="
        + pathStyle
        + ", tempDirectory="
        + tempDirectory
        + ", targetValidation="
        + targetValidation
        + "]";
  }

  /**
   * {@code opaa.upload.s3.target-validation}. {@code enabled} is a boxed {@link Boolean} so an
   * operator who sets only the allowlist does not silently switch the check off; {@code null} means
   * enabled.
   */
  public record TargetValidation(Boolean enabled, List<String> allowlist) {

    public TargetValidation {
      if (enabled == null) {
        enabled = true;
      }
      if (allowlist == null) {
        allowlist = List.of();
      }
    }
  }
}
