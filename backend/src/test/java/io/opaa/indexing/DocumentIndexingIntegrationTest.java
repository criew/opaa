package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.opaa.FakeEmbeddingModel;
import io.opaa.api.dto.QueryResponse;
import io.opaa.auth.SystemRole;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryVisibility;
import io.opaa.organization.Organization;
import io.opaa.query.QueryService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class DocumentIndexingIntegrationTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  @TempDir static Path sharedTempDir;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("opaa.indexing.document-path", () -> sharedTempDir.toAbsolutePath().toString());
    // #484: overrides the dev profile's /data,/tmp default so this suite's own @TempDir (which is
    // neither, on most platforms/CI runners) stays inside the allowlist.
    registry.add(
        "opaa.indexing.filesystem-allowlist", () -> sharedTempDir.toAbsolutePath().toString());
    registry.add("opaa.indexing.chunk-size", () -> 100);
    // The application default overlap (100) is not smaller than the chunk size this test pins, and
    // IndexingProperties rejects that combination outright instead of clamping it silently.
    registry.add("opaa.indexing.chunk-overlap", () -> 10);
    registry.add("opaa.indexing.batch-size", () -> 10);
    registry.add("opaa.indexing.retry-attempts", () -> 1);
  }

  @TestConfiguration
  static class TestConfig {
    @Bean
    @Primary
    EmbeddingModel testEmbeddingModel() {
      return new FakeEmbeddingModel();
    }
  }

  @Autowired private DocumentIndexingService documentIndexingService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private VectorStore vectorStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private IndexingJobService indexingJobService;
  @Autowired private IndexingRunEventRepository indexingRunEventRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private QueryService queryService;
  @MockitoBean private ChatModel chatModel;

  private UUID userId;
  private UUID targetLibraryId;

  @BeforeEach
  void setUp() throws IOException {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store");
    documentRepository.deleteAll();
    indexingJobRepository.deleteAll();
    // Clean up any leftover files from previous tests
    if (Files.exists(sharedTempDir)) {
      try (var files = Files.list(sharedTempDir)) {
        files.forEach(
            f -> {
              try {
                Files.deleteIfExists(f);
              } catch (IOException e) {
                // ignore cleanup failures
              }
            });
      }
    }

    // #419: every trigger needs a caller-chosen library and a caller who actually holds at least
    // EDITOR on it - a system admin is NOT bypassed for an ordinary library any more (PR #431
    // review, Befund 2: the /trigger endpoint already requires SYSTEM_ADMIN, so bypassing the
    // EDITOR check for that flag too would make it unreachable in practice). userId is granted
    // OWNER on its own library explicitly below, exactly like a real KnowledgeLibraryService
    // library creation would. The previous run's library is deleted first -
    // fk_knowledge_libraries_owner_user is RESTRICT, so the user row cannot go while it still owns
    // one.
    jdbcTemplate.update(
        "DELETE FROM knowledge_libraries WHERE owner_user_id IN (SELECT id FROM users WHERE"
            + " email = 'indexing-it@example.com')");
    jdbcTemplate.update("DELETE FROM users WHERE email = 'indexing-it@example.com'");
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', 'indexing-it@example.com',"
            + " 'Indexing IT User', now(), ?, ?)",
        userId,
        "indexing-it-" + userId,
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);

    KnowledgeLibrary library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Zielbibliothek",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                sharedTempDir.toAbsolutePath().toString(),
                null,
                null,
                null,
                false));
    targetLibraryId = library.getId();
    grantOwner(targetLibraryId, userId);
  }

  private void grantOwner(UUID libraryId, UUID granteeId) {
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
            + " subject_user_id, role, created_at, updated_at) VALUES (?, ?, ?, 'USER', ?,"
            + " 'OWNER', now(), now())",
        UUID.randomUUID(),
        libraryId,
        Organization.DEFAULT_ID,
        granteeId);
  }

  private IndexingJob triggerIndexing() {
    return documentIndexingService.triggerIndexing(targetLibraryId, userId, true);
  }

  @Test
  void indexesDocumentsEndToEnd() throws IOException {
    Files.writeString(sharedTempDir.resolve("test.md"), "# Test Document\n\nThis is test content.");
    Files.writeString(sharedTempDir.resolve("notes.txt"), "Some plain text notes for testing.");

    IndexingJob job = triggerIndexing();
    assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);

    awaitJobCompletion(job);

    var completedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(completedJob.getDocumentsProcessed()).isEqualTo(2);
    assertThat(completedJob.getDocumentsTotal()).isEqualTo(2);
    assertThat(completedJob.getDocumentsFailed()).isZero();
    assertThat(completedJob.getDocumentsSkipped()).isZero();

    List<Document> documents = documentRepository.findAll();
    assertThat(documents).hasSize(2);
    assertThat(documents).allMatch(d -> d.getStatus() == DocumentStatus.INDEXED);
    assertThat(documents).allMatch(d -> d.getIndexedAt() != null);
    assertThat(documents).allMatch(d -> d.getChunkCount() > 0);
    assertThat(documents).allMatch(d -> d.getChecksum() != null && d.getChecksum().length() == 64);
    // #201: every document belongs to exactly one library - against the real Liquibase schema,
    // not just the mocked FileProcessingServiceTest, so a missing fk_documents_library_organization
    // constraint or a NULL library_id would fail this insert, not just this assertion.
    assertThat(documents).allMatch(d -> targetLibraryId.equals(d.getLibraryId()));
    assertThat(documents).allMatch(d -> Organization.DEFAULT_ID.equals(d.getOrganizationId()));

    // Verify chunks with embeddings were stored in vector_store
    List<org.springframework.ai.document.Document> results =
        vectorStore.similaritySearch(
            SearchRequest.builder().query("test").topK(100).similarityThreshold(0.0).build());
    assertThat(results).isNotEmpty();
    assertThat(results).allMatch(r -> r.getText() != null && !r.getText().isBlank());
    assertThat(results).allMatch(r -> r.getMetadata().containsKey("document_id"));
    assertThat(results)
        .allMatch(r -> targetLibraryId.toString().equals(r.getMetadata().get("library_id")));
    assertThat(results)
        .allMatch(
            r -> Organization.DEFAULT_ID.toString().equals(r.getMetadata().get("organization_id")));
  }

  @Test
  void skipsUnsupportedFileFormatsAndContinues() throws IOException {
    Files.writeString(sharedTempDir.resolve("good.txt"), "Valid content here.");
    Files.writeString(sharedTempDir.resolve("bad.csv"), "a,b,c");

    IndexingJob job = triggerIndexing();
    assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);

    awaitJobCompletion(job);

    var completedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    // Only .txt is a supported format, .csv is rejected by the shared format list.
    assertThat(completedJob.getDocumentsProcessed()).isEqualTo(1);
    assertThat(completedJob.getDocumentsFailed()).isZero();
    // Issue #375: a rejected document must be reported, not silently dropped. Both files were
    // found, so both are part of the job's total, and the rejected one shows up as skipped —
    // otherwise whoever runs the installation never learns that part of the stock went unindexed.
    assertThat(completedJob.getDocumentsTotal()).isEqualTo(2);
    assertThat(completedJob.getDocumentsSkipped()).isEqualTo(1);

    // #513: the run's own protocol names *why* the file was skipped, not just that it was -
    // without this, a rejected format is indistinguishable from any other skip reason once the
    // run has finished.
    List<IndexingRunEvent> events =
        indexingRunEventRepository.findByJobIdOrderByCreatedAtAsc(completedJob.getId());
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getCategory()).isEqualTo(IndexingEventCategory.UNSUPPORTED_FORMAT);
    assertThat(events.getFirst().getReference()).isEqualTo("bad.csv");
    assertThat(completedJob.getEventsTruncatedCount()).isZero();

    // Verify only the supported file was indexed
    List<Document> documents = documentRepository.findAll();
    assertThat(documents).hasSize(1);
    assertThat(documents.getFirst().getFileName()).isEqualTo("good.txt");
    assertThat(documents.getFirst().getStatus()).isEqualTo(DocumentStatus.INDEXED);
  }

  @Test
  void retainsOnlyTheLastTenRunsPerLibraryAndPrunesTheirEvents() throws IOException {
    // #513, Umfangserweiterung (Maintainer-Ergaenzung 20.08.2026): only the last 10 runs of a
    // library stay around - older ones, and their own events, are pruned once an 11th run starts.
    Files.writeString(sharedTempDir.resolve("bad.csv"), "a,b,c");

    // #604 review, nit (d): a second library's own single run, untouched by the first library's
    // eleven-run pruning below - proves retention is scoped per library, not to the first 10 rows
    // of indexing_jobs overall (which pruneOldRuns' own libraryId-scoped query would satisfy even
    // if it silently reverted to a global limit by mistake, unless another library's run is a
    // reference point for what must survive).
    KnowledgeLibrary otherLibrary =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Andere Bibliothek (Retention)",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                sharedTempDir.toAbsolutePath().toString(),
                null,
                null,
                null,
                false));
    UUID otherLibraryId = otherLibrary.getId();
    grantOwner(otherLibraryId, userId);
    IndexingJob otherLibraryJob =
        documentIndexingService.triggerIndexing(otherLibraryId, userId, true);
    awaitJobCompletion(otherLibraryJob);

    IndexingJob firstJob = triggerIndexing();
    awaitJobCompletion(firstJob);
    var firstCompleted = indexingJobRepository.findById(firstJob.getId()).orElseThrow();
    assertThat(indexingRunEventRepository.findByJobIdOrderByCreatedAtAsc(firstCompleted.getId()))
        .as("the first run's own event exists before it gets pruned")
        .isNotEmpty();

    IndexingJob lastJob = firstJob;
    for (int i = 0; i < 10; i++) {
      lastJob = triggerIndexing();
      awaitJobCompletion(lastJob);
    }

    List<IndexingJob> remainingRuns =
        indexingJobRepository.findByLibraryIdOrderByStartedAtDesc(targetLibraryId);
    assertThat(remainingRuns).hasSize(10);
    assertThat(remainingRuns).noneMatch(job -> job.getId().equals(firstJob.getId()));
    assertThat(remainingRuns.getFirst().getId()).isEqualTo(lastJob.getId());

    // The pruned run's event is gone too (fk_indexing_run_events_job's ON DELETE CASCADE) - not
    // merely orphaned and still counted somewhere.
    assertThat(indexingJobRepository.findById(firstJob.getId())).isEmpty();
    assertThat(indexingRunEventRepository.findByJobIdOrderByCreatedAtAsc(firstJob.getId()))
        .isEmpty();

    // The other library's single run survived every one of targetLibraryId's eleven triggers -
    // pruning never looked past its own libraryId.
    assertThat(indexingJobRepository.findById(otherLibraryJob.getId())).isPresent();

    jdbcTemplate.update("DELETE FROM asset_grants WHERE library_id = ?", otherLibraryId);
    jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE id = ?", otherLibraryId);
  }

  @Test
  void indexesPdfAndDocxDocuments() throws IOException {
    copyTestResource("test-documents/test-document.pdf", "report.pdf");
    copyTestResource("test-documents/test-document.docx", "notes.docx");

    IndexingJob job = triggerIndexing();
    assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);

    awaitJobCompletion(job);

    var completedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(completedJob.getDocumentsProcessed()).isEqualTo(2);
    assertThat(completedJob.getDocumentsFailed()).isZero();

    List<Document> documents = documentRepository.findAll();
    assertThat(documents).hasSize(2);
    assertThat(documents).allMatch(d -> d.getStatus() == DocumentStatus.INDEXED);
    assertThat(documents).allMatch(d -> d.getChunkCount() > 0);

    // Verify chunks were stored in vector_store
    List<org.springframework.ai.document.Document> results =
        vectorStore.similaritySearch(
            SearchRequest.builder().query("OPAA").topK(100).similarityThreshold(0.0).build());
    assertThat(results).isNotEmpty();
    assertThat(results).allMatch(r -> r.getText() != null && !r.getText().isBlank());
  }

  @Test
  void reindexingReplacesOldChunks() throws IOException {
    Files.writeString(sharedTempDir.resolve("doc.txt"), "Original content.");

    IndexingJob firstJob = triggerIndexing();
    awaitJobCompletion(firstJob);

    var completedFirstJob = indexingJobRepository.findById(firstJob.getId()).orElseThrow();
    assertThat(completedFirstJob.getDocumentsProcessed()).isEqualTo(1);
    assertThat(completedFirstJob.getDocumentsSkipped()).isZero();

    // Remember initial state
    List<org.springframework.ai.document.Document> initialResults =
        vectorStore.similaritySearch(
            SearchRequest.builder().query("content").topK(100).similarityThreshold(0.0).build());
    assertThat(initialResults).isNotEmpty();
    Document initialDoc = documentRepository.findAll().getFirst();
    assertThat(initialDoc.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(initialDoc.getChecksum()).isNotNull();
    assertThat(initialDoc.getLibraryId()).isEqualTo(targetLibraryId);

    // Update file and re-index
    Files.writeString(sharedTempDir.resolve("doc.txt"), "Updated content with more text.");
    IndexingJob secondJob = triggerIndexing();
    awaitJobCompletion(secondJob);

    var completedSecondJob = indexingJobRepository.findById(secondJob.getId()).orElseThrow();
    assertThat(completedSecondJob.getDocumentsProcessed()).isEqualTo(1);
    assertThat(completedSecondJob.getDocumentsSkipped()).isZero();
    assertThat(documentRepository.count()).isEqualTo(1);

    // Verify the document content was actually re-indexed
    Document reindexedDoc = documentRepository.findAll().getFirst();
    assertThat(reindexedDoc.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(reindexedDoc.getIndexedAt()).isNotNull();
    assertThat(reindexedDoc.getChecksum()).isNotEqualTo(initialDoc.getChecksum());
    // #201 acceptance criteria: re-indexing keeps the library assignment.
    assertThat(reindexedDoc.getLibraryId()).isEqualTo(targetLibraryId);

    // Verify chunk text was updated via similarity search
    List<org.springframework.ai.document.Document> newResults =
        vectorStore.similaritySearch(
            SearchRequest.builder().query("Updated").topK(100).similarityThreshold(0.0).build());
    assertThat(newResults).isNotEmpty();
    String allChunkText =
        newResults.stream()
            .map(org.springframework.ai.document.Document::getText)
            .reduce("", String::concat);
    assertThat(allChunkText).contains("Updated");
  }

  @Test
  void reindexingIntoADifferentLibraryLeavesNoChunksWithTheOldLibraryIdBehind() throws IOException {
    // PR #431 review, Befund 3: FileProcessingServiceTest proves this only against a mocked
    // VectorStore.delete() call - the string handed to a mock, not that the filter actually
    // matches every chunk of the old document in real pgvector. This indexes the same file twice,
    // once per library, against a real Postgres/pgvector schema and asserts by direct SQL that no
    // row in vector_store still carries the old library_id afterwards.
    // Same sourcePath as targetLibraryId's FILESYSTEM configuration (setUp) - both libraries watch
    // the same directory, so re-triggering into otherLibraryId picks up the same file that was
    // first indexed into targetLibraryId, exercising the library-move assertions below.
    KnowledgeLibrary otherLibrary =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Andere Bibliothek",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                sharedTempDir.toAbsolutePath().toString(),
                null,
                null,
                null,
                false));
    UUID otherLibraryId = otherLibrary.getId();
    grantOwner(otherLibraryId, userId);

    Files.writeString(sharedTempDir.resolve("moved.txt"), "Content that will move libraries.");

    IndexingJob firstJob = triggerIndexing();
    awaitJobCompletion(firstJob);
    assertThat(indexingJobRepository.findById(firstJob.getId()).orElseThrow().getStatus())
        .isEqualTo(JobStatus.COMPLETED);

    Long chunksInOriginalLibrary =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vector_store WHERE metadata->>'library_id' = ?",
            Long.class,
            targetLibraryId.toString());
    assertThat(chunksInOriginalLibrary).isPositive();

    IndexingJob secondJob = documentIndexingService.triggerIndexing(otherLibraryId, userId, true);
    awaitJobCompletion(secondJob);
    assertThat(indexingJobRepository.findById(secondJob.getId()).orElseThrow().getStatus())
        .isEqualTo(JobStatus.COMPLETED);

    Long chunksStillInOriginalLibrary =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vector_store WHERE metadata->>'library_id' = ?",
            Long.class,
            targetLibraryId.toString());
    assertThat(chunksStillInOriginalLibrary)
        .as("no chunk may still carry the old library_id after a move")
        .isZero();

    Long chunksInNewLibrary =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM vector_store WHERE metadata->>'library_id' = ?",
            Long.class,
            otherLibraryId.toString());
    assertThat(chunksInNewLibrary).isPositive();

    Document movedDoc = documentRepository.findAll().getFirst();
    assertThat(movedDoc.getLibraryId()).isEqualTo(otherLibraryId);
  }

  @Test
  void aUserWithAGrantOnTheTargetLibraryFindsTheDocumentAndAUserWithoutOneDoesNot()
      throws IOException {
    // PR #431 review, Befund 3: closes the gap between "indexed through the real pipeline" and
    // "findable through /api/v1/query" - QueryIntegrationTest inserts its chunks by hand and never
    // exercises FileProcessingService at all, so this is the only test proving the two are
    // actually connected for a document that carries a caller-chosen library (#419).
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    var usage =
        new Usage() {
          @Override
          public Integer getPromptTokens() {
            return 10;
          }

          @Override
          public Integer getCompletionTokens() {
            return 10;
          }

          @Override
          public Object getNativeUsage() {
            return null;
          }
        };
    var chatResponseMetadata =
        ChatResponseMetadata.builder().model("test-model").usage(usage).build();
    var assistantMessage = new AssistantMessage("Answer referencing the indexed document.");
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(List.of(new Generation(assistantMessage)), chatResponseMetadata));

    Files.writeString(
        sharedTempDir.resolve("findable.txt"), "A uniquely identifiable sentence about OPAA.");
    IndexingJob job = triggerIndexing();
    awaitJobCompletion(job);
    assertThat(indexingJobRepository.findById(job.getId()).orElseThrow().getStatus())
        .isEqualTo(JobStatus.COMPLETED);

    // userId holds OWNER on targetLibraryId (granted in setUp) - the reader path.
    QueryResponse withGrant =
        queryService.query(
            "uniquely identifiable sentence", null, userId, true, java.util.List.of());
    assertThat(withGrant.getSources())
        .as("a user with a grant on the target library must find the indexed document")
        .anyMatch(source -> "findable.txt".equals(source.getFileName()));

    // A second user in the same organization with no grant on targetLibraryId - but not with an
    // empty readableLibraryIds altogether. Coordinator follow-up on the review: a stranger with
    // zero grants anywhere would let QueryService short-circuit on an empty readable-library set
    // before ever issuing the vector search, which would pass this assertion for the wrong reason
    // (no readable library at all, not "library_id filtered it out"). Granting the stranger a
    // completely unrelated library makes readableLibraryIds non-empty, so the negative result
    // actually exercises the library_id filter in the real similarity search.
    UUID strangerId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', 'indexing-it-stranger@example.com',"
            + " 'Stranger', now(), 'USER', ?)",
        strangerId,
        "indexing-it-stranger-" + strangerId,
        Organization.DEFAULT_ID);
    KnowledgeLibrary strangerLibrary =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Bibliothek des Fremden",
                null,
                strangerId,
                LibraryVisibility.PRIVATE,
                false));
    grantOwner(strangerLibrary.getId(), strangerId);

    QueryResponse withoutGrant =
        queryService.query(
            "uniquely identifiable sentence", null, strangerId, true, java.util.List.of());
    assertThat(withoutGrant.getSources())
        .as("a user without any grant on the target library must not find the indexed document")
        .noneMatch(source -> "findable.txt".equals(source.getFileName()));

    jdbcTemplate.update("DELETE FROM asset_grants WHERE library_id = ?", strangerLibrary.getId());
    jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE id = ?", strangerLibrary.getId());
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", strangerId);
  }

  @Test
  void skipsUnchangedDocumentsOnReindex() throws IOException {
    Files.writeString(sharedTempDir.resolve("doc.txt"), "Same content.");

    IndexingJob firstJob = triggerIndexing();
    awaitJobCompletion(firstJob);

    var completedFirstJob = indexingJobRepository.findById(firstJob.getId()).orElseThrow();
    assertThat(completedFirstJob.getDocumentsProcessed()).isEqualTo(1);
    assertThat(completedFirstJob.getDocumentsSkipped()).isZero();

    // Re-index without changing the file
    IndexingJob secondJob = triggerIndexing();
    awaitJobCompletion(secondJob);

    var completedSecondJob = indexingJobRepository.findById(secondJob.getId()).orElseThrow();
    assertThat(completedSecondJob.getDocumentsProcessed()).isZero();
    assertThat(completedSecondJob.getDocumentsSkipped()).isEqualTo(1);

    // Document record should still be there, unchanged
    assertThat(documentRepository.count()).isEqualTo(1);
    Document doc = documentRepository.findAll().getFirst();
    assertThat(doc.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(doc.getChecksum()).isNotNull();
    assertThat(doc.getChecksum()).hasSize(64);
  }

  @Test
  void triggerIndexingFailsTheJobWhenSourcePathIsOutsideTheConfiguredAllowlist() {
    // #484/ADR-0018 Entscheidung 6: the allowlist is enforced again at run time
    // (AsyncIndexingExecutor),
    // not only at library creation/update time - a Bestandsbibliothek whose sourcePath the
    // operator's
    // allowlist no longer covers must not silently succeed. This library is created directly
    // against
    // the repository (bypassing KnowledgeLibraryService's own creation-time check) with a
    // sourcePath
    // outside this suite's configured allowlist (sharedTempDir), mirroring how such a library could
    // exist if the allowlist were narrowed after it was created.
    KnowledgeLibrary outsideAllowlistLibrary =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Ausserhalb der Allowlist",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                sharedTempDir
                    .resolveSibling("opaa-484-outside-allowlist")
                    .toAbsolutePath()
                    .toString(),
                null,
                null,
                null,
                false));
    grantOwner(outsideAllowlistLibrary.getId(), userId);

    IndexingJob job =
        documentIndexingService.triggerIndexing(outsideAllowlistLibrary.getId(), userId, true);
    assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);

    awaitJobCompletion(job);

    var failedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(failedJob.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(failedJob.getErrorMessage()).contains("außerhalb");
    assertThat(documentRepository.findByLibraryId(outsideAllowlistLibrary.getId())).isEmpty();

    libraryRepository.deleteById(outsideAllowlistLibrary.getId());
  }

  // --- #401: indexing_jobs organization boundary, exercised against two real organizations ---

  /**
   * #401 acceptance criteria: the status query answers only with the caller's own organization's
   * runs. Proven at two independent layers against a real, two-organization database - not just the
   * pre-existing library-ownership check ({@code
   * DocumentIndexingService#loadLibraryInOrganization}, which already 404s a foreign library) but
   * the {@code indexing_jobs} row's own {@code organization_id} (migration 049): {@link
   * IndexingJobService#getLatestJob} for organization B asking about organization A's library must
   * come back empty, exactly as if that library had never run at all - not merely blocked one layer
   * up.
   */
  @Test
  void statusQueryOnlyEverReturnsRunsBelongingToTheCallersOwnOrganization() throws IOException {
    UUID organizationA = insertOrganization("Org A 401");
    UUID organizationB = insertOrganization("Org B 401");
    UUID userInOrganizationA = insertUser(organizationA, "401-user-a@example.com");
    UUID userInOrganizationB = insertUser(organizationB, "401-user-b@example.com");
    KnowledgeLibrary libraryInOrganizationA =
        createLibraryAndGrantEditor(organizationA, userInOrganizationA, "401-org-a");

    IndexingJob job = indexingJobService.startJob(libraryInOrganizationA.getId(), organizationA);

    assertThat(
            documentIndexingService
                .getStatus(libraryInOrganizationA.getId(), userInOrganizationA, false)
                .job()
                .map(IndexingJob::getId))
        .contains(job.getId());
    // The same library, asked about by a user of a genuinely different organization: 404, not
    // merely a different (empty) status - #436's "no grant at all looks like not found" applies
    // here too, since organization B never held any grant on organization A's library.
    assertThatThrownBy(
            () ->
                documentIndexingService.getStatus(
                    libraryInOrganizationA.getId(), userInOrganizationB, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);

    // The second, independent guard this issue adds at the indexing_jobs row itself (#401): even
    // asked directly, bypassing the library-ownership check above entirely, the same libraryId
    // under the wrong organizationId comes back empty rather than leaking organization A's job.
    assertThat(indexingJobService.getLatestJob(libraryInOrganizationA.getId(), organizationB))
        .isEmpty();
    assertThat(
            indexingJobService
                .getLatestJob(libraryInOrganizationA.getId(), organizationA)
                .map(IndexingJob::getId))
        .contains(job.getId());
    assertThat(
            indexingJobRepository.existsByStatusAndLibraryIdAndOrganizationId(
                JobStatus.RUNNING, libraryInOrganizationA.getId(), organizationB))
        .isFalse();
    assertThat(
            indexingJobRepository.existsByStatusAndLibraryIdAndOrganizationId(
                JobStatus.RUNNING, libraryInOrganizationA.getId(), organizationA))
        .isTrue();
  }

  /**
   * #401 acceptance criteria: a running indexing job in one organization must not block a trigger
   * in a different organization. #478 already scoped concurrency per library rather than globally,
   * but that guarantee was previously only ever exercised with two libraries in the *same*
   * organization (see {@code
   * LibraryIndexingAuthorizationIntegrationTest#aSecondTriggerOfTheSameLibraryWhileRunningIsRejectedButAnotherLibraryRunsInParallel}).
   * This proves it holds across a genuine organization boundary too.
   */
  @Test
  void aRunningJobInOneOrganizationDoesNotBlockATriggerInAnotherOrganization() throws IOException {
    UUID organizationA = insertOrganization("Org A 401 Concurrency");
    UUID organizationB = insertOrganization("Org B 401 Concurrency");
    UUID userInOrganizationA = insertUser(organizationA, "401-conc-user-a@example.com");
    UUID userInOrganizationB = insertUser(organizationB, "401-conc-user-b@example.com");
    KnowledgeLibrary libraryInOrganizationA =
        createLibraryAndGrantEditor(organizationA, userInOrganizationA, "401-conc-org-a");
    KnowledgeLibrary libraryInOrganizationB =
        createLibraryAndGrantEditor(organizationB, userInOrganizationB, "401-conc-org-b");

    // Seeds a RUNNING row directly (mirrors IndexingJobRecoveryIntegrationTest's
    // seedOrphanedRunningJob) instead of relying on timing a real async run's RUNNING window -
    // deterministic, and it is uk_indexing_jobs_library_running (migration 028) plus
    // IndexingJobService#isJobRunning that this test actually needs held RUNNING, not a real
    // completed indexing pass.
    indexingJobService.startJob(libraryInOrganizationA.getId(), organizationA);

    IndexingJob jobInOrganizationB =
        documentIndexingService.triggerIndexing(
            libraryInOrganizationB.getId(), userInOrganizationB, false);

    assertThat(jobInOrganizationB.getStatus()).isEqualTo(JobStatus.RUNNING);
    awaitJobCompletion(jobInOrganizationB);
  }

  private UUID insertOrganization(String name) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO organizations (id, name, created_at) VALUES (?, ?, now())", id, name);
    return id;
  }

  private UUID insertUser(UUID organizationId, String email) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Test-Nutzer', now(), ?, ?)",
        id,
        "401-" + id,
        email,
        SystemRole.USER.name(),
        organizationId);
    return id;
  }

  private KnowledgeLibrary createLibraryAndGrantEditor(
      UUID organizationId, UUID ownerId, String subdirectoryName) throws IOException {
    Path libraryDir = sharedTempDir.resolve(subdirectoryName);
    Files.createDirectories(libraryDir);
    KnowledgeLibrary library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                organizationId,
                "Bibliothek " + subdirectoryName,
                null,
                ownerId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                libraryDir.toAbsolutePath().toString(),
                null,
                null,
                null,
                false));
    grantOwner(library.getId(), ownerId, organizationId);
    return library;
  }

  private void grantOwner(UUID libraryId, UUID granteeId, UUID organizationId) {
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
            + " subject_user_id, role, created_at, updated_at) VALUES (?, ?, ?, 'USER', ?,"
            + " 'OWNER', now(), now())",
        UUID.randomUUID(),
        libraryId,
        organizationId,
        granteeId);
  }

  private void awaitJobCompletion(IndexingJob job) {
    await()
        .atMost(30, TimeUnit.SECONDS)
        .until(
            () -> {
              var latestJob = indexingJobRepository.findById(job.getId()).orElseThrow();
              return latestJob.getStatus() != JobStatus.RUNNING;
            });
  }

  private void copyTestResource(String resourcePath, String targetFileName) throws IOException {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
      assertThat(in).as("Test resource %s must exist", resourcePath).isNotNull();
      Files.copy(in, sharedTempDir.resolve(targetFileName), StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
