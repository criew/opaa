package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.SystemRole;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.job.IndexingJob;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.JobStatus;
import io.opaa.indexing.job.JobTriggerSource;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.sourcesettings.S3Scope;
import io.opaa.knowledge.sourcesettings.S3SourceSettings;
import io.opaa.organization.Organization;
import io.opaa.s3.S3TestFixture;
import io.opaa.test.OpaaIntegrationTest;
import java.net.URLEncoder;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The event path end to end (ADR-0027, Entscheidung 6 und Nachtrag 09/2026; #1382, #1949): the
 * notification is delivered by the test itself - the documented S3 {@code Records} payload with
 * {@code Authorization: Bearer <token>}, keys URL-encoded the way MinIO and Ceph send them. The
 * store behind it is real: everything the run touches - the security chain of the intake, the token
 * check, the debounce, the EVENT run with its {@code HeadObject} against the store, the document
 * path, the removal on a confirmed 404 - runs against the shared {@link S3TestFixture}. What this
 * test does not cover is the delivery by the store itself; no S3 server we can pull publicly emits
 * bucket notifications (see the ADR addendum).
 *
 * <p>The real executor bean reaches the container because the shared context's target-validation
 * allowlist names the Docker host address ({@code OpaaIndexingTargetAllowlistInitializer}); this
 * class therefore shares its context with every other {@link OpaaIntegrationTest}.
 */
@OpaaIntegrationTest
class S3EventPathIntegrationTest {

  private static final String TOKEN = "ereignis-token-fuer-den-test";

  /** Longer than the intake's debounce (5 s) plus one run over a few small objects. */
  private static final Duration RUN_TIMEOUT = Duration.ofSeconds(60);

  /**
   * The negative case has to outlast the debounce window it must not trigger: the 5 s debounce plus
   * slack for a batch that waited on a running run.
   */
  private static final Duration SILENCE = Duration.ofSeconds(8);

  private static final Duration POLL = Duration.ofMillis(500);

  @Autowired private MockMvc mockMvc;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private VectorChunkStore vectorChunkStore;
  @Autowired private JdbcTemplate jdbcTemplate;

  private S3TestFixture store;
  private UUID userId;
  private KnowledgeLibrary library;
  private String bucket;
  private String foreignBucket;

  @BeforeEach
  void setUp() {
    store = S3TestFixture.get();
    bucket = store.createBucket("opaa-ereignisse");
    foreignBucket = store.createBucket("opaa-fremd");

    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'S3 Event IT', now(), ?, ?)",
        userId,
        "s3-event-it-" + userId,
        "s3-event-it-" + userId + "@example.com",
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    KnowledgeLibrary fresh =
        KnowledgeLibrary.ownedByUser(
            Organization.DEFAULT_ID,
            "Ereignisse",
            null,
            userId,
            false,
            DocumentSourceType.S3,
            null,
            store.endpoint().toString(),
            null,
            store.rootCredentials().stored(),
            false);
    fresh.updateS3Settings(
        new S3SourceSettings(
            S3TestFixture.REGION, true, List.of(S3Scope.of(bucket, "")), null, null));
    fresh.setWebhookSecret(TOKEN);
    library = libraryRepository.save(fresh);
  }

  @AfterEach
  void tearDown() {
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
  }

  /**
   * One {@code Records} notification, byte for byte in the shape MinIO sends: the key {@code
   * application/x-www-form-urlencoded}, the token as a bearer header.
   */
  private void notifyOf(String eventName, String notifiedBucket, String key) throws Exception {
    String body =
        "{\"Records\":[{\"eventVersion\":\"2.0\",\"eventName\":\""
            + eventName
            + "\",\"s3\":{\"bucket\":{\"name\":\""
            + notifiedBucket
            + "\"},\"object\":{\"key\":\""
            + URLEncoder.encode(key, StandardCharsets.UTF_8).replace("%2F", "/")
            + "\"}}}]}";
    mockMvc
        .perform(
            post("/api/v1/libraries/{id}/s3-events", library.getId())
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted());
  }

  private void put(String bucketName, String key, String text) {
    store.putObject(bucketName, key, text, "text/plain");
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
  void aCorrectTokenNextToAWrongDuplicateOfTheSameHeaderIsRefused() throws Exception {
    // regression guard: a repeated header is judged as a whole, as Spring binds it - the correct
    // first value must not authenticate a request that also carries a wrong one
    mockMvc
        .perform(
            post("/api/v1/libraries/{id}/s3-events", library.getId())
                .header("X-OPAA-Webhook-Secret", TOKEN, "falsch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"Records\":[]}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/libraries/{id}/s3-events", library.getId())
                .header("X-OPAA-Webhook-Secret", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"Records\":[]}"))
        .andExpect(status().isAccepted());
  }

  @Test
  void createdObjectsAreDebouncedIntoOneRunAndARemovedOneIsRemovedAgain() throws Exception {
    // Assurance: notifications reach the intake with the Bearer token and the Records body; three
    // objects announced within the debounce window cost ONE event run (trigger WEBHOOK, no listing
    // assessment) that indexes exactly those three; a removal event leads to a HeadObject 404
    // against the real store that removes the document in a second run.
    List<String> keys =
        List.of("eingang/Sitzung 1.txt", "eingang/Sitzung 2.txt", "eingang/Sitzung 3.txt");
    for (String key : keys) {
      put(bucket, key, "Protokoll: " + key);
      notifyOf("s3:ObjectCreated:Put", bucket, key);
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

    store.deleteObject(bucket, "eingang/Sitzung 2.txt");
    notifyOf("s3:ObjectRemoved:Delete", bucket, "eingang/Sitzung 2.txt");

    IndexingJob removalRun = awaitRuns(2);
    assertThat(removalRun.getRunMode()).isEqualTo(IndexingRunMode.EVENT);
    assertThat(documentAt("eingang/Sitzung 2.txt")).isEmpty();
    assertThat(documentAt("eingang/Sitzung 1.txt")).isPresent();
    assertThat(documentAt("eingang/Sitzung 3.txt")).isPresent();
  }

  @Test
  void anEventForABucketOutsideTheScopesIsDroppedWhileTheInScopeOneOfTheSameBatchRuns()
      throws Exception {
    // Assurance: a notification for a bucket the library does not cover is authenticated, dropped
    // and never turned into a run. The in-scope object announced in the same debounce window is the
    // control: its run proves the wire was up, and that run carries exactly one object - the
    // foreign one contributed nothing. Then silence: no second run follows.
    put(foreignBucket, "x.txt", "Fremdes Protokoll, nicht im Geltungsbereich.");
    notifyOf("s3:ObjectCreated:Put", foreignBucket, "x.txt");
    put(bucket, "eigen.txt", "Eigenes Protokoll der Sitzung im Geltungsbereich.");
    notifyOf("s3:ObjectCreated:Put", bucket, "eigen.txt");

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
