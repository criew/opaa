package io.opaa.indexing.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.FakeEmbeddingModel;
import io.opaa.api.types.SystemRole;
import io.opaa.common.ConflictException;
import io.opaa.indexing.chunk.EmbeddingIndexReconciler;
import io.opaa.indexing.chunk.EmbeddingIndexRecord;
import io.opaa.indexing.chunk.EmbeddingIndexState;
import io.opaa.indexing.chunk.EmbeddingIndexStore;
import io.opaa.indexing.chunk.VectorIndexSchema;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.JobTriggerSource;
import io.opaa.llm.EmbeddingModelAdministration;
import io.opaa.llm.EmbeddingModelAdministration.Draft;
import io.opaa.llm.EmbeddingModelAdministration.Kind;
import io.opaa.llm.EmbeddingModelNotInstalledException;
import io.opaa.llm.EmbeddingModelService;
import io.opaa.llm.EmbeddingModelService.ProbeOutcome;
import io.opaa.llm.InstalledEmbeddingModel;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaPropertyVariantIntegrationTest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The first embedding model of an installation that skipped it (ADR-0061): before it, every run is
 * refused with {@code EMBEDDING_MODEL_NOT_CONFIGURED} and writes nothing; probe and confirmation
 * set it under the schema lock and the version check of a change, creating vector table, model row
 * with generation and anchor, and index record in one transaction, exactly once. Runs in the
 * property-variant signature like the model change, because it drops and recreates {@code
 * vector_store}; table, model row and index record are put back after every method.
 */
@OpaaPropertyVariantIntegrationTest
class FirstEmbeddingModelIntegrationTest {

  private static final String FIRST_ENDPOINT = "http://127.0.0.1:18081/v1";
  private static final String FIRST_MODEL = "bge-m3-erstes";

  @Autowired private EmbeddingModelChangeService changes;
  @Autowired private EmbeddingIndexReconciler reconciler;
  @Autowired private EmbeddingIndexState state;
  @Autowired private EmbeddingIndexStore store;
  @Autowired private VectorIndexSchema schema;
  @Autowired private IndexingJobService jobs;
  @Autowired private InstalledEmbeddingModel installedModel;
  @Autowired private EmbeddingModelService embeddingModels;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmbeddingModelAdministration administration;
  @Autowired private PlatformTransactionManager transactionManager;

  private Map<String, Object> originalModel;
  private java.sql.Timestamp testStart;
  private UUID adminId;

