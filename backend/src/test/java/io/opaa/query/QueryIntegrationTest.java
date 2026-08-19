package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.opaa.FakeEmbeddingModel;
import io.opaa.api.dto.QueryResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
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

/**
 * Exercises the permission-aware vector search (#202) end to end against a real Postgres schema:
 * the test user must actually hold an {@code AssetGrant} on the library a chunk's {@code
 * library_id} metadata points at, or {@link QueryService#query} never even calls {@link
 * VectorStore#similaritySearch} for it - see {@link #userWithoutAnyGrantSeesNothing}.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class QueryIntegrationTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("opaa.indexing.document-path", () -> "/tmp/opaa-test-docs");
  }

  @TestConfiguration
  static class TestConfig {
    @Bean
    @Primary
    EmbeddingModel testEmbeddingModel() {
      return new FakeEmbeddingModel();
    }
  }

  private static final UUID DEFAULT_ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  @MockitoBean private ChatModel chatModel;

  @Autowired private VectorStore vectorStore;
  @Autowired private QueryService queryService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID userId;
  private UUID libraryId;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store");
    // Spring AI 2.0 merges ChatModel.getOptions() into every request; a bare mock returns null
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());

    userId = UUID.randomUUID();
    libraryId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, ?, ?, ?, now(), 'USER', ?)",
        userId,
        "query-it-" + userId,
        "test-issuer",
        "query-it@example.com",
        "Query IT User",
        DEFAULT_ORGANIZATION_ID);
    jdbcTemplate.update(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, listed, personal, source_type, created_at, updated_at)"
            + " VALUES (?, ?, 'IT-Bibliothek', 'USER', ?, 'PRIVATE', false, false, 'UPLOAD',"
            + " now(), now())",
        libraryId,
        DEFAULT_ORGANIZATION_ID,
        userId);
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type, subject_user_id,"
            + " role, created_at, updated_at)"
            + " VALUES (?, ?, ?, 'USER', ?, 'OWNER', now(), now())",
        UUID.randomUUID(),
        libraryId,
        DEFAULT_ORGANIZATION_ID,
        userId);
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM asset_grants WHERE library_id = ?", libraryId);
    jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE id = ?", libraryId);
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
  }

  @Test
  void endToEndQueryReturnsAnswerWithSources() {
    // Index some test documents into the vector store, attributed to the granted library
    var doc1 =
        new Document(
            "OPAA is an AI-powered project assistant built with Spring Boot.",
            Map.of(
                "file_name",
                "readme.md",
                "document_id",
                "doc-1",
                "chunk_index",
                0,
                "library_id",
                libraryId.toString()));
    var doc2 =
        new Document(
            "The deployment uses Docker Compose with PostgreSQL and pgvector.",
            Map.of(
                "file_name",
                "deployment.md",
                "document_id",
                "doc-2",
                "chunk_index",
                0,
                "library_id",
                libraryId.toString()));
    vectorStore.add(List.of(doc1, doc2));

    // Mock the ChatModel response
    var usage =
        new Usage() {
          @Override
          public Integer getPromptTokens() {
            return 150;
          }

          @Override
          public Integer getCompletionTokens() {
            return 100;
          }

          @Override
          public Object getNativeUsage() {
            return null;
          }
        };
    var metadata = ChatResponseMetadata.builder().model("gpt-4o").usage(usage).build();
    var assistantMessage = new AssistantMessage("OPAA is an AI project assistant (readme.md).");
    var chatResponse = new ChatResponse(List.of(new Generation(assistantMessage)), metadata);
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    // Execute the query
    QueryResponse response =
        queryService.query("What is OPAA?", null, userId, true, java.util.List.of());

    // Verify the response
    assertThat(response.getAnswer()).isEqualTo("OPAA is an AI project assistant (readme.md).");
    assertThat(response.getSources()).isNotEmpty();
    assertThat(response.getSources()).allMatch(s -> s.getFileName() != null);
    assertThat(response.getMetadata().getModel()).isEqualTo("gpt-4o");
    assertThat(response.getMetadata().getTokenCount()).isEqualTo(250);
    assertThat(response.getMetadata().getDurationMs()).isGreaterThan(0);
  }

  @Test
  void queryWithNoMatchingDocumentsReturnsEmptySources() {
    // No documents in vector store — similarity search returns empty
    var assistantMessage =
        new AssistantMessage("I don't have enough context to answer that question.");
    var chatResponse = new ChatResponse(List.of(new Generation(assistantMessage)));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    QueryResponse response =
        queryService.query(
            "Something completely unrelated", null, userId, true, java.util.List.of());

    assertThat(response.getAnswer()).contains("don't have enough context");
    assertThat(response.getSources()).isEmpty();
  }

  @Test
  void userWithoutAnyGrantSeesNothing() {
    // A chunk exists in a library the querying user has no grant on - verifies #202's central
    // acceptance criterion end to end: an unauthorized chunk is never loaded, let alone returned,
    // and the answer path is indistinguishable from "nothing matched" (same mocked assistant
    // reply as queryWithNoMatchingDocumentsReturnsEmptySources).
    var doc =
        new Document(
            "Confidential content only the owner may see.",
            Map.of(
                "file_name",
                "secret.md",
                "document_id",
                "doc-secret",
                "chunk_index",
                0,
                "library_id",
                libraryId.toString()));
    vectorStore.add(List.of(doc));

    var assistantMessage =
        new AssistantMessage("I don't have enough context to answer that question.");
    var chatResponse = new ChatResponse(List.of(new Generation(assistantMessage)));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    UUID strangerId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, ?, ?, ?, now(), 'USER', ?)",
        strangerId,
        "query-it-stranger-" + strangerId,
        "test-issuer",
        "stranger@example.com",
        "Stranger",
        DEFAULT_ORGANIZATION_ID);
    try {
      QueryResponse response =
          queryService.query("What is the secret?", null, strangerId, true, java.util.List.of());

      assertThat(response.getAnswer()).contains("don't have enough context");
      assertThat(response.getSources()).isEmpty();
    } finally {
      jdbcTemplate.update("DELETE FROM users WHERE id = ?", strangerId);
    }
  }

  @Test
  void indexedContentBecomesReachableOnceItsLibraryIsOpenedToTheOrganization() {
    // #406: widening a library's visibility must actually widen the search, not just the library
    // API - the case that made a fully indexed corpus useless on the test instance before #406,
    // then demonstrated on the well-known system library that existed until #521 (see that issue).
    // This test walks the whole query chain for an ordinary library the querying user has no grant
    // on - closed first, then opened - because neither half alone would have caught the original
    // defect: the closed state was correct and stayed correct, and no test ever asked what happens
    // after.
    UUID otherOwnerId = UUID.randomUUID();
    UUID closedLibraryId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, ?, ?, ?, now(), 'USER', ?)",
        otherOwnerId,
        "query-it-other-" + otherOwnerId,
        "test-issuer",
        "other@example.com",
        "Other Owner",
        DEFAULT_ORGANIZATION_ID);
    jdbcTemplate.update(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, listed, personal, source_type, created_at, updated_at)"
            + " VALUES (?, ?, 'Verschlossene Bibliothek', 'USER', ?, 'PRIVATE', false, false,"
            + " 'UPLOAD', now(), now())",
        closedLibraryId,
        DEFAULT_ORGANIZATION_ID,
        otherOwnerId);

    var doc =
        new Document(
            "Batman ist 188 cm gross.",
            Map.of(
                "file_name",
                "batman.md",
                "document_id",
                "doc-batman",
                "chunk_index",
                0,
                "library_id",
                closedLibraryId.toString()));
    vectorStore.add(List.of(doc));

    var assistantMessage =
        new AssistantMessage("I don't have enough context to answer that question.");
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(assistantMessage))));

    try {
      QueryResponse closed =
          queryService.query("Wie gross ist Batman?", null, userId, true, java.util.List.of());
      assertThat(closed.getSources()).isEmpty();

      jdbcTemplate.update(
          "UPDATE knowledge_libraries SET visibility = 'ORGANIZATION' WHERE id = ?",
          closedLibraryId);

      var answer =
          new AssistantMessage("Batman ist 188 cm gross. 【source: doc-batman#0 | batman.md】");
      when(chatModel.call(any(Prompt.class)))
          .thenReturn(new ChatResponse(List.of(new Generation(answer))));

      QueryResponse opened =
          queryService.query("Wie gross ist Batman?", null, userId, true, java.util.List.of());

      assertThat(opened.getSources()).hasSize(1);
      assertThat(opened.getSources().getFirst().getFileName()).isEqualTo("batman.md");
    } finally {
      jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE id = ?", closedLibraryId);
      jdbcTemplate.update("DELETE FROM users WHERE id = ?", otherOwnerId);
    }
  }

  @Test
  void queryOnlyReturnsChunksFromTheGrantedLibraryEvenWhenUnauthorizedChunksWouldOutscoreThem() {
    // #202 code review (blocker 1): userWithoutAnyGrantSeesNothing alone does not prove the filter
    // is part of the vector search rather than a post-filter, because an empty readable set short
    // -circuits before the vector store is ever called (see QueryService#query). This test instead
    // gives the user a real, non-empty readable set with a second, ungranted library present in
    // the same store, and asserts on the *count* of results, not just their content: with 6 chunks
    // in the granted library A and 6 in the ungranted library B, FakeEmbeddingModel returns an
    // identical embedding for every text (see its Javadoc), so every one of the 12 chunks scores
    // equally on similarity - a post-filter applied after retrieving topK=5 candidates would
    // return however many of those 5 happened to come from A (typically 2-3 given 6-vs-6 odds,
    // never reliably 5), while a filter that is genuinely part of the ANN search - the only way to
    // guarantee 5 results out of 5 candidates that are all from a library with only 6 members
    // total - always returns exactly topK results, all from A. See the PR description for the
    // reproduction: reverting QueryService's filterExpression(...) call turns this test red while
    // every other test in this class, QueryControllerTest and io.opaa.library.* stay green.
    UUID ungrantedLibraryId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, listed, personal, source_type, created_at, updated_at)"
            + " VALUES (?, ?, 'Fremde Bibliothek', 'USER', ?, 'PRIVATE', false, false, 'UPLOAD',"
            + " now(), now())",
        ungrantedLibraryId,
        DEFAULT_ORGANIZATION_ID,
        userId);

    List<Document> chunks = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      chunks.add(
          new Document(
              "Granted content " + i,
              Map.of(
                  "file_name",
                  "a" + i + ".md",
                  "document_id",
                  "doc-a-" + i,
                  "chunk_index",
                  0,
                  "library_id",
                  libraryId.toString())));
    }
    for (int i = 0; i < 6; i++) {
      chunks.add(
          new Document(
              "Unauthorized content " + i,
              Map.of(
                  "file_name",
                  "b" + i + ".md",
                  "document_id",
                  "doc-b-" + i,
                  "chunk_index",
                  0,
                  "library_id",
                  ungrantedLibraryId.toString())));
    }
    vectorStore.add(chunks);

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort"))));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    try {
      QueryResponse response =
          queryService.query("Beliebige Frage", null, userId, true, java.util.List.of());

      // Exactly topK (5, application.yml default) results, every one of them from the granted
      // library - the count itself is the assertion that matters (see the comment above).
      assertThat(response.getSources()).hasSize(5);
      assertThat(response.getSources())
          .allSatisfy(source -> assertThat(source.getFileName()).startsWith("a"));
    } finally {
      jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE id = ?", ungrantedLibraryId);
    }
  }
}
