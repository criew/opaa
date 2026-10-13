package io.opaa.indexing.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.SystemRole;
import io.opaa.common.ConflictException;
import io.opaa.indexing.chunk.EmbeddingIndexReconciler;
import io.opaa.indexing.chunk.EmbeddingIndexRecord;
import io.opaa.indexing.chunk.EmbeddingIndexRecord.Run;
import io.opaa.indexing.chunk.EmbeddingIndexRecord.RunStatus;
import io.opaa.indexing.chunk.EmbeddingIndexState;
import io.opaa.indexing.chunk.EmbeddingIndexStore;
import io.opaa.indexing.chunk.VectorIndexSchema;
import io.opaa.indexing.document.DocumentIngest;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.organization.Organization;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.searchadmin.SearchPathStatus;
import io.opaa.searchadmin.SearchStatusService;
import io.opaa.test.OpaaPropertyVariantIntegrationTest;
import io.opaa.test.OpaaTestDirectory;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.SourceTypes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The re-embedding after a change of the embedding model, end to end against pgvector with the
 * Liquibase schema: documents indexed as usual, then their vectors replaced by vectors of an "old
 * model" - another size, or the same size under another name. Runs in the property-variant
 * signature because it builds, alters and drops {@code vector_store} itself; with Spring AI's
 * schema initialisation off nothing else touches that table there.
 */
@OpaaPropertyVariantIntegrationTest
class EmbeddingReindexIntegrationTest {

  private static final Path classTempDir = OpaaTestDirectory.subdirectory("embedding-reindex");
  private static final Duration RUN_TIMEOUT = Duration.ofSeconds(60);

  @Autowired private DocumentIngestService documentIngestService;
  @Autowired private EmbeddingReindexService reindexService;
  @Autowired private ContextPrefixRerunService rerunService;
  @Autowired private EmbeddingIndexReconciler reconciler;
  @Autowired private EmbeddingIndexState state;
  @Autowired private EmbeddingIndexStore store;
  @Autowired private VectorIndexSchema schema;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private LibraryAccessService accessService;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private SearchStatusService searchStatusService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private KnowledgeLibrary library;
  private UUID ownerId;

