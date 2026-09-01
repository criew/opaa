package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.chat.ChatSource;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.test.OpaaIndexingIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
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
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * Exercises the permission-aware vector search (#202) end to end against a real Postgres schema:
 * the test user must actually hold an {@code AssetGrant} on the library a chunk's {@code
 * library_id} metadata points at, or {@link QueryService#query} never even calls {@link
 * VectorStore#similaritySearch} for it - see {@link #userWithoutAnyGrantSeesNothing}.
 */
// Own @TestBean chatTitleTaskExecutor override below means Spring's context cache still keys this
// to its own context regardless of the shared @OpaaIndexingIntegrationTest base - documented
// exception per AGENTS.md.
@OpaaIndexingIntegrationTest
class QueryIntegrationTest {

  private static final UUID DEFAULT_ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired private ChatModel chatModel;

  // #758: AnswerGenerationService/ChatTitleGenerationService now resolve their ChatClient via
  // ActiveChatModelResolver on every call instead of holding one built once at startup - stubbed
  // once in setUp() below to always hand back a ChatClient wrapping the class-wide chatModel mock
  // above, so every existing when(chatModel...) stub in this class keeps working unchanged.
  @Autowired private ActiveChatModelResolver activeChatModelResolver;

  /**
   * #616: replaces {@code ChatConfiguration#chatTitleTaskExecutor} with a same-name, fully
   * synchronous executor for this test class only - the real one runs #557's chat-title LLM call on
   * a separate thread, racing this class's {@code when(chatModel...)} re-stubbing (see the {@code
   * promptCaptor} usages below) against that async call landing on the very same, shared {@code
   * chatModel} mock (from {@link io.opaa.test.OpaaIndexingMockConfiguration}) it stubs. Mockito's
   * stubbing API is not thread-safe against a concurrent invocation of the mock being stubbed,
   * which is exactly what corrupted CI runs with {@code MockitoException at
   * QueryIntegrationTest.java:562} (#616) - a still-in-flight title job from an earlier {@code
   * queryService.query(...)} call (in this test or, since {@code chatModel} is reused across every
   * test method in this class's shared Spring context, an earlier test) invoking the mock exactly
   * while a later {@code when(...)} call was mid-setup. {@link SyncTaskExecutor} runs the title job
   * on the calling thread instead, so by the time {@code queryService.query(...)} returns, the
   * title generation call has already completed (or failed) - never racing anything that runs after
   * it.
   *
   * <p>{@code @TestBean(enforceOverride = true)}, not a same-name {@code @Bean} in a
   * {@code @TestConfiguration}: a plain {@code @Bean} with a name that no longer matches - after,
   * say, a rename of {@code ChatConfiguration#chatTitleTaskExecutor} - would silently become an
   * *additional* bean instead of replacing anything, and the flake this class exists to prevent
   * would come back without a single test here failing loudly to say why. {@code enforceOverride =
   * true} instead makes context startup itself fail if no bean named {@code chatTitleTaskExecutor}
   * exists to replace. Not a mocked {@code TaskExecutor} (the way {@code chatModel} above is a
   * mock): a mock would never actually run the submitted title-generation task at all, which would
   * hide the very call this class stubs {@code chatModel} for instead of making it deterministic.
   */
  @TestBean(name = "chatTitleTaskExecutor", enforceOverride = true)
  private TaskExecutor chatTitleTaskExecutor;

  private static TaskExecutor chatTitleTaskExecutor() {
    return new SyncTaskExecutor();
  }

  @Autowired private VectorStore vectorStore;
  @Autowired private QueryService queryService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ChatMemory chatMemory;
  @Autowired private ChunkEmbeddingLookup chunkEmbeddingLookup;

  private UUID userId;
  private UUID libraryId;

  /** Every user this class creates lives in {@link #DEFAULT_ORGANIZATION_ID}. */
  private static CurrentUser asCaller(UUID userId) {
    return CurrentUser.of(userId, DEFAULT_ORGANIZATION_ID, SystemRole.USER, null);
  }

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store");
    // Spring AI 2.0 merges ChatModel.getOptions() into every request; a bare mock returns null
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    when(activeChatModelResolver.resolveChatClient())
        .thenReturn(ChatClient.builder(chatModel).build());

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
            + " visibility, listed, source_type, created_at, updated_at)"
            + " VALUES (?, ?, 'IT-Bibliothek', 'USER', ?, 'PRIVATE', false, 'UPLOAD',"
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
    QueryResult response =
        queryService.query("What is OPAA?", null, asCaller(userId), true, java.util.List.of());

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

    QueryResult response =
        queryService.query(
            "Something completely unrelated", null, asCaller(userId), true, java.util.List.of());

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
      QueryResult response =
          queryService.query(
              "What is the secret?", null, asCaller(strangerId), true, java.util.List.of());

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
            + " visibility, listed, source_type, created_at, updated_at)"
            + " VALUES (?, ?, 'Verschlossene Bibliothek', 'USER', ?, 'PRIVATE', false,"
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
      QueryResult closed =
          queryService.query(
              "Wie gross ist Batman?", null, asCaller(userId), true, java.util.List.of());
      assertThat(closed.getSources()).isEmpty();

      jdbcTemplate.update(
          "UPDATE knowledge_libraries SET visibility = 'ORGANIZATION' WHERE id = ?",
          closedLibraryId);

      var answer =
          new AssistantMessage("Batman ist 188 cm gross. 【source: doc-batman#0 | batman.md】");
      when(chatModel.call(any(Prompt.class)))
          .thenReturn(new ChatResponse(List.of(new Generation(answer))));

      QueryResult opened =
          queryService.query(
              "Wie gross ist Batman?", null, asCaller(userId), true, java.util.List.of());

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
    // the same store, and asserts on the *count* of results, not just their content.
    //
    // The granted library A (10 chunks) and ungranted library B (250 chunks, inserted first) are
    // deliberately lopsided so a broken, post-hoc filter is distinguishable from the correct,
    // search-time filter even at fetchK=25 candidates: FakeEmbeddingModel gives every text an
    // identical embedding (see its Javadoc), so all 260 chunks tie on similarity, and a tied ANN
    // scan returns ties in something close to insertion order. A correct, search-time filter only
    // ever sees A's 10 members and returns all of them as candidates - MmrSelector then narrows
    // those 10 down to topK (8), all "a"-prefixed. A post-filter instead requests the unfiltered
    // top-25 of 260 tied candidates first: with B outnumbering A 25:1 and ordered first, that
    // top-25
    // is overwhelmingly (typically entirely) B, leaving far fewer than 8 - usually zero -
    // authorized
    // candidates once filtered afterward. See the PR description for the reproduction: reverting
    // QueryService's filterExpression(...) call turns this test red while every other test in this
    // class, QueryControllerTest and io.opaa.library.* stay green.
    //
    // #932 review: the granted set below includes one multi-chunk document (see
    // #grantedChunksWithOneMultiChunkDocument's Javadoc for the exact shape, placement, and why
    // its assertions tolerate DocumentCompletion's tier 1 and tier 2 alike), so DocumentCompletion
    // actually runs on this permission-filtered candidate pool rather than every granted document
    // holding exactly one chunk (the pre-#932 shape, which DocumentCompletion is a no-op for).
    UUID ungrantedLibraryId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, listed, source_type, created_at, updated_at)"
            + " VALUES (?, ?, 'Fremde Bibliothek', 'USER', ?, 'PRIVATE', false, 'UPLOAD',"
            + " now(), now())",
        ungrantedLibraryId,
        DEFAULT_ORGANIZATION_ID,
        userId);

    List<Document> chunks = new ArrayList<>();
    for (int i = 0; i < 250; i++) {
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
    chunks.addAll(grantedChunksWithOneMultiChunkDocument());
    vectorStore.add(chunks);

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort"))));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    try {
      QueryResult response =
          queryService.query("Beliebige Frage", null, asCaller(userId), true, java.util.List.of());

      // Exactly topK (8, application.yml default) retrieved chunks, every one of them from the
      // granted library - the count itself is the assertion that matters (see the comment above).
      // Summed matchCount, not response.getSources().size(): with #grantedChunksWithOneMulti
      // ChunkDocument's tied candidates, how many of doc-a-multi's chunks the ANN tie order keeps
      // is unspecified, so the distinct-source count is not (see that method's Javadoc).
      assertThat(response.getSources())
          .allSatisfy(source -> assertThat(source.getFileName()).startsWith("a"));
      assertThat(response.getSources().stream().mapToInt(ChatSource::getMatchCount).sum())
          .isEqualTo(8);
    } finally {
      jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE id = ?", ungrantedLibraryId);
    }
  }

  /**
   * The granted pool {@link
   * #queryOnlyReturnsChunksFromTheGrantedLibraryEvenWhenUnauthorizedChunksWouldOutscoreThem} and
   * {@link #queryFiltersEveryDecomposedSubQuerysSimilaritySearchByTheSameGrantedLibrary} share: ten
   * single-chunk documents ({@code doc-a-0}..{@code doc-a-9}) plus {@code doc-a-multi}'s three
   * chunks - 13 granted chunks over 11 distinct documents - so {@code DocumentCompletion} (#932)
   * actually runs its tier-1 eviction (a document already holding two chunks) against this
   * permission-filtered pool, not just single-chunk documents that only tier 2 can touch.
   *
   * <p>All 13 tie under {@code FakeEmbeddingModel} (see the first caller's comment), so a plain
   * top-k-by-tied-score selection's exact choice of 8 - and in particular how many of {@code
   * doc-a-multi}'s three chunks land in that top 8 - is <b>not</b> pinned by this method's chunk
   * placement; that ANN tie order is not something a test may assume. Both callers therefore assert
   * on the retrieved <em>chunk</em> count (always exactly {@code topK}, summed via {@code
   * ChatSource#getMatchCount()}) rather than the distinct <em>source</em> count, which would vary
   * with how many of {@code doc-a-multi}'s chunks happen to win the tie in a given run.
   *
   * <p>Both assertions also tolerate {@code DocumentCompletion} (#932 scope v2) firing either tier:
   * tier 1 only ever swaps chunks within this same pool, and tier 2 may additionally drop a granted
   * document out of the selection entirely - but neither ever grows the selection past {@code topK}
   * or admits a chunk from outside this permission-filtered pool, so the summed match count stays
   * exactly {@code topK} and every source stays "a"-prefixed regardless of which tier, if any,
   * actually fires for a given run's tie-broken selection.
   */
  private List<Document> grantedChunksWithOneMultiChunkDocument() {
    List<Document> chunks = new ArrayList<>();
    for (int chunkIndex = 0; chunkIndex < 3; chunkIndex++) {
      chunks.add(multiChunkDocumentChunk(chunkIndex));
    }
    for (int i = 0; i < 10; i++) {
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
    return chunks;
  }

  private Document multiChunkDocumentChunk(int chunkIndex) {
    return new Document(
        "Granted content a-multi chunk " + chunkIndex,
        Map.of(
            "file_name",
            "a-multi.md",
            "document_id",
            "doc-a-multi",
            "chunk_index",
            chunkIndex,
            "library_id",
            libraryId.toString()));
  }

  /**
   * #923's ADR-0008 §5 guard for the multi-sub-query path: the same 250-unauthorized-vs-10
   * authorized setup as {@link
   * #queryOnlyReturnsChunksFromTheGrantedLibraryEvenWhenUnauthorizedChunksWouldOutscoreThem}, but
   * with query decomposition forced to two sub-queries (a two-line decomposition response) so every
   * one of {@code VectorSearchStage}'s per-sub-query {@code similaritySearch}
   * calls - not only the first - is exercised end to end. A hypothetical implementation that
   * dropped the permission filter on any sub-query but the first would leak {@code b*.md} sources
   * into the fused result; this test would then fail on the {@code allSatisfy} assertion below.
   */
  @Test
  void queryFiltersEveryDecomposedSubQuerysSimilaritySearchByTheSameGrantedLibrary() {
    UUID ungrantedLibraryId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, listed, source_type, created_at, updated_at)"
            + " VALUES (?, ?, 'Fremde Bibliothek', 'USER', ?, 'PRIVATE', false, 'UPLOAD',"
            + " now(), now())",
        ungrantedLibraryId,
        DEFAULT_ORGANIZATION_ID,
        userId);

    List<Document> chunks = new ArrayList<>();
    for (int i = 0; i < 250; i++) {
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
    chunks.addAll(grantedChunksWithOneMultiChunkDocument());
    vectorStore.add(chunks);

    // First call is the decomposition step - two lines force the multi-sub-query path. Second call
    // answers the question.
    var decompositionResponse =
        new ChatResponse(
            List.of(new Generation(new AssistantMessage("Erste Teilfrage\nZweite Teilfrage"))));
    var answerResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort"))));
    when(chatModel.call(any(Prompt.class))).thenReturn(decompositionResponse, answerResponse);

    try {
      QueryResult response =
          queryService.query(
              "Beliebige Mehrthemenfrage", null, asCaller(userId), true, java.util.List.of());

      // Exactly topK (8) retrieved chunks: each sub-query is independently MMR-narrowed to the
      // full topK before fusion (#923 review) - with both sub-queries returning the identical,
      // fully-overlapping authorized candidate set (FakeEmbeddingModel ties every embedding), the
      // fused result is exactly that same set of 8, not fewer. Summed matchCount, not
      // response.getSources().size() - see #grantedChunksWithOneMultiChunkDocument's Javadoc for
      // why the distinct-source count is not pinned by this fixture.
      assertThat(response.getSources())
          .allSatisfy(source -> assertThat(source.getFileName()).startsWith("a"));
      assertThat(response.getSources().stream().mapToInt(ChatSource::getMatchCount).sum())
          .isEqualTo(8);
    } finally {
      jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE id = ?", ungrantedLibraryId);
    }
  }

  /**
   * {@link ChunkEmbeddingLookup} reads a chunk's embedding straight back out of the pgvector table
   * it was written to - a real vector of the configured dimension for a known id, and simply no
   * entry for an id that was never stored, never an exception.
   */
  @Test
  void chunkEmbeddingLookupReturnsTheStoredVectorByIdAndOmitsAnUnknownId() {
    var chunk =
        new Document(
            "Content to embed",
            Map.of(
                "file_name",
                "embedded.md",
                "document_id",
                "doc-embedded",
                "chunk_index",
                0,
                "library_id",
                libraryId.toString()));
    vectorStore.add(List.of(chunk));

    Map<String, float[]> embeddings =
        chunkEmbeddingLookup.findByIds(List.of(chunk.getId(), UUID.randomUUID().toString()));

    assertThat(embeddings).containsOnlyKeys(chunk.getId());
    assertThat(embeddings.get(chunk.getId())).hasSize(1536);
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

    queryService.query("Erste Frage", chatId, asCaller(userId), true, List.of());

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
    queryService.query(
        "Was sind meine Ausgaben bei Apple?", chatId, asCaller(userId), true, List.of());

    // Simulate a cold cache (restart/eviction) - see QueryService#query's Javadoc for the exact
    // conversation-cache key format this reconstructs.
    chatMemory.clear(userId + ":" + chatId);

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    var secondResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage("Zweite Antwort"))));
    when(chatModel.call(promptCaptor.capture())).thenReturn(secondResponse);

    queryService.query("Mach daraus eine Tabelle", chatId, asCaller(userId), true, List.of());

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
      QueryResult response =
          queryService.query("Fremde Frage", chatId, asCaller(strangerId), true, List.of());

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
        "Geheime Eigentümerfrage über Gehaltsdaten", chatId, asCaller(userId), true, List.of());

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
    // #616 positive proof: recording the invoking thread's name here, rather than a plain
    // thenReturn, directly demonstrates the #616 fix rather than merely relying on it. This is
    // the exact when(chatModel.call(promptCaptor.capture())) whose setup a still-in-flight #557
    // title job from the owner's query above raced against, corrupting CI with a
    // MockitoException at this line before the fix. If #557's title job (or this stranger
    // query's own answer call) ever reaches this stub from any thread but this JUnit test
    // thread - i.e. the real, ChatConfiguration-backed chatTitleTaskExecutor pool, not the
    // synchronous TestBean override at the top of this class - callThreadNames below fails.
    List<String> callThreadNames = new ArrayList<>();
    when(chatModel.call(promptCaptor.capture()))
        .thenAnswer(
            invocation -> {
              callThreadNames.add(Thread.currentThread().getName());
              return strangerAnswer;
            });

    try {
      // Stranger queries with the owner's real chatId - not an unresolvable random one.
      queryService.query("Fremde Frage", chatId, asCaller(strangerId), true, List.of());

      assertThat(callThreadNames)
          .as(
              "every chatModel.call() reaching this stub - including #557's async title job, if"
                  + " still in flight - must run on this test's own thread (#616)")
          .containsOnly(Thread.currentThread().getName());

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
            () ->
                queryService.query(
                    "Frage nach Austritt", chatId, asCaller(userId), true, List.of()))
        .isInstanceOf(io.opaa.common.AccessDeniedException.class);
  }

  /**
   * #557 acceptance criterion 3, end to end: a chat's very first turn triggers a second LLM call
   * (title generation) after the answer is already built - mocked here to fail, proving the answer
   * {@code query()} returns is entirely unaffected and the chat keeps its synchronous
   * prefix-derived fallback title rather than surfacing the failure. In production this second call
   * genuinely runs off the request thread (#557); in this test class it runs synchronously instead
   * (see {@link #chatTitleTaskExecutor}'s Javadoc, #616), so no {@code await()} is needed below -
   * by the time {@code queryService.query(...)} returns, the failing title generation call has
   * already happened.
   */
  @Test
  void queryAnswerSucceedsEvenWhenTitleGenerationFailsAfterwards() {
    UUID spaceId = insertSpaceWithMembership(userId);
    UUID chatId = insertChat(spaceId, userId);

    var answerResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort trotz Fehler"))));
    // First call is #923's query-decomposition step (its single-line response content is
    // irrelevant here - it parses to one sub-query, taking the pre-#923 single-search path); second
    // call answers the question; every call after that (title generation) fails.
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(answerResponse)
        .thenReturn(answerResponse)
        .thenThrow(new RuntimeException("Titelmodell nicht erreichbar"));

    QueryResult response =
        queryService.query("Erste Frage", chatId, asCaller(userId), true, java.util.List.of());

    assertThat(response.getAnswer()).isEqualTo("Antwort trotz Fehler");
    assertThat(response.getChatTitle()).isEqualTo("Erste Frage");

    // The (synchronous, #616) title generation call above already failed and left the fallback
    // title untouched instead of throwing it away or leaving the chat without any title at all.
    String title =
        jdbcTemplate.queryForObject("SELECT title FROM chats WHERE id = ?", String.class, chatId);
    assertThat(title).isEqualTo("Erste Frage");
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
