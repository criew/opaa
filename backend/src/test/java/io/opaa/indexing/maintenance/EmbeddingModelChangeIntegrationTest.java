package io.opaa.indexing.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.opaa.FakeEmbeddingModel;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.SystemRole;
import io.opaa.common.ConflictException;
import io.opaa.common.FieldValidationException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.indexing.chunk.EmbeddingGenerationChangedException;
import io.opaa.indexing.chunk.EmbeddingIndexReconciler;
import io.opaa.indexing.chunk.EmbeddingIndexRecord;
import io.opaa.indexing.chunk.EmbeddingIndexRecord.RunKind;
import io.opaa.indexing.chunk.EmbeddingIndexRecord.RunStatus;
import io.opaa.indexing.chunk.EmbeddingIndexState;
import io.opaa.indexing.chunk.EmbeddingIndexState.Snapshot;
import io.opaa.indexing.chunk.EmbeddingIndexStore;
import io.opaa.indexing.chunk.FullTextChunkStore;
import io.opaa.indexing.chunk.VectorIndexSchema;
import io.opaa.indexing.chunk.VectorStoreWriter;
import io.opaa.indexing.document.DocumentIngest;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.knowledge.VectorTable;
import io.opaa.knowledge.VectorTableCreator;
import io.opaa.llm.EmbeddingAnchorRecorder;
import io.opaa.llm.EmbeddingModelAdministration.Draft;
import io.opaa.llm.EmbeddingModelAdministration.Kind;
import io.opaa.llm.EmbeddingModelService.ProbeOutcome;
import io.opaa.organization.Organization;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.retrieval.search.VectorChunkSearch;
import io.opaa.security.SettingsEncryptor;
import io.opaa.test.FakeEmbeddingProbe;
import io.opaa.test.OpaaPropertyVariantIntegrationTest;
import io.opaa.test.OpaaTestDirectory;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.SourceTypes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The guided change of the embedding model end to end against pgvector with the Flyway schema
 * (ADR-0061): confirmation, discard with refill, endpoint change, the generation guarding writes
 * and searches, the version condition and the limits. Runs in the property-variant signature, like
 * the re-embedding, because it changes {@code vector_store}, the model row and the index row; every
 * one of them is put back after each method.
 */
@OpaaPropertyVariantIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class EmbeddingModelChangeIntegrationTest {

  private static final Path classTempDir = OpaaTestDirectory.subdirectory("embedding-change");
  private static final Duration RUN_TIMEOUT = Duration.ofSeconds(60);
  private static final String NEW_MODEL = "bge-m3-neu";
  private static final String OTHER_ENDPOINT = "http://127.0.0.1:18080/v1";

  @Autowired private EmbeddingModelChangeService changes;
  @Autowired private EmbeddingReindexService reindexService;
  @Autowired private EmbeddingIndexReconciler reconciler;
  @Autowired private EmbeddingIndexState state;
  @Autowired private EmbeddingIndexStore store;
  @Autowired private VectorIndexSchema schema;
  @Autowired private VectorStoreWriter writer;
  @Autowired private VectorChunkSearch vectorChunkSearch;
  @Autowired private EmbeddingAnchorRecorder anchorRecorder;
  @Autowired private FakeEmbeddingProbe probe;
  @Autowired private EmbeddingModel embeddingModel;
  @Autowired private DocumentIngestService documentIngestService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private LibraryAccessService accessService;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private FullTextChunkStore fullTextChunkStore;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private VectorTable vectorTable;
  @Autowired private VectorTableCreator vectorTableCreator;
  @Autowired private SettingsEncryptor settingsEncryptor;

  private final List<CountDownLatch> heldRuns = new ArrayList<>();
  private final List<CompletableFuture<?>> pending = new ArrayList<>();
  private Map<String, Object> originalModel;
  private java.sql.Timestamp testStart;
  private KnowledgeLibrary library;
  private UUID adminId;

  @BeforeEach
  void setUp() throws IOException {
    removeOwnFixtures();
    testStart = jdbcTemplate.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class);
    originalModel =
        jdbcTemplate.queryForMap(
            "SELECT base_url, model_identifier, api_key_ciphertext, max_input_tokens, dimensions,"
                + " generation FROM embedding_model WHERE id = 1");
    adminId = admin();
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Modellwechsel",
                null,
                adminId,
                SourceTypes.FILESYSTEM,
                classTempDir.toString(),
                null,
                null));
    grantRepository.save(
        AssetGrant.forUser(
            KnowledgeLibrary.ASSET_TYPE,
            library.getId(),
            Organization.DEFAULT_ID,
            adminId,
            AssetRole.OWNER,
            null,
            adminId));
    accessService.invalidateLibrary(library.getId());
    Files.createDirectories(classTempDir);
  }

  @AfterEach
  void removeOwnFixtures() {
    heldRuns.forEach(CountDownLatch::countDown);
    heldRuns.clear();
    pending.forEach(future -> future.handle((result, failure) -> null).join());
    pending.clear();
    fakeModel().reset();
    await().atMost(RUN_TIMEOUT).until(() -> !reindexService.progress().running());
    if (schema.columnDimensions(EmbeddingIndexState.EMBEDDING_COLUMN) == null) {
      createVectorStore();
    }
    List<UUID> ownLibraryIds =
        jdbcTemplate.queryForList("SELECT id FROM assets WHERE name = 'Modellwechsel'", UUID.class);
    ownLibraryFixtures.removeLibraries(ownLibraryIds.toArray(new UUID[0]));
    jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'embedding-change-%'");
    jdbcTemplate.update("DELETE FROM backend_instances WHERE host_name = 'embedding-change'");
    jdbcTemplate.update(
        "DELETE FROM installation_counters WHERE counter_key IN (?, ?)",
        EmbeddingModelChangeService.PROBE_COUNTER,
        EmbeddingModelChangeService.ENDPOINT_COUNTER);
    jdbcTemplate.update("DELETE FROM embedding_model_previous");
    if (originalModel != null) {
      jdbcTemplate.update(
          "UPDATE embedding_model SET base_url = ?, model_identifier = ?, api_key_ciphertext = ?,"
              + " max_input_tokens = ?, dimensions = ?, generation = ?, reference_vectors = NULL"
              + " WHERE id = 1",
          originalModel.get("base_url"),
          originalModel.get("model_identifier"),
          originalModel.get("api_key_ciphertext"),
          originalModel.get("max_input_tokens"),
          originalModel.get("dimensions"),
          originalModel.get("generation"));
    }
    jdbcTemplate.execute("DROP TABLE IF EXISTS vector_store");
    jdbcTemplate.update("DELETE FROM embedding_index");
    createVectorStore();
    reconciler.reconcile();
  }

  // --- confirmation ---

  @Test
  void aProbeOfAnotherModelPreviewsTheChangeWithoutWritingAnything() throws IOException {
    indexed("Bewohnerparken", "Zone A");
    long generation = modelGeneration();

    EmbeddingModelChangeService.ProbeResult result = changes.probe(draft(NEW_MODEL));

    assertThat(result.succeeded()).isTrue();
    assertThat(result.kind()).isEqualTo(Kind.MODEL_CHANGE);
    assertThat(result.dimensions()).isEqualTo(FakeEmbeddingModel.DIMENSIONS);
    assertThat(result.preview()).isNotNull();
    assertThat(result.preview().rateSource())
        .isEqualTo(io.opaa.metadata.EmbeddingRateEstimator.RateSource.MEASURED);
    assertThat(result.preview().storageUpperBytes())
        .isGreaterThanOrEqualTo(result.preview().storageLowerBytes())
        .isGreaterThanOrEqualTo(result.preview().currentTableBytes());
    assertThat(modelGeneration()).isEqualTo(generation);
    assertThat(previousRows()).isZero();
    assertThat(state.isStale()).isFalse();
  }

  @Test
  void theConfirmationChangesTheModelInOneTransactionAndTheRunSwitchesOver() throws IOException {
    Document document = indexed("Bewohnerparken", "Zone A");
    long generation = modelGeneration();
    long startedBefore = auditEvents("EMBEDDING_REINDEX_STARTED");

    changes.confirm(
        draft(NEW_MODEL), FakeEmbeddingModel.DIMENSIONS, adminId, Organization.DEFAULT_ID);

    assertThat(modelName()).isEqualTo(NEW_MODEL);
    assertThat(modelGeneration()).isGreaterThan(generation);
    assertThat(auditEvents("EMBEDDING_MODEL_CHANGED")).isOne();
    assertThat(auditEvents("EMBEDDING_REINDEX_STARTED"))
        .as("the event of the confirmation stands for the start")
        .isEqualTo(startedBefore);
    awaitTheRun();
    EmbeddingIndexRecord record = store.load().orElseThrow();
    assertThat(record.run().status()).isEqualTo(RunStatus.COMPLETED);
    assertThat(record.current().model()).isEqualTo(NEW_MODEL);
    assertThat(record.current().generation()).isEqualTo(modelGeneration());
    assertThat(record.target()).isNull();
    assertThat(state.isStale()).isFalse();
    assertThat(previousRows()).as("the switch drops the previous model").isZero();
    assertThat(columnCountOf(document, "embedding IS NULL")).isZero();
  }

  @Test
  void whileTheChangeRunsTheVectorSearchIsSuspendedAndARestartKeepsTheChange() throws IOException {
    indexed("Bewohnerparken", "SCHRANKE-EINS");
    holdTheRunAt("SCHRANKE-EINS");
    changes.confirm(
        draft(NEW_MODEL), FakeEmbeddingModel.DIMENSIONS, adminId, Organization.DEFAULT_ID);
    long target = store.load().orElseThrow().target().generation();

    assertThat(state.isVectorSearchAvailable()).isFalse();
    reconciler.reconcile();

    EmbeddingIndexRecord record = store.load().orElseThrow();
    assertThat(state.isStale()).isTrue();
    assertThat(record.target().generation()).isEqualTo(target).isEqualTo(modelGeneration());
    assertThat(record.run().status()).isEqualTo(RunStatus.RUNNING);
  }

  @Test
  void aChangeUnderTheSameNameAndSizeSurvivesARestart() throws IOException {
    indexed("Bewohnerparken", "SCHRANKE-ZWEI");
    String sameName = modelName();
    anchorRecorder.recordIfMissing();
    assertThat(anchorRecorded()).isTrue();
    probe.answerWith((endpoint, texts) -> dissimilarProbe(texts));
    holdTheRunAt("SCHRANKE-ZWEI");

    EmbeddingModelChangeService.ProbeResult result = changes.probe(draft(sameName));
    assertThat(result.kind()).isEqualTo(Kind.MODEL_CHANGE);
    changes.confirm(
        draft(sameName), FakeEmbeddingModel.DIMENSIONS, adminId, Organization.DEFAULT_ID);
    reconciler.reconcile();

    assertThat(state.isStale()).as("not taken for the old model again").isTrue();
    assertThat(schema.columnDimensions(EmbeddingIndexState.TARGET_COLUMN)).isNotNull();
  }

  @Test
  void aRefusedTableLockLeavesNothingBehind() throws Exception {
    indexed("Bewohnerparken", "Zone A");
    long generation = modelGeneration();
    try (Connection reader = dataSource.getConnection()) {
      reader.setAutoCommit(false);
      try (Statement statement = reader.createStatement()) {
        statement.execute("SELECT count(*) FROM vector_store");
      }
      assertThatThrownBy(
              () ->
                  changes.confirm(
                      draft(NEW_MODEL),
                      FakeEmbeddingModel.DIMENSIONS,
                      adminId,
                      Organization.DEFAULT_ID))
          .isInstanceOf(ConflictException.class)
          .hasMessageContaining("belegt");
      reader.rollback();
    }

    assertThat(modelName()).isNotEqualTo(NEW_MODEL);
    assertThat(modelGeneration()).isEqualTo(generation);
    assertThat(previousRows()).isZero();
    assertThat(schema.columnDimensions(EmbeddingIndexState.TARGET_COLUMN)).isNull();
    assertThat(auditEvents("EMBEDDING_MODEL_CHANGED")).isZero();
  }

  @Test
  void twoConfirmationsAtOnceStartExactlyOneChange() throws Exception {
    indexed("Bewohnerparken", "SCHRANKE-DREI");
    holdTheRunAt("SCHRANKE-DREI");
    List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch go = new CountDownLatch(1);
    List<CompletableFuture<Void>> confirmations = new ArrayList<>();
    for (String model : List.of(NEW_MODEL, NEW_MODEL + "-b")) {
      confirmations.add(
          CompletableFuture.runAsync(
              () -> {
                try {
                  go.await();
                  changes.confirm(
                      draft(model),
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
    assertThat(previousRows()).isOne();
    assertThat(auditEvents("EMBEDDING_MODEL_CHANGED")).isOne();
  }

  // --- discard and refill ---

  @Test
  void aDiscardRestoresThePreviousModelAndRefillsWhatWasWrittenMeanwhile() throws Exception {
    Document before = indexed("Bewohnerparken", "SCHRANKE-VIER");
    String original = modelName();
    long generation = modelGeneration();
    CountDownLatch held = holdTheRunAt("SCHRANKE-VIER");
    changes.confirm(
        draft(NEW_MODEL), FakeEmbeddingModel.DIMENSIONS, adminId, Organization.DEFAULT_ID);
    awaitHeld(held);
    Document meanwhile = indexed("Anwohnerparkausweis", "Zone B");
    assertThat(columnCountOf(meanwhile, "embedding IS NULL"))
        .as("written while stale: only a vector of the new model")
        .isPositive();

    EmbeddingReindexProgress discarded = changes.discard(adminId, Organization.DEFAULT_ID);

    assertThat(discarded.stale()).isFalse();
    assertThat(modelName()).isEqualTo(original);
    assertThat(modelGeneration()).isEqualTo(generation);
    assertThat(previousRows()).isZero();
    assertThat(schema.columnDimensions(EmbeddingIndexState.TARGET_COLUMN)).isNull();
    assertThat(auditEvents("EMBEDDING_MODEL_CHANGE_DISCARDED")).isOne();
    releaseTheRuns();
    awaitTheRun();
    EmbeddingIndexRecord record = store.load().orElseThrow();
    assertThat(record.run().kind()).isEqualTo(RunKind.REFILL);
    assertThat(record.run().status()).isEqualTo(RunStatus.COMPLETED);
    assertThat(columnCountOf(meanwhile, "embedding IS NULL")).isZero();
    assertThat(columnCountOf(before, "embedding IS NULL")).isZero();
  }

  @Test
  void aDiscardIsRefusedWhileALiveRunBuildsTheTargetIndexAndPossibleOnceTheRunEnded()
      throws IOException {
    indexed("Bewohnerparken", "SCHRANKE-FUENF");
    holdTheRunAt("SCHRANKE-FUENF");
    changes.confirm(
        draft(NEW_MODEL), FakeEmbeddingModel.DIMENSIONS, adminId, Organization.DEFAULT_ID);
    jdbcTemplate.update("UPDATE embedding_index SET index_build_started_at = clock_timestamp()");

    assertThatThrownBy(() -> changes.discard(adminId, Organization.DEFAULT_ID))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Umschalten");

    reindexService.cancel();
    assertThat(store.load().orElseThrow().run().indexBuildStartedAt())
        .as("every end of the run clears the mark")
        .isNull();
    changes.discard(adminId, Organization.DEFAULT_ID);
    assertThat(state.isStale()).isFalse();
  }

  @Test
  void aDiscardAfterTheSwitchIsRefused() throws IOException {
    indexed("Bewohnerparken", "Zone A");
    changes.confirm(
        draft(NEW_MODEL), FakeEmbeddingModel.DIMENSIONS, adminId, Organization.DEFAULT_ID);
    awaitTheRun();

    assertThatThrownBy(() -> changes.discard(adminId, Organization.DEFAULT_ID))
        .isInstanceOf(ConflictException.class);
    assertThat(modelName()).isEqualTo(NEW_MODEL);
  }

  @Test
  void aConfirmationEndsARunningRefillAndItsWorkerOnTheSameInstance() throws Exception {
    indexed("Bewohnerparken", "SCHRANKE-SECHS");
    CountDownLatch reembedding = holdTheRunAt("SCHRANKE-SECHS");
    changes.confirm(
        draft(NEW_MODEL), FakeEmbeddingModel.DIMENSIONS, adminId, Organization.DEFAULT_ID);
    awaitHeld(reembedding);
    indexed("Anwohnerparkausweis", "SCHRANKE-SIEBEN");
    CountDownLatch refill = holdTheRunAt("SCHRANKE-SIEBEN");
    changes.discard(adminId, Organization.DEFAULT_ID);
    releaseFirstHeldRun();
    awaitHeld(refill);
    assertThat(store.load().orElseThrow().run().kind()).isEqualTo(RunKind.REFILL);

    changes.confirm(
        draft(NEW_MODEL + "-c"), FakeEmbeddingModel.DIMENSIONS, adminId, Organization.DEFAULT_ID);

    EmbeddingIndexRecord record = store.load().orElseThrow();
    assertThat(record.run().kind()).isEqualTo(RunKind.REEMBED);
    assertThat(auditPayload("EMBEDDING_MODEL_CHANGED", "refillEnded")).contains("true");
    releaseTheRuns();
    awaitTheRun();
    assertThat(store.load().orElseThrow().current().model()).isEqualTo(NEW_MODEL + "-c");
  }

  @Test
  void aRefillOfADeadInstanceIsTakenOverAndCompleted() throws IOException {
    Document document = indexed("Bewohnerparken", "Zone A");
    jdbcTemplate.update(
        "UPDATE vector_store SET embedding = NULL WHERE metadata->>'document_id' = ?",
        document.getId().toString());
    UUID dead = deadInstance();
    jdbcTemplate.update(
        "UPDATE embedding_index SET run_status = 'RUNNING', run_kind = 'REFILL',"
            + " run_generation = generation, run_owner_instance_id = ?, run_started_at = now(),"
            + " run_organization_id = ?",
        dead,
        Organization.DEFAULT_ID);

    reindexService.takeOverOrphanedRun();
    awaitTheRun();

    assertThat(store.load().orElseThrow().run().status()).isEqualTo(RunStatus.COMPLETED);
    assertThat(columnCountOf(document, "embedding IS NULL")).isZero();
  }

  // --- generation guards ---

  @Test
  void aModelRowNamingNeitherColumnGetsAGenerationOfItsOwnAtTheStart() throws IOException {
    Document document = indexed("Bewohnerparken", "Zone A");
    long current = store.load().orElseThrow().current().generation();
    jdbcTemplate.update("UPDATE embedding_model SET model_identifier = 'von-hand-geaendert'");

    reconciler.reconcile();

    EmbeddingIndexRecord record = store.load().orElseThrow();
    assertThat(record.target().generation())
        .isEqualTo(modelGeneration())
        .isNotEqualTo(record.current().generation());
    assertThat(record.current().generation()).isEqualTo(current);
    org.springframework.ai.document.Document chunk = chunkOf(document);
    writer.writeEmbeddedChunks(List.of(chunk), List.of(constant(0.5f)), current);
    assertThat(vectorOf(chunk, "embedding")).isEqualTo(literal(0.5f));
    assertThat(vectorOf(chunk, "embedding_next"))
        .as("a vector of the embedding column never lands in the target column")
        .isNull();
    writer.writeEmbeddedChunks(
        List.of(chunk), List.of(constant(0.25f)), record.target().generation());
    assertThat(vectorOf(chunk, "embedding_next")).isEqualTo(literal(0.25f));
    assertThat(vectorOf(chunk, "embedding")).isEqualTo(literal(0.5f));
  }

  @Test
  void aWriteUnderAGenerationNoColumnCarriesWritesNeitherVectorNorFullText() {
    UUID documentId = UUID.randomUUID();
    org.springframework.ai.document.Document chunk =
        org.springframework.ai.document.Document.builder()
            .id(UUID.randomUUID().toString())
            .text("verwaist")
            .metadata(Map.of("document_id", documentId.toString()))
            .build();

    assertThatThrownBy(
            () -> writer.writeEmbeddedChunks(List.of(chunk), List.of(fakeVector()), 999_999L))
        .isInstanceOf(EmbeddingGenerationChangedException.class);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE id = ?::uuid", Long.class, chunk.getId()))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunk_full_text WHERE document_id = ?",
                Long.class,
                documentId))
        .isZero();
  }

  @Test
  void theSearchFindsNothingUnderAGenerationTheDatabaseNoLongerNames() throws IOException {
    indexed("Bewohnerparken", "Zone A");
    SearchRequest request = SearchRequest.builder().query("Bewohnerparken").topK(5).build();
    assertThat(vectorChunkSearch.similaritySearch(request)).isNotEmpty();

    jdbcTemplate.update("UPDATE embedding_index SET generation = generation + 1000");

    assertThat(vectorChunkSearch.similaritySearch(request))
        .as("this instance has not heard of the switch yet; the database has")
        .isEmpty();
  }

  // --- endpoint change ---

  @Test
  void theSameModelMovesToAnotherEndpointWithoutAReembedding() throws IOException {
    indexed("Bewohnerparken", "Zone A");
    anchorRecorder.recordIfMissing();
    assertThat(anchorRecorded()).isTrue();
    long generation = modelGeneration();

    changes.changeEndpoint(
        new Draft(OTHER_ENDPOINT, modelName(), "", null), adminId, Organization.DEFAULT_ID);

    assertThat(modelBaseUrl()).isEqualTo(OTHER_ENDPOINT);
    assertThat(modelGeneration()).isEqualTo(generation);
    assertThat(store.load().orElseThrow().current().baseUrl()).isEqualTo(OTHER_ENDPOINT);
    assertThat(state.isStale()).isFalse();
    assertThat(auditPayload("EMBEDDING_MODEL_ENDPOINT_CHANGED", "similarity")).isNotEmpty();
  }

  @Test
  void anEndpointThatEmbedsOtherwiseIsRefusedAsAnEndpointChange() {
    anchorRecorder.recordIfMissing();
    assertThat(anchorRecorded()).isTrue();
    probe.answerWith((endpoint, texts) -> dissimilarProbe(texts));

    assertThatThrownBy(
            () ->
                changes.changeEndpoint(
                    new Draft(OTHER_ENDPOINT, modelName(), "", null),
                    adminId,
                    Organization.DEFAULT_ID))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Ähnlichkeit");
    assertThat(modelBaseUrl()).isNotEqualTo(OTHER_ENDPOINT);
  }

  @Test
  void withoutAnAnchorTheStoredEndpointIsProbedAndItsSilenceRefusesTheEndpointChange() {
    jdbcTemplate.update("UPDATE embedding_model SET reference_vectors = NULL");
    String stored = modelBaseUrl();
    probe.answerWith(
        (endpoint, texts) ->
            endpoint.baseUrl().equals(stored)
                ? new ProbeOutcome(false, "nicht erreichbar", null)
                : FakeEmbeddingModel.probeOutcome(texts));

    assertThatThrownBy(
            () ->
                changes.changeEndpoint(
                    new Draft(OTHER_ENDPOINT, modelName(), "", null),
                    adminId,
                    Organization.DEFAULT_ID))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Wechsel des Modells");

    probe.reset();
    changes.changeEndpoint(
        new Draft(OTHER_ENDPOINT, modelName(), "", null), adminId, Organization.DEFAULT_ID);
    assertThat(modelBaseUrl()).isEqualTo(OTHER_ENDPOINT);
  }

  @Test
  void aReplacementEndpointServesTheTargetModelOfAStoppedChange() throws IOException {
    indexed("Bewohnerparken", "SCHRANKE-ACHT");
    holdTheRunAt("SCHRANKE-ACHT");
    changes.confirm(
        draft(NEW_MODEL), FakeEmbeddingModel.DIMENSIONS, adminId, Organization.DEFAULT_ID);
    long target = modelGeneration();

    assertThatThrownBy(
            () ->
                changes.changeEndpoint(
                    new Draft(OTHER_ENDPOINT, NEW_MODEL, "", null),
                    adminId,
                    Organization.DEFAULT_ID))
        .as("not while the run works")
        .isInstanceOf(ConflictException.class);
    reindexService.cancel();
    releaseTheRuns();
    awaitTheRun();

    changes.changeEndpoint(
        new Draft(OTHER_ENDPOINT, NEW_MODEL, "", null), adminId, Organization.DEFAULT_ID);

    EmbeddingIndexRecord record = store.load().orElseThrow();
    assertThat(modelGeneration()).isEqualTo(target);
    assertThat(record.target().baseUrl()).isEqualTo(OTHER_ENDPOINT);
    assertThat(auditPayload("EMBEDDING_MODEL_ENDPOINT_CHANGED", "duringChange")).contains("true");
  }

  @Test
  void aStoredKeyNeverFollowsTheAddressToAnotherEndpoint() {
    jdbcTemplate.update(
        "UPDATE embedding_model SET api_key_ciphertext = ? WHERE id = 1",
        settingsCiphertext("sk-gespeichert"));

    assertThatThrownBy(() -> changes.probe(new Draft(OTHER_ENDPOINT, NEW_MODEL, null, null)))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .containsExactly("embeddingModel.apiKey"));
  }

  // --- version condition, limits, secrets ---

  @Test
  void everyStepWaitsForTheInstancesToRunOneVersion() throws IOException {
    indexed("Bewohnerparken", "Zone A");
    liveInstanceOfVersion("99.0.0");

    assertThatThrownBy(
            () ->
                changes.confirm(
                    draft(NEW_MODEL),
                    FakeEmbeddingModel.DIMENSIONS,
                    adminId,
                    Organization.DEFAULT_ID))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Version");
    assertThatThrownBy(() -> changes.discard(adminId, Organization.DEFAULT_ID))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Version");
    assertThatThrownBy(
            () ->
                changes.changeEndpoint(
                    new Draft(OTHER_ENDPOINT, modelName(), "", null),
                    adminId,
                    Organization.DEFAULT_ID))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Version");
    assertThat(modelName()).isNotEqualTo(NEW_MODEL);
  }

  @Test
  void exhaustedProbesLockOutNeitherTheEndpointChangeNorTheDiscard() {
    for (int i = 0; i < EmbeddingModelChangeService.CALLS_PER_WINDOW; i++) {
      changes.probe(draft(NEW_MODEL));
    }

    assertThatThrownBy(() -> changes.probe(draft(NEW_MODEL)))
        .isInstanceOf(TooManyRequestsException.class);
    changes.changeEndpoint(
        new Draft(OTHER_ENDPOINT, modelName(), "", null), adminId, Organization.DEFAULT_ID);
    assertThat(modelBaseUrl()).isEqualTo(OTHER_ENDPOINT);
    assertThatThrownBy(() -> changes.discard(adminId, Organization.DEFAULT_ID))
        .as("refused for nothing to discard, not for the limit")
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void theKeyAppearsNeitherInTheAuditLogNorInTheLog(CapturedOutput output) throws IOException {
    indexed("Bewohnerparken", "Zone A");
    String key = "sk-streng-geheim-" + UUID.randomUUID();

    changes.confirm(
        new Draft(OTHER_ENDPOINT, NEW_MODEL, key, null),
        FakeEmbeddingModel.DIMENSIONS,
        adminId,
        Organization.DEFAULT_ID);
    awaitTheRun();

    List<String> payloads =
        jdbcTemplate.queryForList(
            "SELECT coalesce(before, '') || coalesce(after, '') FROM audit_log"
                + " WHERE event_type = 'EMBEDDING_MODEL_CHANGED' AND recorded_at >= ?",
            String.class,
            testStart);
    assertThat(payloads).hasSize(1);
    assertThat(payloads.getFirst()).doesNotContain(key).contains("\"apiKeyChange\"");
    assertThat(output.getAll()).doesNotContain(key);
  }

  // --- locks between writers and changes, races ---

  // regression guard for ADR-0061, Entscheidung 6: a writer waiting behind a queued change must
  // not overtake it, or a stream of writers starves the change
  @Test
  void aConfirmationQueuedBehindAWriterHoldsBackTheWritersArrivingAfterIt() throws Exception {
    Document first = indexed("Bewohnerparken", "Zone A");
    Document second = indexed("Anwohnerparkausweis", "Zone B");
    long generation = store.load().orElseThrow().current().generation();
    CountDownLatch firstHolds = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    heldRuns.add(releaseFirst);
    CompletableFuture<Void> firstWriter =
        CompletableFuture.runAsync(
            () ->
                writeInOneTransaction(
                    chunkOf(first),
                    generation,
                    () -> {
                      firstHolds.countDown();
                      awaitQuietly(releaseFirst);
                    }));
    pending.add(firstWriter);
    assertThat(firstHolds.await(30, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<Void> confirmation =
        CompletableFuture.runAsync(
            () ->
                changes.confirm(
                    draft(NEW_MODEL),
                    FakeEmbeddingModel.DIMENSIONS,
                    adminId,
                    Organization.DEFAULT_ID));
    pending.add(confirmation);
    await().atMost(Duration.ofSeconds(9)).until(() -> lockWaiters() >= 1);

    CompletableFuture<Void> secondWriter =
        CompletableFuture.runAsync(
            () -> writeInOneTransaction(chunkOf(second), generation, () -> {}));
    pending.add(secondWriter);

    assertThatThrownBy(() -> secondWriter.get(2, TimeUnit.SECONDS))
        .as("a writer arriving after the queued change waits for it")
        .isInstanceOf(java.util.concurrent.TimeoutException.class);
    releaseFirst.countDown();
    firstWriter.get(30, TimeUnit.SECONDS);
    confirmation.get(30, TimeUnit.SECONDS);
    secondWriter.get(30, TimeUnit.SECONDS);
    assertThat(modelName()).isEqualTo(NEW_MODEL);
  }

  @Test
  void aConfirmationBehindAWriterBeyondTheWaitIsAConflictThatChangesNothing() throws Exception {
    Document document = indexed("Bewohnerparken", "Zone A");
    long generation = store.load().orElseThrow().current().generation();
    CountDownLatch holds = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    heldRuns.add(release);
    CompletableFuture<Void> writer =
        CompletableFuture.runAsync(
            () ->
                writeInOneTransaction(
                    chunkOf(document),
                    generation,
                    () -> {
                      holds.countDown();
                      awaitQuietly(release);
                    }));
    pending.add(writer);
    assertThat(holds.await(30, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(
            () ->
                changes.confirm(
                    draft(NEW_MODEL),
                    FakeEmbeddingModel.DIMENSIONS,
                    adminId,
                    Organization.DEFAULT_ID))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("belegt");
    release.countDown();
    writer.get(30, TimeUnit.SECONDS);
    assertThat(modelName()).isNotEqualTo(NEW_MODEL);
    assertThat(previousRows()).isZero();
  }

  // regression guard for ADR-0061, Entscheidung 6: the startup reconciliation and a writer take
  // the index row and the vector table in the same order and never deadlock
  @Test
  void theStartupReconciliationAndAWriterOfAnotherInstanceDoNotDeadlock() throws Exception {
    Document document = indexed("Bewohnerparken", "Zone A");
    long generation = store.load().orElseThrow().current().generation();
    org.springframework.ai.document.Document chunk = chunkOf(document);
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (Statement statement = holder.createStatement()) {
        statement.execute(
            "SELECT pg_advisory_xact_lock("
                + VectorStoreWriter.DOCUMENT_CHUNK_LOCK_NAMESPACE
                + ", hashtext('"
                + document.getId()
                + "'))");
      }
      CompletableFuture<Integer> writer =
          CompletableFuture.supplyAsync(
              () ->
                  new TransactionTemplate(transactionManager)
                      .execute(
                          status ->
                              otherInstanceWriter()
                                  .rewriteEmbeddedChunks(
                                      List.of(chunk), List.of(fakeVector()), generation)));
      pending.add(writer);
      await().atMost(Duration.ofSeconds(20)).until(() -> lockWaiters() >= 1);
      jdbcTemplate.update("UPDATE embedding_model SET model_identifier = 'von-hand-geaendert'");
      CompletableFuture<Void> reconciliation = CompletableFuture.runAsync(reconciler::reconcile);
      pending.add(reconciliation);
      await().atMost(Duration.ofSeconds(20)).until(() -> lockWaiters() >= 2);
      holder.rollback();

      assertThat(writer.get(60, TimeUnit.SECONDS)).isOne();
      reconciliation.get(60, TimeUnit.SECONDS);
    }
    assertThat(state.isStale()).isTrue();
  }

  // regression guard for ADR-0061, Entscheidung 5: an endpoint change checked against one model
  // never lands in the row of another that a change put there meanwhile
  @Test
  void anEndpointChangeOvertakenByAModelChangeIsRefused() {
    jdbcTemplate.update("UPDATE embedding_model SET reference_vectors = NULL");
    String stored = modelBaseUrl();
    probe.answerWith(
        (endpoint, texts) -> {
          if (endpoint.baseUrl().equals(stored)) {
            jdbcTemplate.update(
                "UPDATE embedding_model SET model_identifier = 'zwischendurch-gewechselt',"
                    + " generation = generation + 100");
          }
          return FakeEmbeddingModel.probeOutcome(texts);
        });

    assertThatThrownBy(
            () ->
                changes.changeEndpoint(
                    new Draft(OTHER_ENDPOINT, modelName(), "", null),
                    adminId,
                    Organization.DEFAULT_ID))
        .isInstanceOf(ConflictException.class);
    assertThat(modelBaseUrl()).isEqualTo(stored);
  }

  @Test
  void aConfirmationOvertakenByAnotherChangeIsRefused() throws IOException {
    indexed("Bewohnerparken", "Zone A");
    long[] calls = {0};
    probe.answerWith(
        (endpoint, texts) -> {
          if (texts.size() == io.opaa.llm.EmbeddingAnchor.TEXTS.size() && calls[0]++ == 0) {
            jdbcTemplate.update("UPDATE embedding_model SET generation = generation + 100");
          }
          return FakeEmbeddingModel.probeOutcome(texts);
        });
    long generation = modelGeneration() + 100;

    assertThatThrownBy(
            () ->
                changes.confirm(
                    draft(NEW_MODEL),
                    FakeEmbeddingModel.DIMENSIONS,
                    adminId,
                    Organization.DEFAULT_ID))
        .isInstanceOf(ConflictException.class);
    assertThat(modelName()).isNotEqualTo(NEW_MODEL);
    assertThat(modelGeneration()).isEqualTo(generation);
    assertThat(previousRows()).isZero();
  }

  // regression guard for ADR-0061, Entscheidung 2: a switch by the previous version leaves the
  // target generation behind without a target model; the index is taken as current, not re-embedded
  @Test
  void aSwitchByThePreviousVersionNeedsNoFurtherReembedding() throws IOException {
    Document document = indexed("Bewohnerparken", "Zone A");
    long current = store.load().orElseThrow().current().generation();
    long switched = current + 7;
    jdbcTemplate.update("UPDATE embedding_model SET generation = ?", switched);
    jdbcTemplate.update(
        "UPDATE embedding_index SET target_model = NULL, target_dimensions = NULL,"
            + " target_generation = ?",
        switched);

    reconciler.reconcile();

    assertThat(state.isStale()).isFalse();
    assertThat(store.load().orElseThrow().current().generation()).isEqualTo(switched);
    writer.writeEmbeddedChunks(List.of(chunkOf(document)), List.of(constant(0.5f)), switched);
    assertThat(vectorOf(chunkOf(document), "embedding")).isEqualTo(literal(0.5f));
  }

  @Test
  void aResumedReembeddingRunsForTheTargetGenerationOfTheRecord() throws Exception {
    indexed("Bewohnerparken", "SCHRANKE-NEUN");
    CountDownLatch held = holdTheRunAt("SCHRANKE-NEUN");
    changes.confirm(
        draft(NEW_MODEL), FakeEmbeddingModel.DIMENSIONS, adminId, Organization.DEFAULT_ID);
    awaitHeld(held);
    reindexService.cancel();
    releaseTheRuns();
    awaitTheRun();
    long target = store.load().orElseThrow().target().generation();
    Snapshot snapshot = state.snapshot();
    state.exclusively(
        () -> {
          state.replace(
              new Snapshot(
                  snapshot.current(),
                  snapshot.configured(),
                  snapshot.target().withGeneration(9999)));
          return null;
        });

    reindexService.start(adminId, Organization.DEFAULT_ID);

    assertThat(store.load().orElseThrow().run().generation()).isEqualTo(target);
    awaitTheRun();
  }

  @Test
  void aModelBeyondTheSizeTheVectorIndexTakesFailsAtTheProbe() {
    int size = vectorTableCreator.maxDimensions() + 1;
    probe.answerWith(
        (endpoint, texts) -> {
          List<float[]> vectors = new ArrayList<>();
          for (int i = 0; i < texts.size(); i++) {
            vectors.add(constant(0.5f, size));
          }
          return ProbeOutcome.succeeded(vectors, 1_000_000L);
        });

    EmbeddingModelChangeService.ProbeResult result = changes.probe(draft(NEW_MODEL));

    assertThat(result.succeeded()).isFalse();
    assertThat(result.message()).contains(Integer.toString(size)).contains("höchstens");
    assertThatThrownBy(
            () -> changes.confirm(draft(NEW_MODEL), size, adminId, Organization.DEFAULT_ID))
        .isInstanceOf(FieldValidationException.class);
    assertThat(previousRows()).isZero();
  }

  /** A writer of another instance: its own column state, nothing of this instance's in between. */
  private VectorStoreWriter otherInstanceWriter() {
    return new VectorStoreWriter(
        jdbcTemplate,
        fullTextChunkStore,
        new EmbeddingIndexState(),
        store,
        objectMapper,
        vectorTable);
  }

  /** Writes {@code chunk} in a transaction of its own and runs {@code beforeCommit} inside it. */
  private void writeInOneTransaction(
      org.springframework.ai.document.Document chunk, long generation, Runnable beforeCommit) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              otherInstanceWriter()
                  .writeEmbeddedChunks(List.of(chunk), List.of(fakeVector()), generation);
              beforeCommit.run();
            });
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(RUN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Sessions of this database waiting for a lock. */
  private long lockWaiters() {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                + " AND wait_event_type = 'Lock'",
            Long.class);
    return count == null ? 0 : count;
  }

  // --- helpers ---

  /** A draft at the stored endpoint, whose stored key it keeps. */
  private Draft draft(String model) {
    return new Draft(modelBaseUrl(), model, null, null);
  }

  /** Vectors that point another way for every text: another version under the same name. */
  private static ProbeOutcome dissimilarProbe(List<String> texts) {
    List<float[]> vectors = new ArrayList<>();
    for (int t = 0; t < texts.size(); t++) {
      float[] vector = new float[FakeEmbeddingModel.DIMENSIONS];
      for (int i = 0; i < vector.length; i++) {
        vector[i] = (float) Math.cos(i * 0.37 + t);
      }
      vectors.add(vector);
    }
    return ProbeOutcome.succeeded(vectors, 1_000_000L);
  }

  private FakeEmbeddingModel fakeModel() {
    return (FakeEmbeddingModel) embeddingModel;
  }

  /**
   * Holds the next embedding of a text with {@code marker} until the test lets it go; the returned
   * latch opens once it is held.
   */
  private CountDownLatch holdTheRunAt(String marker) {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch entered = new CountDownLatch(1);
    heldRuns.add(release);
    fakeModel()
        .beforeEmbeddingTextContaining(
            marker,
            () -> {
              entered.countDown();
              try {
                release.await(RUN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    return entered;
  }

  private static void awaitHeld(CountDownLatch entered) throws InterruptedException {
    assertThat(entered.await(RUN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
  }

  private void releaseFirstHeldRun() {
    heldRuns.getFirst().countDown();
  }

  private void releaseTheRuns() {
    heldRuns.forEach(CountDownLatch::countDown);
  }

  private void awaitTheRun() {
    await().atMost(RUN_TIMEOUT).until(() -> !reindexService.progress().running());
  }

  private String modelName() {
    return jdbcTemplate.queryForObject(
        "SELECT model_identifier FROM embedding_model WHERE id = 1", String.class);
  }

  private String modelBaseUrl() {
    return jdbcTemplate.queryForObject(
        "SELECT base_url FROM embedding_model WHERE id = 1", String.class);
  }

  private long modelGeneration() {
    Long generation =
        jdbcTemplate.queryForObject(
            "SELECT generation FROM embedding_model WHERE id = 1", Long.class);
    return generation == null ? 0 : generation;
  }

  private boolean anchorRecorded() {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "SELECT reference_vectors IS NOT NULL FROM embedding_model WHERE id = 1",
            Boolean.class));
  }

  private long previousRows() {
    Long count =
        jdbcTemplate.queryForObject("SELECT count(*) FROM embedding_model_previous", Long.class);
    return count == null ? 0 : count;
  }

  /** Events of {@code type} recorded since this test method started. */
  private long auditEvents(String type) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log WHERE event_type = ? AND recorded_at >= ?",
            Long.class,
            type,
            testStart);
    return count == null ? 0 : count;
  }

  private String auditPayload(String type, String key) {
    List<String> values =
        jdbcTemplate.queryForList(
            "SELECT after::jsonb->>? FROM audit_log WHERE event_type = ? AND recorded_at >= ?"
                + " ORDER BY recorded_at DESC LIMIT 1",
            String.class,
            key,
            type,
            testStart);
    return values.isEmpty() || values.getFirst() == null ? "" : values.getFirst();
  }

  private long columnCountOf(Document document, String condition) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = ? AND " + condition,
            Long.class,
            document.getId().toString());
    return count == null ? 0 : count;
  }

  private org.springframework.ai.document.Document chunkOf(Document document) {
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT id::text AS id, content, metadata::text AS metadata FROM vector_store"
                + " WHERE metadata->>'document_id' = ? LIMIT 1",
            document.getId().toString());
    return org.springframework.ai.document.Document.builder()
        .id((String) row.get("id"))
        .text((String) row.get("content"))
        .metadata(
            Map.of(
                "document_id",
                document.getId().toString(),
                "library_id",
                library.getId().toString()))
        .build();
  }

  private String vectorOf(org.springframework.ai.document.Document chunk, String column) {
    return jdbcTemplate.queryForObject(
        "SELECT " + column + "::text FROM vector_store WHERE id = ?::uuid",
        String.class,
        chunk.getId());
  }

  private static float[] constant(float value) {
    return constant(value, FakeEmbeddingModel.DIMENSIONS);
  }

  private static float[] constant(float value, int size) {
    float[] vector = new float[size];
    java.util.Arrays.fill(vector, value);
    return vector;
  }

  private String literal(float value) {
    return jdbcTemplate.queryForObject(
        "SELECT ?::vector::text",
        String.class,
        "["
            + String.join(",", Collections.nCopies(FakeEmbeddingModel.DIMENSIONS, value + ""))
            + "]");
  }

  private static float[] fakeVector() {
    return FakeEmbeddingModel.probeOutcome(List.of("x")).vectors().getFirst();
  }

  private String settingsCiphertext(String key) {
    return settingsEncryptor.encrypt(key);
  }

  private void createVectorStore() {
    jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
    jdbcTemplate.execute(
        "CREATE TABLE IF NOT EXISTS vector_store (id uuid PRIMARY KEY DEFAULT gen_random_uuid(),"
            + " content text, metadata json, embedding vector("
            + FakeEmbeddingModel.DIMENSIONS
            + "))");
  }

  private Document indexed(String title, String marker) throws IOException {
    String fileName = title.toLowerCase(java.util.Locale.ROOT) + "-" + UUID.randomUUID() + ".md";
    Path file = classTempDir.resolve(fileName);
    Files.writeString(
        file,
        """
        # %s

        ## § 1 Geltungsbereich

        Diese Regelung gilt für %s im Stadtgebiet. Der Antrag wird beim Bürgerbüro gestellt.
        """
            .formatted(title, marker),
        StandardCharsets.UTF_8);
    assertThat(
            documentIngestService.ingest(
                DocumentIngest.builder(library)
                    .file(file)
                    .filePath(file.toString())
                    .fileName(fileName)
                    .sourceType(SourceTypes.FILESYSTEM)
                    .build(),
                null))
        .isEqualTo(DocumentIngestResult.PROCESSED);
    return documentRepository.findByLibraryId(library.getId()).stream()
        .filter(document -> fileName.equals(document.getFileName()))
        .findFirst()
        .orElseThrow();
  }

  private UUID admin() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Modellwechsel', now(), ?, ?)",
        id,
        "embedding-change-" + id,
        "embedding-change-" + id + "@example.com",
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    return id;
  }

  private void liveInstanceOfVersion(String version) {
    jdbcTemplate.update(
        "INSERT INTO backend_instances (id, host_name, version, oldest_compatible_version,"
            + " started_at, heartbeat_at, fingerprint, fingerprint_hash) VALUES (?,"
            + " 'embedding-change', ?, ?, clock_timestamp(), clock_timestamp(), '{}', 'x')",
        UUID.randomUUID(),
        version,
        version);
  }

  private UUID deadInstance() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO backend_instances (id, host_name, version, oldest_compatible_version,"
            + " started_at, heartbeat_at, fingerprint, fingerprint_hash) VALUES (?,"
            + " 'embedding-change', '0.0.1', '0.0.1', clock_timestamp() - interval '2 days',"
            + " clock_timestamp() - interval '1 day', '{}', 'x')",
        id);
    return id;
  }
}
