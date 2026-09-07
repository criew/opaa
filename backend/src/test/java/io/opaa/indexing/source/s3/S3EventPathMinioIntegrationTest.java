package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobRepository;
import io.opaa.indexing.JobStatus;
import io.opaa.indexing.JobTriggerSource;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIndexingIntegrationTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
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
 * The event path against MinIO's real webhook (ADR-0027, Entscheidung 6; #1382): a dedicated MinIO
 * container notifies the running test backend through the sshd port forward Testcontainers opens
 * for the backend's random port, so the bytes on the wire are what a real store sends - MinIO's
 * {@code Authorization: Bearer <auth_token>}, its {@code Records} envelope, its URL-encoded keys.
 * The whole chain runs for real: the security chain of the intake, the token check, the debounce,
 * the EVENT run with its {@code HeadObject} finding, the document path. Skipped without Docker; the
 * CI runs it. Budget: one extra MinIO container (~3 s), one sshd sidecar, and two debounce waits (5
 * s each) - under 30 s. The webhook target is configured through MinIO's environment at start, the
 * way a Compose deployment would do it.
 *
 * <p>The context forks on the target-validation allowlist: the real executor bean must be allowed
 * to reach the container on the Docker host address, which the shared context refuses by design.
 * The address is only known at runtime (loopback on a plain host, the bridge gateway inside a
 * container), hence the {@code @DynamicPropertySource}.
 */
@Testcontainers(disabledWithoutDocker = true)
@OpaaIndexingIntegrationTest
class S3EventPathMinioIntegrationTest {

  private static final String TOKEN = "ereignis-token-fuer-den-test";

  @DynamicPropertySource
  static void allowTheDockerHost(DynamicPropertyRegistry registry) {
    registry.add(
        "opaa.indexing.target-validation.allowlist",
        () -> DockerClientFactory.instance().dockerHostIpAddress());
  }

  @Autowired private Environment environment;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private VectorChunkStore vectorChunkStore;
  @Autowired private JdbcTemplate jdbcTemplate;

  private MinIOContainer minio;
  private S3Client admin;
  private UUID userId;
  private KnowledgeLibrary library;
  private String bucket;

  @BeforeEach
  void setUp() throws Exception {
    int serverPort = environment.getRequiredProperty("local.server.port", Integer.class);
    // must precede the container start: the sidecar that forwards the port is attached at start
    org.testcontainers.Testcontainers.exposeHostPorts(serverPort);

    // The webhook target is part of MinIO's start-up configuration (no restart, which needs a
    // TTY), and it carries the library id - so the library row exists first, with a placeholder
    // endpoint that the container's real address replaces once it runs.
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'S3 Event IT', now(), ?, ?)",
        userId,
        "s3-event-it-" + userId,
        "s3-event-it-" + userId + "@example.com",
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    bucket = "opaa-ereignisse";
    KnowledgeLibrary fresh =
        KnowledgeLibrary.ownedByUser(
            Organization.DEFAULT_ID,
            "Ereignisse",
            null,
            userId,
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.S3,
            null,
            "http://localhost:1",
            null,
            "platzhalter:platzhalter",
            false);
    fresh.updateS3Settings(
        new S3SourceSettings(
            MinioFixture.REGION, true, List.of(S3Scope.of(bucket, "")), null, null));
    fresh.setWebhookSecret(TOKEN);
    library = libraryRepository.save(fresh);

    String endpoint =
        "http://host.testcontainers.internal:"
            + serverPort
            + "/api/v1/libraries/"
            + library.getId()
            + "/s3-events";
    minio =
        new MinIOContainer(DockerImageName.parse(MinioFixture.IMAGE))
            .withEnv("MINIO_NOTIFY_WEBHOOK_ENABLE_OPAA", "on")
            .withEnv("MINIO_NOTIFY_WEBHOOK_ENDPOINT_OPAA", endpoint)
            .withEnv("MINIO_NOTIFY_WEBHOOK_AUTH_TOKEN_OPAA", TOKEN);
    minio.start();
    admin =
        S3Client.builder()
            .endpointOverride(URI.create(minio.getS3URL()))
            .region(Region.of(MinioFixture.REGION))
            .forcePathStyle(true)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .build();
    admin.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    admin.createBucket(CreateBucketRequest.builder().bucket("opaa-fremd").build());
    mc("mc event add local/" + bucket + " arn:minio:sqs::OPAA:webhook --event put,delete");
    mc("mc event add local/opaa-fremd arn:minio:sqs::OPAA:webhook --event put,delete");

