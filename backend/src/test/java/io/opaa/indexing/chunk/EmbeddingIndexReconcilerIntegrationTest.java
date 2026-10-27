package io.opaa.indexing.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.opaa.test.OpaaPropertyVariantIntegrationTest;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The startup comparison of configured embedding model and vector index, against a real pgvector
 * with the Flyway schema. The signature pins the configured size to 1536 and switches Spring AI's
 * schema initialisation off, so each test builds the table it needs - a table stuck at another size
 * included - and drops it again; that is why this class cannot share the canonical database. The
 * configured model is the default, {@code bge-m3}.
 */
@OpaaPropertyVariantIntegrationTest
class EmbeddingIndexReconcilerIntegrationTest {

  private static final String CONFIGURED_MODEL = "bge-m3";

  @Autowired private EmbeddingIndexReconciler reconciler;
  @Autowired private EmbeddingIndexState state;
  @Autowired private EmbeddingIndexStore store;
  @Autowired private VectorIndexSchema schema;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  @BeforeEach
  @AfterEach
  void restoreTheIndexOfTheSignature() {
    jdbcTemplate.execute("DROP TABLE IF EXISTS vector_store");
    jdbcTemplate.update("DELETE FROM embedding_index");
    reconciler.reconcile();
  }

  @Test
  void aMissingTableRecordsTheConfiguredModelAndIsNotStale() {
    assertThat(state.isStale()).isFalse();
    assertThat(store.load())
        .hasValueSatisfying(
            record -> {
              assertThat(record.current().model()).isEqualTo(CONFIGURED_MODEL);
              assertThat(record.current().dimensions()).isEqualTo(1536);
              assertThat(record.target()).isNull();
            });
  }

  @Test
  void anEmptyTableOfAnotherSizeIsResizedInPlaceInsteadOfRefusingTheStart() {
    createVectorStore(768);

    reconciler.reconcile();

    assertThat(state.isStale()).isFalse();
    assertThat(schema.columnDimensions(EmbeddingIndexState.EMBEDDING_COLUMN)).isEqualTo(1536);
    assertThat(schema.columnDimensions(EmbeddingIndexState.TARGET_COLUMN)).isNull();
  }

  @Test
  void anIndexWithoutRecordedIdentityTakesTheConfiguredOneWhenItsSizeMatches() {
    createVectorStore(1536);
    insertChunk(1536);
    jdbcTemplate.update("DELETE FROM embedding_index");

    reconciler.reconcile();

    assertThat(state.isStale()).isFalse();
    assertThat(store.load().orElseThrow().current().model()).isEqualTo(CONFIGURED_MODEL);
  }

  @Test
  void anotherModelOfTheSameSizeIsDetectedAsStaleAndSuspendsTheVectorSearch() {
    createVectorStore(1536);
    insertChunk(1536);
    recordIndexModel("text-embedding-3-small", 1536);

    reconciler.reconcile();

    assertThat(state.isStale()).isTrue();
    assertThat(state.isVectorSearchAvailable()).isFalse();
    assertThat(state.snapshot().current().model()).isEqualTo("text-embedding-3-small");
    assertThat(state.snapshot().target().model()).isEqualTo(CONFIGURED_MODEL);
    assertThat(schema.columnDimensions(EmbeddingIndexState.TARGET_COLUMN)).isEqualTo(1536);
    assertThat(store.load().orElseThrow().target().model()).isEqualTo(CONFIGURED_MODEL);
  }

  @Test
  void vectorsOfAnotherSizeNoLongerPreventTheStartAndGetATargetColumnOfTheConfiguredSize() {
    createVectorStore(768);
    insertChunk(768);
    jdbcTemplate.update("DELETE FROM embedding_index");

    reconciler.reconcile();

    assertThat(state.isStale()).isTrue();
    assertThat(state.snapshot().current().model()).as("unknown: never recorded").isNull();
    assertThat(state.snapshot().current().dimensions()).isEqualTo(768);
    assertThat(schema.columnDimensions(EmbeddingIndexState.EMBEDDING_COLUMN)).isEqualTo(768);
    assertThat(schema.columnDimensions(EmbeddingIndexState.TARGET_COLUMN)).isEqualTo(1536);
  }

  @Test
  void aSecondStartKeepsTheTargetVectorsAlreadyComputedForTheSameModel() {
    createVectorStore(768);
    insertChunk(768);
    reconciler.reconcile();
    jdbcTemplate.update(
        "UPDATE vector_store SET embedding_next = ?::vector", vectorLiteral(1536, "0.2"));

    reconciler.reconcile();

    assertThat(state.isStale()).isTrue();
    assertThat(schema.chunksWithout(EmbeddingIndexState.TARGET_COLUMN)).isZero();
  }

