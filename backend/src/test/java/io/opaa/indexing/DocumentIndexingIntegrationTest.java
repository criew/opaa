package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

    // Verify only the supported file was indexed
    List<Document> documents = documentRepository.findAll();
    assertThat(documents).hasSize(1);
    assertThat(documents.getFirst().getFileName()).isEqualTo("good.txt");
    assertThat(documents.getFirst().getStatus()).isEqualTo(DocumentStatus.INDEXED);
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
    KnowledgeLibrary otherLibrary =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Andere Bibliothek",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
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
    QueryResponse withGrant = queryService.query("uniquely identifiable sentence", null, userId);
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
                false,
                false));
    grantOwner(strangerLibrary.getId(), strangerId);

    QueryResponse withoutGrant =
        queryService.query("uniquely identifiable sentence", null, strangerId);
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