  @BeforeEach
  void removeTheEmbeddingModel() {
    testStart = jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class);
    originalModel = jdbc.queryForMap("SELECT * FROM embedding_model WHERE id = 1");
    adminId = admin();
    jdbc.update("DELETE FROM embedding_model");
    jdbc.update("DELETE FROM embedding_index");
    jdbc.execute("DROP TABLE IF EXISTS vector_store");
    installedModel.invalidateAll();
  }

  @AfterEach
  void restoreTheEmbeddingModel() {
    jdbc.update("DELETE FROM embedding_model");
    jdbc.update(
        "INSERT INTO embedding_model (id, base_url, model_identifier, api_key_ciphertext,"
            + " max_input_tokens, dimensions, installed_at, generation) VALUES (1, ?, ?, ?, ?, ?,"
            + " ?, ?)",
        originalModel.get("base_url"),
        originalModel.get("model_identifier"),
        originalModel.get("api_key_ciphertext"),
        originalModel.get("max_input_tokens"),
        originalModel.get("dimensions"),
        originalModel.get("installed_at"),
        originalModel.get("generation"));
    jdbc.execute("DROP TABLE IF EXISTS vector_store");
    jdbc.execute(
        "CREATE TABLE vector_store (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), content text,"
            + " metadata json, embedding vector("
            + FakeEmbeddingModel.DIMENSIONS
            + "))");
    jdbc.update("DELETE FROM embedding_index");
    jdbc.update(
        "DELETE FROM installation_counters WHERE counter_key = ?",
        EmbeddingModelChangeService.PROBE_COUNTER);
    jdbc.update("DELETE FROM users WHERE email LIKE 'first-embedding-%'");
    installedModel.invalidateAll();
    reconciler.reconcile();
  }

  @Test
  void withoutAnEmbeddingModelNoRunStartsAndNothingIsWritten() {
    UUID libraryId = UUID.randomUUID();

    assertThat(jobs.isEmbeddingModelMissing()).isTrue();
    assertThatThrownBy(
            () ->
                jobs.startJob(
                    libraryId,
                    Organization.DEFAULT_ID,
                    JobTriggerSource.MANUAL,
                    io.opaa.api.types.IndexingRunMode.FULL))
        .isInstanceOf(EmbeddingModelNotInstalledException.class)
        .extracting(e -> ((EmbeddingModelNotInstalledException) e).getCode())
        .isEqualTo(EmbeddingModelNotInstalledException.CODE);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM indexing_jobs WHERE library_id = ?", Long.class, libraryId))
        .isZero();
    assertThatThrownBy(embeddingModels::requireInstalled)
        .isInstanceOf(EmbeddingModelNotInstalledException.class)
        .hasMessageContaining("Modelle → Einbettung");
  }

  @Test
  void theProbeOfTheFirstModelNamesItInitialWithoutAPreview() {
    EmbeddingModelChangeService.ProbeResult result = changes.probe(firstDraft(FIRST_MODEL));

    assertThat(result.succeeded()).isTrue();
    assertThat(result.kind()).isEqualTo(Kind.INITIAL);
    assertThat(result.dimensions()).isEqualTo(FakeEmbeddingModel.DIMENSIONS);
    assertThat(result.preview()).isNull();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM embedding_model", Long.class)).isZero();
  }

  @Test
  void theFirstModelCreatesTableRowAndIndexRecordInOneStep() {
    long highestBefore = highestGeneration();

    changes.confirm(
        firstDraft(FIRST_MODEL), FakeEmbeddingModel.DIMENSIONS, adminId, Organization.DEFAULT_ID);

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM embedding_model WHERE id = 1");
    long generation = ((Number) row.get("generation")).longValue();
    assertThat(row.get("model_identifier")).isEqualTo(FIRST_MODEL);
    assertThat(row.get("base_url")).isEqualTo(FIRST_ENDPOINT);
    assertThat(row.get("reference_vectors")).as("the anchor of the probe").isNotNull();
    assertThat(generation).isGreaterThan(highestBefore);
    assertThat(schema.columnDimensions(EmbeddingIndexState.EMBEDDING_COLUMN))
        .isEqualTo(FakeEmbeddingModel.DIMENSIONS);
    EmbeddingIndexRecord record = store.load().orElseThrow();
    assertThat(record.current().model()).isEqualTo(FIRST_MODEL);
    assertThat(record.current().generation()).isEqualTo(generation);
    assertThat(record.target()).isNull();
    assertThat(state.isStale()).isFalse();
    assertThat(state.isVectorSearchAvailableFor(generation)).isTrue();
    assertThat(jobs.isEmbeddingModelMissing()).isFalse();
    assertThat(auditAfter("EMBEDDING_MODEL_CHANGED", "initial")).isEqualTo("true");
  }

  // regression guard: chunks without a model row were taken over by the first model unnoticed
  @Test
  void aTableWithChunksButNoModelRowRefusesTheFirstModel() {
    jdbc.execute(
        "CREATE TABLE vector_store (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), content text,"
            + " metadata json, embedding vector("
            + FakeEmbeddingModel.DIMENSIONS
            + "))");
    jdbc.update("INSERT INTO vector_store (content, metadata) VALUES ('Altbestand', '{}')");

    assertThatThrownBy(
            () ->
                changes.confirm(
                    firstDraft(FIRST_MODEL),
                    FakeEmbeddingModel.DIMENSIONS,
                    adminId,
                    Organization.DEFAULT_ID))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Abschnitte");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM embedding_model", Long.class)).isZero();
    assertThat(store.load()).isEmpty();
    assertThat(changes.probe(firstDraft(FIRST_MODEL)).succeeded()).isFalse();
  }

  /**
   * Two instances fixing the first model at once: without the exclusion of this instance, only the
   * schema lock in the database orders them, and the second finds the row of the first.
   */
  @Test
  void theSchemaLockLetsOnlyOneOfTwoTransactionsInstallTheFirstModel() throws Exception {
    Draft draft = firstDraft(FIRST_MODEL);
    EmbeddingModelAdministration.Resolved resolved = administration.resolve(draft);
    ProbeOutcome probed = administration.probe(resolved.endpoint());
    EmbeddingModelAdministration.Comparison comparison =
        administration.compare(resolved.endpoint(), probed);
    CountDownLatch firstHoldsTheLock = new CountDownLatch(1);
    CountDownLatch releaseTheFirst = new CountDownLatch(1);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    CompletableFuture<Void> first =
        CompletableFuture.runAsync(
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      schema.lockSchemaChanges();
                      administration.requireUnchanged(resolved, comparison);
                      administration.installFirst(
                          resolved,
                          probed.dimensions(),
                          EmbeddingModelAdministration.anchorOf(probed),
                          administration.nextGeneration());
                      firstHoldsTheLock.countDown();
                      awaitQuietly(releaseTheFirst);
                    }));
    assertThat(firstHoldsTheLock.await(30, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<Void> second =
        CompletableFuture.runAsync(
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      schema.lockSchemaChanges();
                      administration.requireUnchanged(resolved, comparison);
                    }));
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(30))
        .until(() -> advisoryLockWaiters() > 0);
    releaseTheFirst.countDown();
    first.get(30, TimeUnit.SECONDS);

    assertThatThrownBy(() -> second.get(30, TimeUnit.SECONDS))
        .hasCauseInstanceOf(ConflictException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM embedding_model", Long.class)).isOne();
  }

  @Test
  void twoFirstConfirmationsOfOneInstanceInstallExactlyOneModel() throws Exception {
    List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch go = new CountDownLatch(1);
    List<CompletableFuture<Void>> confirmations = new ArrayList<>();
    for (String model : List.of(FIRST_MODEL, FIRST_MODEL + "-b")) {
      confirmations.add(
          CompletableFuture.runAsync(
              () -> {
                try {
                  go.await();
                  changes.confirm(
                      firstDraft(model),
                      FakeEmbeddingModel.DIMENSIONS,
                      adminId,
                      Organization.DEFAULT_ID);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                  failures.add(e);
                }
              }));
    }
    go.countDown();
    CompletableFuture.allOf(confirmations.toArray(new CompletableFuture[0]))
        .get(60, TimeUnit.SECONDS);

    assertThat(failures).hasSize(1).first().isInstanceOf(ConflictException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM embedding_model", Long.class)).isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE event_type = 'EMBEDDING_MODEL_CHANGED'"
                    + " AND recorded_at >= ?",
                Long.class,
                testStart))
        .isOne();
  }

  private long advisoryLockWaiters() {
    Long waiting =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted",
            Long.class);
    return waiting == null ? 0 : waiting;
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static Draft firstDraft(String model) {
    return new Draft(FIRST_ENDPOINT, model, "", null);
  }

  private long highestGeneration() {
    Long highest =
        jdbc.queryForObject(
            "SELECT coalesce(max(generation), 0) FROM (SELECT generation FROM embedding_index"
                + " UNION ALL SELECT ?::bigint) g",
            Long.class,
            ((Number) originalModel.get("generation")).longValue());
    return highest == null ? 0 : highest;
  }

  private String auditAfter(String type, String key) {
    List<String> values =
        jdbc.queryForList(
            "SELECT after::jsonb->>? FROM audit_log WHERE event_type = ? AND recorded_at >= ?"
                + " ORDER BY recorded_at DESC LIMIT 1",
            String.class,
            key,
            type,
            testStart);
    return values.isEmpty() ? "" : values.getFirst();
  }

  private UUID admin() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Erstes Modell', now(), ?, ?)",
        id,
        "first-embedding-" + id,
        "first-embedding-" + id + "@example.com",
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    return id;
  }
}