  @Test
  void goingBackToTheModelOfTheIndexDropsTheTargetColumn() {
    createVectorStore(1536);
    insertChunk(1536);
    recordIndexModel("text-embedding-3-small", 1536);
    reconciler.reconcile();
    assertThat(state.isStale()).isTrue();
    recordIndexModel(CONFIGURED_MODEL, 1536);
    recordIndexGenerationOfTheModelRow();

    reconciler.reconcile();

    assertThat(state.isStale()).isFalse();
    assertThat(schema.columnDimensions(EmbeddingIndexState.TARGET_COLUMN)).isNull();
  }

  @Test
  void aChangedEndpointAloneIsNoModelChange() {
    createVectorStore(1536);
    insertChunk(1536);
    jdbcTemplate.update("UPDATE embedding_index SET base_url = 'http://elsewhere:11434/v1'");

    reconciler.reconcile();

    assertThat(state.isStale()).isFalse();
  }

  /**
   * Ein alter Pod löscht {@code embedding_next} nicht: next to a live instance of a newer version,
   * the reconciliation takes the index as recorded, even where its own comparison would drop the
   * target column.
   */
  @Test
  void anInstanceOfAnOlderVersionNextToANewerOneChangesNoColumn() {
    createVectorStore(1536);
    insertChunk(1536);
    recordIndexModel("text-embedding-3-small", 1536);
    reconciler.reconcile();
    jdbcTemplate.update("UPDATE embedding_index SET model = ?", CONFIGURED_MODEL);
    recordIndexGenerationOfTheModelRow();
    UUID newer = liveInstanceOfVersion("99.0.0");
    try {
      reconciler.reconcile();

      assertThat(schema.columnDimensions(EmbeddingIndexState.TARGET_COLUMN)).isEqualTo(1536);
      assertThat(state.snapshot().target()).isNotNull();
    } finally {
      jdbcTemplate.update("DELETE FROM backend_instances WHERE id = ?", newer);
    }

    reconciler.reconcile();

    assertThat(schema.columnDimensions(EmbeddingIndexState.TARGET_COLUMN))
        .as("without the newer instance, this one is the newest and reconciles")
        .isNull();
  }

  /** Two instances never change the embedding columns at the same time. */
  @Test
  void theReconciliationWaitsForTheSchemaLockAnotherInstanceHolds() throws Exception {
    createVectorStore(1536);
    try (Connection other = dataSource.getConnection()) {
      other.setAutoCommit(false);
      try (Statement statement = other.createStatement()) {
        statement.execute(
            "SELECT pg_advisory_xact_lock(" + VectorIndexSchema.SCHEMA_LOCK_NAMESPACE + ", 0)");
      }
      CompletableFuture<Void> reconciliation = CompletableFuture.runAsync(reconciler::reconcile);
      await()
          .atMost(10, TimeUnit.SECONDS)
          .until(
              () ->
                  jdbcTemplate.queryForObject(
                          "SELECT count(*) FROM pg_stat_activity WHERE wait_event = 'advisory'",
                          Long.class)
                      > 0);
      assertThat(reconciliation).isNotDone();

      other.commit();
      reconciliation.get(10, TimeUnit.SECONDS);
    }
  }

  private UUID liveInstanceOfVersion(String version) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO backend_instances (id, host_name, version, oldest_compatible_version,"
            + " started_at, heartbeat_at, fingerprint, fingerprint_hash) VALUES (?, 'fixture', ?,"
            + " ?, clock_timestamp(), clock_timestamp(), '{}', 'x')",
        id,
        version,
        version);
    return id;
  }

  /** The embedding column as built under the generation the model row carries now. */
  private void recordIndexGenerationOfTheModelRow() {
    jdbcTemplate.update(
        "UPDATE embedding_index SET generation = (SELECT generation FROM embedding_model),"
            + " target_generation = NULL");
  }

  private void recordIndexModel(String model, int dimensions) {
    jdbcTemplate.update(
        "UPDATE embedding_index SET model = ?, dimensions = ?, target_model = NULL,"
            + " target_base_url = NULL, target_dimensions = NULL",
        model,
        dimensions);
  }

  private void createVectorStore(int dimensions) {
    jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
    jdbcTemplate.execute(
        "CREATE TABLE vector_store (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), content text,"
            + " metadata json, embedding vector(%d))".formatted(dimensions));
  }

  private void insertChunk(int dimensions) {
    jdbcTemplate.update(
        "INSERT INTO vector_store (content, metadata, embedding) VALUES ('Text', '{}', ?::vector)",
        vectorLiteral(dimensions, "0.1"));
  }

  private static String vectorLiteral(int dimensions, String value) {
    return "[" + String.join(",", Collections.nCopies(dimensions, value)) + "]";
  }
}
