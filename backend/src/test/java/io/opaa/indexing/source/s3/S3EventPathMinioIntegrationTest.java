package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.document.Document;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.indexing.job.IndexingJob;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.JobStatus;
import io.opaa.indexing.job.JobTriggerSource;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * The event path against MinIO's real webhook (ADR-0027, Entscheidung 6; #1382): a dedicated MinIO
 * container per test notifies the running test backend through the sshd port forward Testcontainers
 * opens for the backend's random port, so the bytes on the wire are what a real store sends -
 * MinIO's {@code Authorization: Bearer <auth_token>}, its {@code Records} envelope, its URL-encoded
 * keys. The whole chain runs for real: the security chain of the intake, the token check, the
 * debounce, the EVENT run with its {@code HeadObject} finding, the document path. The webhook
 * target is configured through MinIO's environment at start, the way a Compose deployment would do
 * it.
 *
 * <p>The real executor bean reaches the container because the shared context's target-validation
 * allowlist names the Docker host address ({@code OpaaIndexingTargetAllowlistInitializer}); this
 * class therefore shares its context with every other {@link OpaaIntegrationTest}. Budget: one
 * extra MinIO container and one sshd sidecar per test method (~3 s each), plus one debounce wait
 * per expected run; the CI runs it.
 */
@OpaaIntegrationTest
class S3EventPathMinioIntegrationTest {

  private static final String TOKEN = "ereignis-token-fuer-den-test";
  private static final String FOREIGN_BUCKET = "opaa-fremd";

  /** Longer than the intake's debounce (5 s) plus one run over a few small objects. */
  private static final Duration RUN_TIMEOUT = Duration.ofSeconds(60);

  /**
   * The negative case has to outlast the debounce window it must not trigger: the 5 s debounce plus
   * slack for a batch that waited on a running run.
   */
  private static final Duration SILENCE = Duration.ofSeconds(8);

  private static final Duration POLL = Duration.ofMillis(500);

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
    admin.createBucket(CreateBucketRequest.builder().bucket(FOREIGN_BUCKET).build());
    mc("mc event add local/" + bucket + " arn:minio:sqs::OPAA:webhook --event put,delete");
    mc("mc event add local/" + FOREIGN_BUCKET + " arn:minio:sqs::OPAA:webhook --event put,delete");

    library.updateSourceConfiguration(
        null, minio.getS3URL(), null, minio.getUserName() + ":" + minio.getPassword(), false);
    library = libraryRepository.save(library);
  }

  @AfterEach
  void tearDown() {
    try {
      if (library != null) {
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
      }
      if (userId != null) {
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
      }
    } finally {
      // the container must go even when a delete above throws - Ryuk would only reap it at exit
      if (admin != null) {
        admin.close();
      }
      if (minio != null) {
        minio.stop();
      }
    }
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

  private Optional<Document> documentAt(String key) {
    return documentRepository.findByLibraryIdAndFilePath(
        library.getId(), S3FullSync.filePath(bucket, key));
  }

  private List<IndexingJob> runsOfLibrary() {
    return indexingJobRepository.findByLibraryIdOrderByStartedAtDesc(library.getId());
  }

  /**
   * Waits until the library has exactly {@code count} runs and the newest one has completed - the
   * job's end state is the later anchor: the run template records cost and counters after the last
   * document became visible.
   */
  private IndexingJob awaitRuns(int count) {
    await()
        .atMost(RUN_TIMEOUT)
        .pollInterval(POLL)
        .untilAsserted(
            () -> {
              List<IndexingJob> runs = runsOfLibrary();
              assertThat(runs).hasSize(count);
              assertThat(runs.getFirst().getStatus()).isEqualTo(JobStatus.COMPLETED);
            });
    return runsOfLibrary().getFirst();
  }

  @Test
  void createdObjectsAreDebouncedIntoOneRunAndARemovedOneIsRemovedThroughMinIOsOwnWebhook() {
    // Assurance: MinIO's notifications reach the intake with the Bearer auth_token and Records
    // body; three objects put within the debounce window cost ONE event run (trigger WEBHOOK, no
    // listing assessment) that indexes exactly those three; a removal event leads to a HeadObject
    // 404 that removes the document in a second run.
    List<String> keys =
        List.of("eingang/Sitzung 1.txt", "eingang/Sitzung 2.txt", "eingang/Sitzung 3.txt");
    for (String key : keys) {
      put(bucket, key, "Protokoll: " + key);
    }

    IndexingJob eventRun = awaitRuns(1);
    assertThat(eventRun.getRunMode()).isEqualTo(IndexingRunMode.EVENT);
    assertThat(eventRun.getTriggeredBy()).isEqualTo(JobTriggerSource.WEBHOOK);
    assertThat(eventRun.getDocumentsProcessed()).as("one run, three objects").isEqualTo(3);
    assertThat(eventRun.getListingComplete())
        .as("an event run never assesses the listing")
        .isNull();
    for (String key : keys) {
      assertThat(documentAt(key))
          .as(key)
          .hasValueSatisfying(
              document -> assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED));
    }

    admin.deleteObject(
        DeleteObjectRequest.builder().bucket(bucket).key("eingang/Sitzung 2.txt").build());

    IndexingJob removalRun = awaitRuns(2);
    assertThat(removalRun.getRunMode()).isEqualTo(IndexingRunMode.EVENT);
    assertThat(documentAt("eingang/Sitzung 2.txt")).isEmpty();
    assertThat(documentAt("eingang/Sitzung 1.txt")).isPresent();
    assertThat(documentAt("eingang/Sitzung 3.txt")).isPresent();
  }

  @Test
  void anEventForABucketOutsideTheScopesIsDroppedWhileTheInScopeOneOfTheSameBatchRuns() {
    // Assurance: a notification for a bucket the library does not cover is authenticated, dropped
    // and never turned into a run. The in-scope object put in the same debounce window is the
    // control: its run proves the wire was up, and that run carries exactly one object - the
    // foreign one contributed nothing. Then silence: no second run follows.
    put(FOREIGN_BUCKET, "x.txt", "Fremdes Protokoll, nicht im Geltungsbereich.");
    put(bucket, "eigen.txt", "Eigenes Protokoll der Sitzung im Geltungsbereich.");

    IndexingJob run = awaitRuns(1);
    assertThat(run.getDocumentsProcessed()).isEqualTo(1);
    assertThat(
            documentRepository.findByLibraryIdAndSourceType(library.getId(), DocumentSourceType.S3))
        .extracting(Document::getFilePath)
        .containsExactly(S3FullSync.filePath(bucket, "eigen.txt"));

    await()
        .during(SILENCE)
        .atMost(SILENCE.plus(Duration.ofSeconds(4)))
        .pollInterval(POLL)
        .until(() -> runsOfLibrary().size() == 1);
  }
}
