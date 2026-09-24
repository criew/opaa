package io.opaa.indexing.source.s3;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * One real S3-compatible object store per JVM (ADR-0027, Entscheidung 9 und Nachtrag 09/2026):
 * started lazily on first use, shared by every test class that needs one, stopped by Ryuk/JVM exit
 * - small enough for the regular {@code test} task, unlike Confluence Data Center. Seeding goes
 * through the SDK with the root credentials; restricted users are created over the store's
 * MinIO-compatible admin API, which takes the same IAM policy documents.
 */
public final class S3TestFixture {

  private static final Logger log = LoggerFactory.getLogger(S3TestFixture.class);

  /**
   * The pinned image. RustFS speaks the S3 API and the MinIO admin API of {@link
   * #createUser(String)}; the regex manager in {@code renovate.json5} reads the tag from the
   * comment below.
   */
  // renovate: datasource=docker depName=rustfs/rustfs
  public static final String IMAGE = "rustfs/rustfs:1.0.0";

  public static final String REGION = "us-east-1";

  private static final int PORT = 9000;
  private static final String ROOT_ACCESS_KEY = "opaa-test-root";
  private static final String ROOT_SECRET_KEY = "opaa-test-root-secret";

  private static S3TestFixture instance;

  private final GenericContainer<?> container;
  private final S3Client admin;
  private final HttpClient http = HttpClient.newHttpClient();
  private int userCounter;

  /** The shared instance, started on first call. */
  public static synchronized S3TestFixture get() {
    if (instance == null) {
      S3TestFixture fixture = new S3TestFixture();
      instance = fixture;
      Runtime.getRuntime().addShutdownHook(new Thread(fixture::stop));
    }
    return instance;
  }

  /** {@link #IMAGE} as a Testcontainers name. */
  private static DockerImageName imageName() {
    return DockerImageName.parse(IMAGE);
  }

  /** A container of {@link #IMAGE} with the root credentials of this fixture, not yet started. */
  private static GenericContainer<?> newContainer() {
    return new GenericContainer<>(imageName())
        .withExposedPorts(PORT)
        .withEnv("RUSTFS_ACCESS_KEY", ROOT_ACCESS_KEY)
        .withEnv("RUSTFS_SECRET_KEY", ROOT_SECRET_KEY)
        .withEnv("RUSTFS_VOLUMES", "/data")
        .waitingFor(Wait.forHttp("/health").forPort(PORT).forStatusCode(200))
        .withStartupTimeout(Duration.ofMinutes(2));
  }

  private S3TestFixture() {
    container = newContainer();
    log.info("Starting S3 test store {}", IMAGE);
    container.start();
    admin = clientFor(rootCredentials());
  }

  private S3Client clientFor(S3Credentials credentials) {
    return S3Client.builder()
        .endpointOverride(endpoint())
        .region(Region.of(REGION))
        .forcePathStyle(true)
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(credentials.accessKey(), credentials.secretKey())))
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
    return URI.create("http://" + container.getHost() + ":" + container.getMappedPort(PORT));
  }

  public S3Credentials rootCredentials() {
    return new S3Credentials(ROOT_ACCESS_KEY, ROOT_SECRET_KEY, null);
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
   * A user whose rights are exactly {@code policyJson} (an IAM-style policy document), created over
   * the store's admin API. Returns its static credentials.
   */
  public synchronized S3Credentials createUser(String policyJson) {
    String user = "opaa-test-" + (++userCounter);
    String password = "Passwort-" + UUID.randomUUID();
    String policy = "policy-" + user;
    adminCall("/add-canned-policy?name=" + policy, policyJson);
    adminCall(
        "/add-user?accessKey=" + user,
        "{\"secretKey\":\"" + password + "\",\"status\":\"enabled\"}");
    adminCall(
        "/set-user-or-group-policy?policyName="
            + policy
            + "&userOrGroup="
            + user
            + "&isGroup=false",
        "");
    return new S3Credentials(user, password, null);
  }

  /**
   * Sends {@code body} to an admin path under {@code /rustfs/admin/v3}, signed with SigV4 and the
   * root credentials. Unlike MinIO's own client the store takes the payloads in clear text, so no
   * further encoding is involved.
   */
  private void adminCall(String pathAndQuery, String body) {
    URI uri = URI.create(endpoint() + "/rustfs/admin/v3" + pathAndQuery);
    SdkHttpRequest unsigned =
        SdkHttpRequest.builder()
            .method(SdkHttpMethod.PUT)
            .uri(uri)
            .appendHeader("Content-Type", "application/json")
            .build();
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    SignedRequest signed =
        AwsV4HttpSigner.create()
            .sign(
                request ->
                    request
                        .identity(AwsBasicCredentials.create(ROOT_ACCESS_KEY, ROOT_SECRET_KEY))
                        .request(unsigned)
                        .payload(() -> new java.io.ByteArrayInputStream(payload))
                        .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "s3")
                        .putProperty(AwsV4HttpSigner.REGION_NAME, REGION));
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri).PUT(HttpRequest.BodyPublishers.ofByteArray(payload));
    signed
        .request()
        .headers()
        .forEach(
            (name, values) -> {
              if (!"Host".equalsIgnoreCase(name) && !"Content-Length".equalsIgnoreCase(name)) {
                values.forEach(value -> request.header(name, value));
              }
            });
    try {
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException(
            "admin call "
                + pathAndQuery
                + " failed ("
                + response.statusCode()
                + "): "
                + response.body());
      }
    } catch (IOException e) {
      throw new IllegalStateException("admin call " + pathAndQuery + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("admin call " + pathAndQuery + " interrupted", e);
    }
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