  @BeforeEach
  void setUp() throws IOException {
    removeOwnFixtures();
    createVectorStore();
    reconciler.reconcile();
    ownerId = user();
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Neueinbettung",
                null,
                ownerId,
                SourceTypes.FILESYSTEM,
                classTempDir.toString(),
                null,
                null));
    grantRepository.save(
        AssetGrant.forUser(
            KnowledgeLibrary.ASSET_TYPE,
            library.getId(),
            Organization.DEFAULT_ID,
            ownerId,
            AssetRole.OWNER,
            null,
            ownerId));
    accessService.invalidateLibrary(library.getId());
    Files.createDirectories(classTempDir);
  }

  /**
   * Both hooks: the library goes through the regular deletion path, which needs the table, and the
   * table and identity row are then put back to what the signature starts with.
   */
  @AfterEach
  void removeOwnFixtures() {
    await().atMost(RUN_TIMEOUT).until(() -> !reindexService.progress().running());
    if (schema.columnDimensions(EmbeddingIndexState.EMBEDDING_COLUMN) == null) {
      createVectorStore();
    }
    List<UUID> ownLibraryIds =
        jdbcTemplate.queryForList("SELECT id FROM assets WHERE name = 'Neueinbettung'", UUID.class);
    ownLibraryFixtures.removeLibraries(ownLibraryIds.toArray(new UUID[0]));
    jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'embedding-reindex-%'");
    jdbcTemplate.execute("DROP TABLE IF EXISTS vector_store");
    jdbcTemplate.update("DELETE FROM embedding_index");
    reconciler.reconcile();
  }

  @Test
  void aChangedVectorSizeIsReembeddedInPlaceAndTheColumnTakesTheNewSize() throws IOException {
    Document first = indexed("satzung-a.md");
    Document second = indexed("satzung-b.md");
    Map<UUID, List<UUID>> chunkIdsBefore = chunkIdsOf(first, second);
    Map<String, Long> surroundingsBefore = surroundingRowCounts();
    long completionsBefore = auditEvents("EMBEDDING_REINDEX_COMPLETED");
    replaceVectorsByAnOldModel(768);

    reconciler.reconcile();

    assertThat(state.isStale()).isTrue();
    assertThat(schema.columnDimensions(EmbeddingIndexState.EMBEDDING_COLUMN)).isEqualTo(768);

    reindexService.start(ownerId, Organization.DEFAULT_ID);
    awaitTheRun();

    EmbeddingIndexRecord record = store.load().orElseThrow();
    assertThat(record.run().status()).isEqualTo(RunStatus.COMPLETED);
    assertThat(record.current().model()).isEqualTo("nomic-embed-text");
    assertThat(record.current().dimensions()).isEqualTo(1536);
    assertThat(record.target()).isNull();
    assertThat(state.isStale()).isFalse();
    assertThat(schema.columnDimensions(EmbeddingIndexState.EMBEDDING_COLUMN)).isEqualTo(1536);
    assertThat(schema.columnDimensions(EmbeddingIndexState.TARGET_COLUMN)).isNull();
    assertThat(schema.chunksWithout(EmbeddingIndexState.EMBEDDING_COLUMN)).isZero();
    assertThat(chunkIdsOf(first, second))
        .as("chunks are re-embedded under their own ids, not re-chunked")
        .isEqualTo(chunkIdsBefore);
    assertThat(documentRepository.findById(first.getId())).isPresent();
    assertThat(documentRepository.findById(second.getId())).isPresent();
    assertThat(surroundingRowCounts())
        .as("accounts, grants, spaces and chats are untouched")
        .isEqualTo(surroundingsBefore);
    assertThat(auditEvents("EMBEDDING_REINDEX_COMPLETED")).isEqualTo(completionsBefore + 1);
  }

  @Test
  void whileTheIndexIsStaleNewChunksGetOnlyAVectorOfTheNewModelAndTheFullTextStaysSearchable()
      throws IOException {
    Document old = indexed("satzung-a.md");
    replaceVectorsByAnOldModel(768);
    reconciler.reconcile();

    Document fresh = indexed("satzung-b.md");

    assertThat(columnCountOf(fresh, "embedding IS NULL AND embedding_next IS NOT NULL"))
        .as("a vector of the new model never lands in the column of the old one")
        .isEqualTo(chunkIdsOf(fresh).get(fresh.getId()).size());
    assertThat(columnCountOf(old, "embedding IS NOT NULL AND embedding_next IS NULL"))
        .isEqualTo(chunkIdsOf(old).get(old.getId()).size());
    assertThat(fullTextMatches(old, "Personalausweis")).isPositive();
    assertThat(searchStatusService.statusForOrganization(Organization.DEFAULT_ID).searchPaths())
        .filteredOn(path -> path.path() == SearchPathStatus.SearchPathName.VECTOR)
        .singleElement()
        .satisfies(
            path ->
                assertThat(path.condition())
                    .isEqualTo(SearchPathStatus.SearchPathCondition.SUSPENDED));
  }

  @Test
  void aRunTheProcessLeftRunningResumesAfterTheRestartAndKeepsWhatItHadEmbedded()
      throws IOException {
    Document first = indexed("satzung-a.md");
    Document second = indexed("satzung-b.md");
    Map<UUID, List<UUID>> chunkIdsBefore = chunkIdsOf(first, second);
    jdbcTemplate.update("UPDATE embedding_index SET model = 'text-embedding-3-small'");
    reconciler.reconcile();
    assertThat(state.isStale()).isTrue();
    rerunService.reembedInPlace(first);
    markRunning();

    reconciler.reconcile();
    assertThat(columnCountOf(first, "embedding_next IS NOT NULL"))
        .as("the second start keeps the target vectors of the same model")
        .isEqualTo(chunkIdsBefore.get(first.getId()).size());
    reindexService.resumeAfterRestart();
    awaitTheRun();

    assertThat(store.load().orElseThrow().run().status()).isEqualTo(RunStatus.COMPLETED);
    assertThat(state.isStale()).isFalse();
    assertThat(schema.chunksWithout(EmbeddingIndexState.EMBEDDING_COLUMN)).isZero();
    assertThat(chunkIdsOf(first, second)).isEqualTo(chunkIdsBefore);
  }

  @Test
  void aCancelledRunKeepsTheIndexStaleAndConsistentAndCanBeStartedAgain() throws IOException {
    indexed("satzung-a.md");
    replaceVectorsByAnOldModel(768);
    reconciler.reconcile();
    markRunning();

    reindexService.cancel();

    assertThat(store.load().orElseThrow().run().status()).isEqualTo(RunStatus.CANCELLED);
    assertThat(state.isStale()).isTrue();
    assertThat(schema.columnDimensions(EmbeddingIndexState.EMBEDDING_COLUMN)).isEqualTo(768);
    assertThatThrownBy(reindexService::cancel).isInstanceOf(ConflictException.class);

    reindexService.start(ownerId, Organization.DEFAULT_ID);
    awaitTheRun();

    assertThat(store.load().orElseThrow().run().status()).isEqualTo(RunStatus.COMPLETED);
    assertThat(schema.columnDimensions(EmbeddingIndexState.EMBEDDING_COLUMN)).isEqualTo(1536);
  }

  @Test
  void anIndexOfTheConfiguredModelHasNothingToReembed() {
    assertThat(state.isStale()).isFalse();

    assertThatThrownBy(() -> reindexService.start(ownerId, Organization.DEFAULT_ID))
        .isInstanceOf(ConflictException.class);
  }

  private void awaitTheRun() {
    await().atMost(RUN_TIMEOUT).until(() -> !reindexService.progress().running());
  }

  private void markRunning() {
    EmbeddingIndexRecord record = store.load().orElseThrow();
    store.save(
        record.withRun(
            new Run(
                RunStatus.RUNNING, Instant.now(), ownerId, Organization.DEFAULT_ID, null, null)));
  }

  /**
   * What an index of an older model looks like: the column of another size and filled with that
   * size, the identity naming another model.
   */
  private void replaceVectorsByAnOldModel(int dimensions) {
    jdbcTemplate.execute("ALTER TABLE vector_store DROP COLUMN embedding");
    jdbcTemplate.execute(
        "ALTER TABLE vector_store ADD COLUMN embedding vector(%d)".formatted(dimensions));
    jdbcTemplate.update(
        "UPDATE vector_store SET embedding = ?::vector",
        "[" + String.join(",", Collections.nCopies(dimensions, "0.1")) + "]");
    jdbcTemplate.update(
        "UPDATE embedding_index SET model = 'old-model', dimensions = ?", dimensions);
  }

  private void createVectorStore() {
    jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
    jdbcTemplate.execute(
        "CREATE TABLE IF NOT EXISTS vector_store (id uuid PRIMARY KEY DEFAULT gen_random_uuid(),"
            + " content text, metadata json, embedding vector(1536))");
  }

  private Map<UUID, List<UUID>> chunkIdsOf(Document... documents) {
    Map<UUID, List<UUID>> ids = new LinkedHashMap<>();
    for (Document document : documents) {
      ids.put(
          document.getId(),
          jdbcTemplate.queryForList(
              "SELECT id FROM vector_store WHERE metadata->>'document_id' = ? ORDER BY id",
              UUID.class,
              document.getId().toString()));
    }
    return ids;
  }

  private long columnCountOf(Document document, String condition) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = ? AND " + condition,
            Long.class,
            document.getId().toString());
    return count == null ? 0 : count;
  }

  private long fullTextMatches(Document document, String term) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chunk_full_text WHERE document_id = ?"
                + " AND content_tsv @@ plainto_tsquery('german', ?)",
            Long.class,
            document.getId(),
            term);
    return count == null ? 0 : count;
  }

  private Map<String, Long> surroundingRowCounts() {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String table : List.of("users", "asset_grants", "spaces", "chats", "documents")) {
      counts.put(table, jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class));
    }
    return counts;
  }

  private long auditEvents(String type) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log WHERE event_type = ?", Long.class, type);
    return count == null ? 0 : count;
  }

  private Document indexed(String fileName) throws IOException {
    Path file = classTempDir.resolve(fileName);
    Files.writeString(
        file,
        """
        # Verwaltungsgebührensatzung

        ## § 7 Gebühren für Personaldokumente

        Für die Ausstellung eines Personalausweises wird eine Gebühr von 37,00 EUR erhoben.
        Die Gebühr ist bei Antragstellung fällig und wird nicht erstattet.

        ## § 8 Befreiungen

        Von der Gebühr befreit sind Personen, die Leistungen nach dem Zweiten Buch
        Sozialgesetzbuch beziehen und dies durch einen aktuellen Bescheid nachweisen.
        """,
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

  private UUID user() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Neueinbettung', now(), ?, ?)",
        id,
        "embedding-reindex-" + id,
        "embedding-reindex-" + id + "@example.com",
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    return id;
  }
}
