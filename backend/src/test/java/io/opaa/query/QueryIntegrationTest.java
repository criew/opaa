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
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
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
  @Autowired private ChatMemory chatMemory;

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
    // #525: chats/spaces the persisted-chat tests below create for userId - fk_chats_author and
    // fk_spaces_owner are ON DELETE RESTRICT, so these must go before the user itself.
    jdbcTemplate.update("DELETE FROM chats WHERE author_id = ?", userId);
    jdbcTemplate.update("DELETE FROM space_memberships WHERE user_id = ?", userId);
    jdbcTemplate.update("DELETE FROM spaces WHERE owner_id = ?", userId);
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

  /**
   * #525 review, finding 1 (critical) and finding 2: {@code QueryService#query} runs inside a
   * class-level {@code @Transactional(readOnly = true)} transaction; {@code ChatService#appendTurn}
   * must open its own writable transaction ({@code REQUIRES_NEW}) or Hibernate silently drops the
   * persisted turn - a chat-mocking unit test can never catch this, only a real transaction against
   * a real database can. Reproduction (fix temporarily reverted to plain {@code @Transactional}):
   * this test failed with {@code expected: 2 but was: 0} - zero rows in {@code chat_messages} after
   * a successful {@code queryService.query(...)} call, exactly the silent data loss the finding
   * describes. With the {@code REQUIRES_NEW} fix in place, it passes.
   */
  @Test
  void queryPersistsTheTurnForARealChat() {
    UUID spaceId = insertSpaceWithMembership(userId);
    UUID chatId = insertChat(spaceId, userId);

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort"))));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    queryService.query("Erste Frage", chatId, userId, true, List.of());

    Integer messageCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chat_messages WHERE chat_id = ?", Integer.class, chatId);
    assertThat(messageCount).isEqualTo(2);
  }

  /**
   * #525 review, finding 2 (rehydration): with the in-memory conversation cache cold - simulating a
   * restart or eviction - a follow-up question in the same persisted chat must still be enriched
   * from the first turn, because {@code QueryService} reloads it from {@code chat_messages}, not
   * from the (now empty) cache.
   */
  @Test
  void queryRehydratesConversationHistoryFromPersistedMessagesOnAColdCache() {
    UUID spaceId = insertSpaceWithMembership(userId);
    UUID chatId = insertChat(spaceId, userId);

    var firstResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage("Erste Antwort"))));
    when(chatModel.call(any(Prompt.class))).thenReturn(firstResponse);
    queryService.query("Was sind meine Ausgaben bei Apple?", chatId, userId, true, List.of());

    // Simulate a cold cache (restart/eviction) - see QueryService#query's Javadoc for the exact
    // conversation-cache key format this reconstructs.
    chatMemory.clear(userId + ":" + chatId);

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    var secondResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage("Zweite Antwort"))));
    when(chatModel.call(promptCaptor.capture())).thenReturn(secondResponse);

    queryService.query("Mach daraus eine Tabelle", chatId, userId, true, List.of());

    boolean firstQuestionInPrompt =
        promptCaptor.getValue().getInstructions().stream()
            .anyMatch(m -> m.getText() != null && m.getText().contains("Ausgaben bei Apple"));
    assertThat(firstQuestionInPrompt)
        .as("the second prompt must include the first question, rehydrated from chat_messages")
        .isTrue();
  }

  /**
   * #525 review, finding 3 (critical - conversation-history leak) and finding 4 (membership): a
   * chatId belonging to another user must neither read that user's history nor write anything into
   * their chat - it is treated as an ephemeral, unpersisted conversation instead (see
   * QueryService#query's Javadoc).
   */
  @Test
  void queryWithAForeignChatIdWritesNothingAndReadsNothing() {
    UUID spaceId = insertSpaceWithMembership(userId);
    UUID chatId = insertChat(spaceId, userId);

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

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort"))));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    try {
      QueryResponse response =
          queryService.query("Fremde Frage", chatId, strangerId, true, List.of());

      // Falls back to an ephemeral conversation, not the owner's chat - the returned id is the
      // supplied chatId (echoed back, exactly as an unresolvable id always is), but nothing was
      // written to it.
      assertThat(response.getChatId()).isEqualTo(chatId);
      Integer messageCount =
          jdbcTemplate.queryForObject(
              "SELECT count(*) FROM chat_messages WHERE chat_id = ?", Integer.class, chatId);
      assertThat(messageCount).isZero();
    } finally {
      jdbcTemplate.update("DELETE FROM users WHERE id = ?", strangerId);
    }
  }

  /**
   * #525 review round 2, finding B: the previous version of this test (see git history) never
   * actually proved the cache-key qualification, because it never populated the owner's cache entry
   * and never inspected what a stranger's prompt actually contained - it would have stayed green
   * even with the bug the userId-qualified cache key (see {@code QueryService#query}'s Javadoc)
   * fixes. This version forces the owner's history into the in-memory cache first, then proves the
   * stranger's prompt does not contain it.
   *
   * <p>Reproduction (fix temporarily reverted to a bare, unqualified {@code chatId.toString()}
   * cache key): {@code ownerQuestionLeakedIntoStrangersPrompt} was {@code true} - the owner's
   * question appeared verbatim in the stranger's {@link Prompt}. With the {@code userId}-qualified
   * key restored, it is {@code false} - see the PR description for the exact captured failure.
   */
  @Test
  void queryWithAForeignChatIdNeverLeaksTheOwnersCachedHistoryIntoTheStrangersPrompt() {
    UUID spaceId = insertSpaceWithMembership(userId);
    UUID chatId = insertChat(spaceId, userId);

    // Owner asks a question first - this both persists it to chat_messages and, critically for
    // this test, warms the in-memory conversation cache under the owner-scoped key.
    var ownerAnswer =
        new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort für den Besitzer"))));
    when(chatModel.call(any(Prompt.class))).thenReturn(ownerAnswer);
    queryService.query(
        "Geheime Eigentümerfrage über Gehaltsdaten", chatId, userId, true, List.of());

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

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    var strangerAnswer =
        new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort für den Fremden"))));
    when(chatModel.call(promptCaptor.capture())).thenReturn(strangerAnswer);

    try {
      // Stranger queries with the owner's real chatId - not an unresolvable random one.
      queryService.query("Fremde Frage", chatId, strangerId, true, List.of());

      boolean ownerQuestionLeakedIntoStrangersPrompt =
          promptCaptor.getValue().getInstructions().stream()
              .anyMatch(
                  m -> m.getText() != null && m.getText().contains("Geheime Eigentümerfrage"));
      assertThat(ownerQuestionLeakedIntoStrangersPrompt)
          .as("the owner's cached question must never appear in a stranger's prompt")
          .isFalse();
    } finally {
      jdbcTemplate.update("DELETE FROM users WHERE id = ?", strangerId);
    }
  }

  /**
   * #525 review, finding 4: an author removed from the chat's space must not be able to keep
   * querying through it, even though the chat itself (and its history) remains theirs to read via
   * GET /api/v1/chats/{chatId} - see ChatService#requireStillSpaceMember's Javadoc.
   */
  @Test
  void queryRejectsAnAuthorNoLongerAMemberOfTheChatsSpace() {
    UUID spaceId = insertSpaceWithMembership(userId);
    UUID chatId = insertChat(spaceId, userId);
    jdbcTemplate.update(
        "DELETE FROM space_memberships WHERE space_id = ? AND user_id = ?", spaceId, userId);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> queryService.query("Frage nach Austritt", chatId, userId, true, List.of()))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(
                        ((org.springframework.web.server.ResponseStatusException) ex)
                            .getStatusCode())
                    .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN));
  }

  private UUID insertSpaceWithMembership(UUID memberId) {
    UUID spaceId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO spaces (id, name, is_default, visibility, owner_id, organization_id,"
            + " created_at, updated_at) VALUES (?, 'Fachbereich', false, 'PRIVATE', ?, ?, now(),"
            + " now())",
        spaceId,
        memberId,
        DEFAULT_ORGANIZATION_ID);
    jdbcTemplate.update(
        "INSERT INTO space_memberships (id, user_id, space_id, role, organization_id, created_at)"
            + " VALUES (?, ?, ?, 'ADMIN', ?, now())",
        UUID.randomUUID(),
        memberId,
        spaceId,
        DEFAULT_ORGANIZATION_ID);
    return spaceId;
  }

  private UUID insertChat(UUID spaceId, UUID authorId) {
    UUID chatId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO chats (id, space_id, author_id, organization_id, use_knowledge, status,"
            + " created_at, updated_at) VALUES (?, ?, ?, ?, true, 'PRIVATE', now(), now())",
        chatId,
        spaceId,
        authorId,
        DEFAULT_ORGANIZATION_ID);
    return chatId;
  }
}