    library.updateSourceConfiguration(
        null, minio.getS3URL(), null, minio.getUserName() + ":" + minio.getPassword(), false);
    library = libraryRepository.save(library);
  }

  @AfterEach
  void tearDown() {
    List<Document> documents =
        documentRepository.findByLibraryIdAndSourceType(library.getId(), DocumentSourceType.S3);
    documents.stream()
        .sorted(Comparator.comparingInt((Document d) -> d.getFilePath().length()).reversed())
        .forEach(
            document -> {
              vectorChunkStore.deleteByDocumentId(document.getId());
              documentRepository.delete(document);
            });
    jdbcTemplate.update(
        "DELETE FROM indexing_run_events WHERE job_id IN"
            + " (SELECT id FROM indexing_jobs WHERE library_id = ?)",
        library.getId());
    jdbcTemplate.update("DELETE FROM indexing_jobs WHERE library_id = ?", library.getId());
    libraryRepository.deleteById(library.getId());
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    admin.close();
    minio.stop();
  }

  private void mc(String command) throws Exception {
    String script =
        "mc alias set local http://127.0.0.1:9000 '"
            + minio.getUserName()
            + "' '"
            + minio.getPassword()
            + "' >/dev/null && "
            + command;
    Container.ExecResult result = minio.execInContainer("sh", "-c", script);
    assertThat(result.getExitCode())
        .as("%s: %s%s", command, result.getStdout(), result.getStderr())
        .isZero();
  }

  private void put(String bucketName, String key, String text) {
    admin.putObject(
        PutObjectRequest.builder().bucket(bucketName).key(key).contentType("text/plain").build(),
        RequestBody.fromBytes(text.getBytes(StandardCharsets.UTF_8)));
  }

  private java.util.Optional<Document> documentAt(String key) {
    return documentRepository.findByLibraryIdAndFilePath(
        library.getId(), S3FullSync.filePath(bucket, key));
  }

  private long runsOfLibrary() {
    return indexingJobRepository.findAll().stream()
        .filter(job -> library.getId().equals(job.getLibraryId()))
        .count();
  }

  @Test
  void aCreatedObjectIsIndexedAndARemovedOneRemovedThroughMinIOsOwnWebhook() {
    // Assurance: MinIO's notification reaches the intake with its Bearer auth_token and Records
    // body, is debounced into one EVENT run with trigger WEBHOOK, and the run indexes exactly the
    // reported object; the removal event leads to a HeadObject 404 that removes the document.
    put(bucket, "eingang/Sitzung 1.txt", "Protokoll der ersten Sitzung.");

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(
            () ->
                assertThat(documentAt("eingang/Sitzung 1.txt"))
                    .hasValueSatisfying(
                        document ->
                            assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED)));
    List<IndexingJob> runs =
        indexingJobRepository.findAll().stream()
            .filter(job -> library.getId().equals(job.getLibraryId()))
            .toList();
    assertThat(runs).hasSize(1);
    IndexingJob eventRun = runs.getFirst();
    assertThat(eventRun.getRunMode()).isEqualTo(IndexingRunMode.EVENT);
    assertThat(eventRun.getTriggeredBy()).isEqualTo(JobTriggerSource.WEBHOOK);
    assertThat(eventRun.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(eventRun.getDocumentsProcessed()).isEqualTo(1);
    assertThat(eventRun.getListingComplete())
        .as("an event run never assesses the listing")
        .isNull();

    admin.deleteObject(
        DeleteObjectRequest.builder().bucket(bucket).key("eingang/Sitzung 1.txt").build());

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> assertThat(documentAt("eingang/Sitzung 1.txt")).isEmpty());
    assertThat(runsOfLibrary()).isEqualTo(2);
  }

  @Test
  void anEventForABucketOutsideTheScopesStartsNoRun() {
    // Assurance: a notification for a bucket the library does not cover is authenticated, dropped
    // and never turned into a run - MinIO delivers it, OPAA counts it away.
    long before = runsOfLibrary();
    put("opaa-fremd", "x.txt", "fremd");

    await()
        .during(Duration.ofSeconds(8))
        .atMost(Duration.ofSeconds(9))
        .until(() -> runsOfLibrary() == before);
    assertThat(
            documentRepository.findByLibraryIdAndSourceType(library.getId(), DocumentSourceType.S3))
        .isEmpty();
  }
}
