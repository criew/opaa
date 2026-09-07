package io.opaa.indexing.source.s3;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * One real MinIO per JVM (ADR-0027, Entscheidung 9): started lazily on first use, shared by every
 * test class that needs an S3-compatible store, stopped by Ryuk/JVM exit - small enough for the
 * regular {@code test} task, unlike Confluence Data Center. Seeding goes through the SDK with the
 * root credentials; restricted users are created with the {@code mc} client the image ships.
 */
public final class MinioFixture {

  private static final Logger log = LoggerFactory.getLogger(MinioFixture.class);

  /**
   * The pinned image; the community line of {@code minio/minio} ended with this release. Renovate
   * does not track this constant - bump it deliberately, together with the mc commands below.
   */
  public static final String IMAGE = "minio/minio:RELEASE.2025-09-07T16-13-09Z";

  public static final String REGION = "us-east-1";

  private static MinioFixture instance;

  private final MinIOContainer container;
  private final S3Client admin;
  private int userCounter;

  /** The shared instance, started on first call. */
  public static synchronized MinioFixture get() {
    if (instance == null) {
      MinioFixture fixture = new MinioFixture();
      instance = fixture;
      Runtime.getRuntime().addShutdownHook(new Thread(fixture::stop));
    }
    return instance;
  }

  private MinioFixture() {
    container = new MinIOContainer(DockerImageName.parse(IMAGE));
    log.info("Starting MinIO {}", IMAGE);
    container.start();
    admin =
        S3Client.builder()
            .endpointOverride(endpoint())
            .region(Region.of(REGION))
            .forcePathStyle(true)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(container.getUserName(), container.getPassword())))
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .build();
  }

  private void stop() {
    try {
      admin.close();
    } finally {
      container.stop();
    }
  }

  public URI endpoint() {
    return URI.create(container.getS3URL());
  }

  public S3Credentials rootCredentials() {
    return new S3Credentials(container.getUserName(), container.getPassword(), null);
  }

  /** A path-style connection without proxy or relaxed TLS for {@code credentials}. */
  public S3Connection connection(S3Credentials credentials) {
    return new S3Connection(endpoint(), REGION, true, credentials, null, -1, false);
  }

  /** The SDK client with root credentials, for seeding and assertions. */
  public S3Client admin() {
    return admin;
  }

  /** A fresh bucket with a unique name. */
  public String createBucket(String prefix) {
    String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    admin.createBucket(CreateBucketRequest.builder().bucket(name).build());
    return name;
  }

  public void putObject(String bucket, String key, byte[] bytes, String contentType) {
    admin.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
        RequestBody.fromBytes(bytes));
  }

  public void putObject(String bucket, String key, String text, String contentType) {
    putObject(bucket, key, text.getBytes(StandardCharsets.UTF_8), contentType);
  }

  public void deleteObject(String bucket, String key) {
    admin.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
  }

  /** Puts {@code count} small objects {@code prefix + i} in parallel, for pagination tests. */
  public void putMany(String bucket, String prefix, int count, Consumer<Integer> progress)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<?>> futures = new java.util.ArrayList<>();
      for (int i = 0; i < count; i++) {
        int n = i;
        futures.add(
            pool.submit(
                () -> {
                  putObject(
                      bucket, prefix + String.format("%05d", n) + ".txt", "o" + n, "text/plain");
                  if (progress != null) {
                    progress.accept(n);
                  }
                }));
      }
      for (Future<?> future : futures) {
        future.get();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * A MinIO user whose rights are exactly {@code policyJson} (an IAM-style policy document),
   * created with {@code mc} inside the container. Returns its static credentials.
   */
  public synchronized S3Credentials createUser(String policyJson) {
    String user = "opaa-test-" + (++userCounter);
    String password = "Passwort-" + UUID.randomUUID();
    String policy = "policy-" + user;
    // No heredoc: the terminator would have to stand alone on its line, and the policy JSON
    // contains no single quote, so a quoted printf is the safe way to write the file.
    String script =
        String.join(
            " && ",
            "mc alias set local http://127.0.0.1:9000 '"
                + container.getUserName()
                + "' '"
                + container.getPassword()
                + "' >/dev/null",
            "printf '%s' '" + policyJson + "' > /tmp/" + policy + ".json",
            "mc admin policy create local " + policy + " /tmp/" + policy + ".json >/dev/null",
            "mc admin user add local " + user + " '" + password + "' >/dev/null",
            "mc admin policy attach local " + policy + " --user " + user + " >/dev/null");
    try {
      Container.ExecResult result = container.execInContainer("sh", "-c", script);
      if (result.getExitCode() != 0) {
        throw new IllegalStateException(
            "mc failed (" + result.getExitCode() + "): " + result.getStderr());
      }
    } catch (Exception e) {
      throw new IllegalStateException("could not create a restricted MinIO user", e);
    }
    return new S3Credentials(user, password, null);
  }

  /** A policy that allows exactly {@code actions} on {@code bucket} and its objects. */
  public static String policyAllowing(String bucket, String... actions) {
    StringBuilder list = new StringBuilder();
    for (String action : actions) {
      if (list.length() > 0) {
        list.append(',');
      }
      list.append('"').append(action).append('"');
    }
    return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":["
        + list
        + "],\"Resource\":[\"arn:aws:s3:::"
        + bucket
        + "\",\"arn:aws:s3:::"
        + bucket
        + "/*\"]}]}";
  }
}
