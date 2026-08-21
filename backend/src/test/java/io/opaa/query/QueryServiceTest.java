package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.dto.QueryResponse;
import io.opaa.api.dto.SourceReference;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.chat.Chat;
import io.opaa.chat.ChatService;
import io.opaa.indexing.DocumentRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.library.PermissionHistoryService;
import io.opaa.observability.QueryMetrics;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

  @Mock private VectorStore vectorStore;
  @Mock private AnswerGenerationService answerGenerationService;
  @Mock private ChatMemory chatMemory;
  @Mock private DocumentRepository documentRepository;
  @Mock private UserRepository userRepository;
  @Mock private LibraryAccessService libraryAccessService;
  @Mock private PermissionHistoryService permissionHistoryService;
  @Mock private ChatService chatService;
  private QueryService queryService;

  private final UUID currentUserId = UUID.randomUUID();
  private final UUID organizationId = UUID.randomUUID();
  private final UUID readableLibraryId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    queryService =
        new QueryService(
            vectorStore,
            answerGenerationService,
            chatMemory,
            new CitationParser(),
            new CitationValidator(),
            documentRepository,
            userRepository,
            libraryAccessService,
            permissionHistoryService,
            chatService,
            new QueryMetrics(new SimpleMeterRegistry()),
            new QueryProperties(5, 0.3));

    User user = new User("subject", "issuer", "user@example.com", "User");
    user.setOrganizationId(organizationId);
    // lenient: not every test in this class exercises the full query() path (e.g. the
    // mergeSourceReferences nested tests call other members directly), so MockitoExtension's
    // strict stubbing would otherwise flag these as unused.
    lenient().when(userRepository.findById(currentUserId)).thenReturn(Optional.of(user));
    lenient()
        .when(libraryAccessService.readableLibraryIds(currentUserId, organizationId))
        .thenReturn(Set.of(readableLibraryId));
    // #238's regression check - matches the applied scope by default so it never flags a
    // mismatch in tests that do not care about it.
    lenient()
        .when(permissionHistoryService.readableLibraryIdsAsOf(eq(currentUserId), any(), any()))
        .thenReturn(Set.of(readableLibraryId));
    // #525 default: no chatId given (or it does not resolve to a chat the caller authored) runs
    // the query ephemerally, exactly as before persisted chats existed.
    lenient().when(chatService.findOwnedChat(any(), any())).thenReturn(Optional.empty());
  }

  /**
   * #639: {@code sourceEntryUrl} is resolved via the same {@code document_id} -> DocumentRepository
   * lookup {@code indexedAt} already uses ({@link QueryService#lookupSourceDocuments}), not carried
   * on the chunk metadata itself.
   */
  @Test
  void queryPopulatesSourceEntryUrlFromDocumentLookup() {
    when(chatMemory.get(any())).thenReturn(List.of());
    UUID documentId = UUID.randomUUID();
    var chunk =
        Document.builder()
            .text("Feed entry content")
            .metadata(Map.of("file_name", "entry.html", "document_id", documentId.toString()))
            .score(0.8)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var indexedDocument = new io.opaa.indexing.Document("entry.html", "/path", "text/html", 100L);
    indexedDocument.setSourceEntryUrl("https://example.com/feed/entry-123");
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(indexedDocument));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null, currentUserId, true, List.of());

    assertThat(response.getSources().getFirst().getSourceEntryUrl())
        .isEqualTo("https://example.com/feed/entry-123");
  }

  /**
   * #639 acceptance criterion: a source referencing any other document (no source entry URL, e.g.
   * not RSS-sourced) still carries {@code sourceEntryUrl: null}.
   */
  @Test
  void queryLeavesSourceEntryUrlNullWhenDocumentHasNone() {
    when(chatMemory.get(any())).thenReturn(List.of());
    UUID documentId = UUID.randomUUID();
    var chunk =
        Document.builder()
            .text("Uploaded content")
            .metadata(Map.of("file_name", "upload.pdf", "document_id", documentId.toString()))
            .score(0.8)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var indexedDocument = new io.opaa.indexing.Document("entry.html", "/path", "text/html", 100L);
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(indexedDocument));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null, currentUserId, true, List.of());

    assertThat(response.getSources().getFirst().getSourceEntryUrl()).isNull();
  }

  /**
   * #666 review: {@code mapSources}/{@code mergeSourceReferences} dedupe by {@code fileName}, not
   * {@code document_id} - two distinct RSS-sourced documents can share a file name while carrying
   * different {@code sourceEntryUrl} values. Asserting either one for the merged citation would be
   * a checkable falsehood about where the other, merged-away chunk came from, so the merged source
   * carries no origin link at all rather than an arbitrarily-picked, possibly wrong one.
   */
  @Test
  void queryDropsSourceEntryUrlWhenTwoDocumentsShareAFileNameWithDifferentUrls() {
    when(chatMemory.get(any())).thenReturn(List.of());
    UUID firstDocumentId = UUID.randomUUID();
    UUID secondDocumentId = UUID.randomUUID();
    var firstChunk =
        Document.builder()
            .text("From the first feed entry")
            .metadata(
                Map.of("file_name", "attachment.pdf", "document_id", firstDocumentId.toString()))
            .score(0.9)
            .build();
    var secondChunk =
        Document.builder()
            .text("From the second feed entry")
            .metadata(
                Map.of("file_name", "attachment.pdf", "document_id", secondDocumentId.toString()))
            .score(0.7)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(firstChunk, secondChunk));

    var firstDocument =
        new io.opaa.indexing.Document("attachment.pdf", "/path1", "application/pdf", 100L);
    firstDocument.setSourceEntryUrl("https://example.com/feed/entry-1");
    var secondDocument =
        new io.opaa.indexing.Document("attachment.pdf", "/path2", "application/pdf", 100L);
    secondDocument.setSourceEntryUrl("https://example.com/feed/entry-2");
    when(documentRepository.findById(firstDocumentId)).thenReturn(Optional.of(firstDocument));
    when(documentRepository.findById(secondDocumentId)).thenReturn(Optional.of(secondDocument));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null, currentUserId, true, List.of());

    assertThat(response.getSources()).hasSize(1);
    assertThat(response.getSources().getFirst().getSourceEntryUrl()).isNull();
  }

  @Test
  void queryMarksCitedSourcesCorrectly() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Relevant content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-123"))
            .score(0.85)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var usage = createUsage(100, 200);
    var metadata = ChatResponseMetadata.builder().model("gpt-4o").usage(usage).build();
    var answer = "The answer is 42 【source: doc-123#0 | readme.md】";
    var chatResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage(answer))), metadata);
    when(answerGenerationService.generateAnswer(eq("What?"), any(), any()))
        .thenReturn(chatResponse);

    QueryResponse response = queryService.query("What?", null, currentUserId, true, List.of());

    assertThat(response.getAnswer()).contains("【source:");
    assertThat(response.getSources()).hasSize(1);
    assertThat(response.getSources().getFirst().getFileName()).isEqualTo("readme.md");
    assertThat(response.getSources().getFirst().getRelevanceScore()).isEqualTo(0.85);
    assertThat(response.getSources().getFirst().getCited()).isTrue();
    assertThat(response.getSources().getFirst().getMatchCount()).isEqualTo(1);
    assertThat(response.getMetadata().getModel()).isEqualTo("gpt-4o");
    assertThat(response.getMetadata().getTokenCount()).isEqualTo(300);
    assertThat(response.getChatId()).isNotNull();
  }

  @Test
  void queryGeneratesChatIdWhenNoneGiven() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null, currentUserId, true, List.of());

    assertThat(response.getChatId()).isNotNull();
  }

  @Test
  void queryScopesAndPersistsWhenChatIdBelongsToTheCaller() {
    Chat chat = new Chat(UUID.randomUUID(), currentUserId, organizationId, null, true, Set.of());
    UUID chatId = chat.getId();
    String conversationKey = currentUserId + ":" + chatId;
    when(chatService.findOwnedChat(chatId, currentUserId)).thenReturn(Optional.of(chat));
    when(chatMemory.get(conversationKey)).thenReturn(List.of());
    when(chatService.historyAsSpringAiMessages(chatId)).thenReturn(List.of());
    when(chatService.effectiveLibraryScope(chat, Set.of(readableLibraryId)))
        .thenReturn(Set.of(readableLibraryId));
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), eq(conversationKey)))
        .thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", chatId, currentUserId, true, List.of());

    assertThat(response.getChatId()).isEqualTo(chatId);
    verify(chatService).appendTurn(eq(chat), eq("Question"), eq("Answer"), any());
  }

  /**
   * #561: {@code ChatService#appendTurn} now returns the chat's title after the turn (rather than
   * QueryService reading it back off the possibly-mutated {@link Chat} instance, which appendTurn
   * no longer mutates - see that method's Javadoc) - QueryService simply forwards whatever the
   * (here fully mocked) {@code chatService} returns.
   */
  @Test
  void queryIncludesTheChatsCurrentTitleInTheResponse() {
    Chat chat = new Chat(UUID.randomUUID(), currentUserId, organizationId, null, true, Set.of());
    UUID chatId = chat.getId();
    String conversationKey = currentUserId + ":" + chatId;
    when(chatService.findOwnedChat(chatId, currentUserId)).thenReturn(Optional.of(chat));
    when(chatMemory.get(conversationKey)).thenReturn(List.of());
    when(chatService.historyAsSpringAiMessages(chatId)).thenReturn(List.of());
    when(chatService.effectiveLibraryScope(chat, Set.of(readableLibraryId)))
        .thenReturn(Set.of(readableLibraryId));
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), eq(conversationKey)))
        .thenReturn(chatResponse);
    when(chatService.appendTurn(eq(chat), any(), any(), any())).thenReturn("Frage zur Frist");

    QueryResponse response =
        queryService.query("Frage zur Frist", chatId, currentUserId, true, List.of());

    assertThat(response.getChatTitle()).isEqualTo("Frage zur Frist");
  }

  @Test
  void queryLeavesChatTitleNullForAnEphemeralQuery() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null, currentUserId, true, List.of());

    assertThat(response.getChatTitle()).isNull();
  }

  @Test
  void queryUsesTheChatsRestrictedScopeWhenUseKnowledgeIsOff() {
    UUID otherReadableLibraryId = UUID.randomUUID();
    lenient()
        .when(libraryAccessService.readableLibraryIds(currentUserId, organizationId))
        .thenReturn(Set.of(readableLibraryId, otherReadableLibraryId));
    Chat chat =
        new Chat(
            UUID.randomUUID(),
            currentUserId,
            organizationId,
            null,
            false,
            Set.of(readableLibraryId));
    UUID chatId = chat.getId();
    when(chatService.findOwnedChat(chatId, currentUserId)).thenReturn(Optional.of(chat));
    when(chatMemory.get(currentUserId + ":" + chatId)).thenReturn(List.of());
    when(chatService.historyAsSpringAiMessages(chatId)).thenReturn(List.of());
    // Only the sticky reference, not the second library the caller can also read - the scope
    // that the chat's own useKnowledge=false restricts to (epic #523), regardless of the
    // request-level useKnowledge/libraryIds passed below.
    when(chatService.effectiveLibraryScope(chat, Set.of(readableLibraryId, otherReadableLibraryId)))
        .thenReturn(Set.of(readableLibraryId));
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query("Question", chatId, currentUserId, true, List.of());

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    String filter = captor.getValue().getFilterExpression().toString();
    assertThat(filter).contains(readableLibraryId.toString());
    assertThat(filter).doesNotContain(otherReadableLibraryId.toString());
  }

  // #706 review, finding 3: the space-curated fail-open case - useKnowledge stays true
  // (@Alles-Wissen), the chat's space has at least one library association, but none of them are
  // readable by this caller, so effectiveLibraryScope legitimately resolves to empty. This must be
  // marked distinctly from answeredWithoutKnowledge (which only ever covers useKnowledge=false).
  @Test
  void queryMarksNoKnowledgeAvailableInSpaceWhenTheSpaceIsCuratedButNothingIsReadable() {
    Chat chat = new Chat(UUID.randomUUID(), currentUserId, organizationId, null, true, Set.of());
    UUID chatId = chat.getId();
    when(chatService.findOwnedChat(chatId, currentUserId)).thenReturn(Optional.of(chat));
    when(chatMemory.get(currentUserId + ":" + chatId)).thenReturn(List.of());
    when(chatService.historyAsSpringAiMessages(chatId)).thenReturn(List.of());
    when(chatService.effectiveLibraryScope(chat, Set.of(readableLibraryId))).thenReturn(Set.of());
    when(chatService.spaceHasLibraryAssociations(chat.getSpaceId())).thenReturn(true);
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", chatId, currentUserId, true, List.of());

    assertThat(response.getMetadata().getNoKnowledgeAvailableInSpace()).isTrue();
    assertThat(response.getMetadata().getAnsweredWithoutKnowledge()).isFalse();
    org.mockito.Mockito.verifyNoInteractions(vectorStore);
  }

  // The unrelated ordinary case must stay unmarked: a space without any association resolving to
  // an empty scope would mean the caller simply has no readable library at all - not curation.
  @Test
  void queryDoesNotMarkNoKnowledgeAvailableInSpaceWhenTheSpaceHasNoAssociations() {
    Chat chat = new Chat(UUID.randomUUID(), currentUserId, organizationId, null, true, Set.of());
    UUID chatId = chat.getId();
    when(chatService.findOwnedChat(chatId, currentUserId)).thenReturn(Optional.of(chat));
    when(chatMemory.get(currentUserId + ":" + chatId)).thenReturn(List.of());
    when(chatService.historyAsSpringAiMessages(chatId)).thenReturn(List.of());
    when(chatService.effectiveLibraryScope(chat, Set.of(readableLibraryId)))
        .thenReturn(Set.of(readableLibraryId));
    lenient().when(chatService.spaceHasLibraryAssociations(chat.getSpaceId())).thenReturn(false);
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", chatId, currentUserId, true, List.of());

    assertThat(response.getMetadata().getNoKnowledgeAvailableInSpace()).isFalse();
  }

  // An ephemeral query (no persisted chat) never marks this flag, regardless of scope - curation
  // only exists at the chat/space level.
  @Test
  void queryNeverMarksNoKnowledgeAvailableInSpaceForAnEphemeralQuery() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null, currentUserId, false, List.of());

    assertThat(response.getMetadata().getNoKnowledgeAvailableInSpace()).isFalse();
  }

  @Test
  void queryRunsEphemerallyWhenChatIdDoesNotBelongToTheCaller() {
    // Pre-#525 behaviour, preserved for callers that have not moved to persisted chats yet (see
    // #527): a chatId that does not resolve to an owned chat is treated as a plain, non-persisted
    // conversation-cache key rather than being rejected - and it is echoed back unchanged, so a
    // client round-tripping it still gets multi-turn continuity without anything being persisted.
    UUID foreignChatId = UUID.randomUUID();
    when(chatService.findOwnedChat(foreignChatId, currentUserId)).thenReturn(Optional.empty());
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response =
        queryService.query("Question", foreignChatId, currentUserId, true, List.of());

    assertThat(response.getChatId()).isEqualTo(foreignChatId);
    verify(chatService, never()).appendTurn(any(), any(), any(), any());
  }

  /**
   * #123 follow-up: explicit two-account test against the real cache stack ({@link
   * MessageWindowChatMemory} over {@link CaffeineChatMemoryRepository}, exactly as {@link
   * QueryConfiguration} wires it) instead of this test class's otherwise-mocked {@code chatMemory}
   * field. Both accounts submit the same {@code chatId}; neither resolves it via {@link
   * ChatService#findOwnedChat} (stubbed empty for every argument in {@link #setUp}), so both run
   * ephemerally - see the class Javadoc on {@link
   * #queryRunsEphemerallyWhenChatIdDoesNotBelongToTheCaller} for that pre-existing behaviour. The
   * persisted-chat-owner-versus-foreign-account combination is not additionally covered here: it
   * exercises the same key formula ({@code currentUserId + ":" + effectiveChatId}, {@link
   * QueryService#query}) on the owner side that {@link
   * #queryScopesAndPersistsWhenChatIdBelongsToTheCaller} already asserts via the mocked {@code
   * chatMemory}, so it would not add coverage of a code path this test does not already reach. The
   * test simulates what {@link AnswerGenerationService} does in production - write two messages per
   * call under the given conversation key - and captures the key actually used per call instead of
   * recomputing the formula, so the assertions stay valid even if the key format changes.
   */
  @Test
  void sameChatIdForTwoDifferentUsersProducesIsolatedConversationHistories() {
    ChatMemoryRepository realRepository = new CaffeineChatMemoryRepository(50, 60);
    ChatMemory realChatMemory =
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(realRepository)
            .maxMessages(20)
            .build();
    QueryService serviceWithRealMemory =
        new QueryService(
            vectorStore,
            answerGenerationService,
            realChatMemory,
            new CitationParser(),
            new CitationValidator(),
            documentRepository,
            userRepository,
            libraryAccessService,
            permissionHistoryService,
            chatService,
            new QueryMetrics(new SimpleMeterRegistry()),
            new QueryProperties(5, 0.3));

    UUID otherUserId = UUID.randomUUID();
    User otherUser = new User("other-subject", "issuer", "other@example.com", "Other User");
    otherUser.setOrganizationId(organizationId);
    when(userRepository.findById(otherUserId)).thenReturn(Optional.of(otherUser));
    // useKnowledge=false with no requested library keeps the search scope empty regardless of
    // what this account may read, so the readable-set stub's content is irrelevant here - the
    // vector store and permission-history check are simply skipped for an empty scope (see
    // QueryService#query and #checkAgainstPermissionHistory).
    when(libraryAccessService.readableLibraryIds(otherUserId, organizationId)).thenReturn(Set.of());

    UUID sharedChatId = UUID.randomUUID();
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    List<String> usedConversationKeys = new ArrayList<>();
    when(answerGenerationService.generateAnswer(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              String conversationKey = invocation.getArgument(2);
              usedConversationKeys.add(conversationKey);
              realChatMemory.add(
                  conversationKey, new UserMessage("question from " + conversationKey));
              realChatMemory.add(
                  conversationKey, new AssistantMessage("answer to " + conversationKey));
              return chatResponse;
            });

    serviceWithRealMemory.query("Question A", sharedChatId, currentUserId, false, List.of());
    serviceWithRealMemory.query("Question B", sharedChatId, otherUserId, false, List.of());

    assertThat(usedConversationKeys).hasSize(2);
    String keyUserA = usedConversationKeys.get(0);
    String keyUserB = usedConversationKeys.get(1);
    assertThat(keyUserA).isNotEqualTo(keyUserB);

    List<Message> historyUserA = realChatMemory.get(keyUserA);
    List<Message> historyUserB = realChatMemory.get(keyUserB);

    assertThat(historyUserA).hasSize(2);
    assertThat(historyUserB).hasSize(2);
    assertThat(historyUserA.stream().map(Message::getText))
        .allMatch(text -> text.contains(keyUserA))
        .noneMatch(text -> text.contains(keyUserB));
    assertThat(historyUserB.stream().map(Message::getText))
        .allMatch(text -> text.contains(keyUserB))
        .noneMatch(text -> text.contains(keyUserA));
  }

  /**
   * #525 acceptance criterion: "Folgefragen im selben Chat nutzen den persistierten Verlauf als
   * Kontext". Simulates a cold in-memory cache (the very first {@code chatMemory.get} call in this
   * test returns empty, as it would after a restart or eviction) with history nonetheless available
   * from the persisted store - and asserts the search query is enriched from that persisted
   * history, not left as the plain question.
   */
  @Test
  void queryEnrichesSearchFromPersistedHistoryOnAColdConversationCache() {
    Chat chat = new Chat(UUID.randomUUID(), currentUserId, organizationId, null, true, Set.of());
    UUID chatId = chat.getId();
    String conversationKey = currentUserId + ":" + chatId;
    when(chatService.findOwnedChat(chatId, currentUserId)).thenReturn(Optional.of(chat));

    List<Message> persistedHistory =
        List.of(
            new UserMessage("Was sind meine Ausgaben bei Apple?"),
            new AssistantMessage("Ihre Apple-Ausgaben betragen 500 EUR."));
    // First call (the seeding check) sees a cold cache; the second call (inside
    // buildSearchQuery) sees it warmed by the seeding this test asserts happened.
    when(chatMemory.get(conversationKey)).thenReturn(List.of(), persistedHistory);
    when(chatService.historyAsSpringAiMessages(chatId)).thenReturn(persistedHistory);
    when(chatService.effectiveLibraryScope(chat, Set.of(readableLibraryId)))
        .thenReturn(Set.of(readableLibraryId));
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Tabelle"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query(
        "Mach daraus eine tabellarische Auflistung", chatId, currentUserId, true, List.of());

    verify(chatMemory).add(conversationKey, persistedHistory);
    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    assertThat(captor.getValue().getQuery())
        .isEqualTo("Was sind meine Ausgaben bei Apple? Mach daraus eine tabellarische Auflistung");
  }

  @Test
  void queryMarksUncitedSourcesCorrectly() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var citedChunk =
        Document.builder()
            .text("Cited content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-1"))
            .score(0.9)
            .build();
    var uncitedChunk =
        Document.builder()
            .text("Uncited content")
            .metadata(Map.of("file_name", "other.pdf", "document_id", "doc-2"))
            .score(0.7)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(citedChunk, uncitedChunk));

    var answer = "Info from readme 【source: doc-1#0 | readme.md】.";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null, currentUserId, true, List.of());

    assertThat(response.getSources()).hasSize(2);
    assertThat(response.getSources().get(0).getCited()).isTrue();
    assertThat(response.getSources().get(1).getCited()).isFalse();
  }

  /**
   * #386 acceptance criterion + reproduction proof: a citation whose document id is not among the
   * chunks retrieved for this answer must be flagged as invalid - never silently dropped, and never
   * allowed to pass through as an ordinary, unflagged citation. Before the fix this fabricated
   * citation produced no source entry at all (it matches no real chunk's document id in {@code
   * mapSources}' original iteration), so nothing in the response indicated anything was wrong with
   * it - this assertion is red on the pre-fix code (no entry exists to carry {@code citationValid:
   * false} at all) and green after. Uses a file name that collides with no retrieved chunk, so the
   * result is unaffected by the collision-folding {@link
   * #queryFoldsACollidingSyntheticEntryIntoTheRealUncitedSourceInsteadOfAddingARow} covers
   * separately.
   */
  @Test
  void queryMarksCitationToAFabricatedDocumentIdAsInvalid() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Relevant content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-123"))
            .score(0.85)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var answer = "The answer is 42 【source: fabricated-id#0 | fabricated-name.pdf】";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("What?", null, currentUserId, true, List.of());

    assertThat(response.getSources())
        .anySatisfy(
            source -> {
              assertThat(source.getFileName()).isEqualTo("fabricated-name.pdf");
              assertThat(source.getCitationValid()).isFalse();
              assertThat(source.getCited()).isTrue();
            });
  }

  /**
   * #386 acceptance criterion: a citation with a valid document id and section but a file name that
   * does not match that document is invalid - it is more misleading than no citation at all.
   */
  @Test
  void queryMarksCitationWithMismatchedFileNameAsInvalid() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Relevant content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-123"))
            .score(0.85)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var answer = "The answer is 42 【source: doc-123#0 | wrong-name.pdf】";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("What?", null, currentUserId, true, List.of());

    assertThat(response.getSources().getFirst().getFileName()).isEqualTo("readme.md");
    assertThat(response.getSources().getFirst().getCitationValid()).isFalse();
  }

  /**
   * #386 acceptance criterion: a section number that belongs to the cited document but was not
   * among the chunks retrieved for this answer is invalid.
   */
  @Test
  void queryMarksCitationWithSectionOutsideRetrievedChunksAsInvalid() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Relevant content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-123"))
            .score(0.85)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var answer = "The answer is 42 【source: doc-123#9 | readme.md】";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("What?", null, currentUserId, true, List.of());

    assertThat(response.getSources().getFirst().getFileName()).isEqualTo("readme.md");
    assertThat(response.getSources().getFirst().getCitationValid()).isFalse();
  }

  /** #386: a citation matching the retrieved chunk exactly stays unflagged. */
  @Test
  void queryLeavesAValidCitationUnflagged() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Relevant content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-123"))
            .score(0.85)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var answer = "The answer is 42 【source: doc-123#0 | readme.md】";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("What?", null, currentUserId, true, List.of());

    assertThat(response.getSources().getFirst().getCitationValid()).isTrue();
  }

  /**
   * #697 review, finding 4 + second round: a fabricated citation's synthetic entry must never merge
   * with a real, retrieved-but-uncited source that happens to share its file name via the normal
   * dedupe-by-filename merge - that would have made the real document appear falsely cited, with
   * its real relevance score and its real "open in document" link, for a citation it was never
   * actually named in. The second review round found that the first fix (never merging the two
   * groups at all) traded that failure for another: two {@code SourceReference} rows sharing one
   * file name, which the frontend's marker-to-source join resolves last-wins - always to the
   * synthetic, zero-relevance row, corrupting even a source that {@code was} genuinely, validly
   * cited (see the next test). The fix folds a colliding synthetic entry into the real one instead
   * of adding a second row: only {@code citationValid} moves to {@code false}, nothing else about
   * the real entry changes.
   */
  @Test
  void queryFoldsACollidingSyntheticEntryIntoTheRealUncitedSourceInsteadOfAddingARow() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Relevant content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-123"))
            .score(0.85)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    // "fabricated-id" is not among the retrieved chunks, but the model still copied the correct,
    // real file name into the fabricated citation - the exact collision finding 4 describes.
    var answer = "The answer is 42 【source: fabricated-id#0 | readme.md】";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("What?", null, currentUserId, true, List.of());

    assertThat(response.getSources()).hasSize(1);
    SourceReference source = response.getSources().getFirst();
    assertThat(source.getFileName()).isEqualTo("readme.md");
    assertThat(source.getRelevanceScore()).isEqualTo(0.85);
    assertThat(source.getMatchCount()).isEqualTo(1);
    assertThat(source.getCited()).isFalse();
    assertThat(source.getCitationValid()).isFalse();
  }

  /**
   * #697 second review round's concrete scenario: a source is both validly cited <em>and</em> named
   * by a colliding fabricated citation. Before this fix, the frontend's file-name join would have
   * resolved to the synthetic, zero-relevance row for this file - the validly cited real source
   * would have displayed with 0% relevance and no document link, even though a genuine citation
   * pointed at it. Folding the collision into the real entry keeps its real {@code cited = true},
   * relevance score and link intact; only {@code citationValid} reflects the separate, invalid
   * citation that also named this file.
   */
  @Test
  void queryPreservesRealMetadataOnAValidlyCitedSourceThatAlsoCollidesWithASyntheticEntry() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Relevant content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-123"))
            .score(0.85)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var answer =
        "The answer is 42 【source: doc-123#0 | readme.md】 【source: fabricated-id#0 | readme.md】";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("What?", null, currentUserId, true, List.of());

    assertThat(response.getSources()).hasSize(1);
    SourceReference source = response.getSources().getFirst();
    assertThat(source.getFileName()).isEqualTo("readme.md");
    assertThat(source.getRelevanceScore()).isEqualTo(0.85);
    assertThat(source.getCited()).isTrue();
    assertThat(source.getCitationValid()).isFalse();
  }

  @Test
  void queryCountsMatchesPerFile() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk1 =
        Document.builder()
            .text("First chunk")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-1"))
            .score(0.9)
            .build();
    var chunk2 =
        Document.builder()
            .text("Second chunk")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-1"))
            .score(0.7)
            .build();
    var chunk3 =
        Document.builder()
            .text("Readme chunk")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-2"))
            .score(0.8)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(chunk1, chunk2, chunk3));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null, currentUserId, true, List.of());

    assertThat(response.getSources()).hasSize(2);
    assertThat(response.getSources().get(0).getFileName()).isEqualTo("report.pdf");
    assertThat(response.getSources().get(0).getMatchCount()).isEqualTo(2);
    assertThat(response.getSources().get(1).getFileName()).isEqualTo("readme.md");
    assertThat(response.getSources().get(1).getMatchCount()).isEqualTo(1);
  }

  @Test
  void queryRetainsCitationMarkersInAnswer() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-1"))
            .score(0.9)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var answer = "The answer 【source: doc-1#0 | readme.md】 is here.";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null, currentUserId, true, List.of());

    assertThat(response.getAnswer()).isEqualTo(answer);
  }

  @Test
  void queryDeduplicatesSourcesByFileName() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk1 =
        Document.builder()
            .text("High relevance chunk")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-1"))
            .score(0.9)
            .build();
    var chunk2 =
        Document.builder()
            .text("Lower relevance chunk")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-1"))
            .score(0.7)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(chunk1, chunk2));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null, currentUserId, true, List.of());

    assertThat(response.getSources()).hasSize(1);
    assertThat(response.getSources().getFirst().getRelevanceScore()).isEqualTo(0.9);
  }

  @Test
  void queryPassesSearchRequestWithCorrectParameters() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage("No results"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query("Test query", null, currentUserId, true, List.of());

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    SearchRequest request = captor.getValue();
    assertThat(request.getQuery()).isEqualTo("Test query");
    assertThat(request.getTopK()).isEqualTo(5);
    assertThat(request.getSimilarityThreshold()).isEqualTo(0.3);
  }

  @Test
  void queryFiltersOnReadableLibraryIds() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query("Test query", null, currentUserId, true, List.of());

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    assertThat(captor.getValue().getFilterExpression()).isNotNull();
    assertThat(captor.getValue().getFilterExpression().toString())
        .contains(readableLibraryId.toString());
  }

  @Test
  void queryWithNoReadableLibrariesSkipsVectorStoreAndReturnsEmptySources() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(libraryAccessService.readableLibraryIds(currentUserId, organizationId))
        .thenReturn(Set.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null, currentUserId, true, List.of());

    assertThat(response.getSources()).isEmpty();
    org.mockito.Mockito.verifyNoInteractions(vectorStore);
  }

  @Test
  void queryWithUseKnowledgeFalseAndOneReferencedLibraryOnlySearchesThatLibrary() {
    UUID otherReadableLibraryId = UUID.randomUUID();
    when(libraryAccessService.readableLibraryIds(currentUserId, organizationId))
        .thenReturn(Set.of(readableLibraryId, otherReadableLibraryId));
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query("Question", null, currentUserId, false, List.of(readableLibraryId));

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    String filterExpression = captor.getValue().getFilterExpression().toString();
    assertThat(filterExpression).contains(readableLibraryId.toString());
    assertThat(filterExpression).doesNotContain(otherReadableLibraryId.toString());
  }

  @Test
  void queryWithUseKnowledgeFalseAndUnreadableLibrarySkipsVectorStoreAndReturnsEmptySources() {
    UUID unreadableLibraryId = UUID.randomUUID();
    when(chatMemory.get(any())).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response =
        queryService.query("Question", null, currentUserId, false, List.of(unreadableLibraryId));

    assertThat(response.getSources()).isEmpty();
    assertThat(response.getMetadata().getAnsweredWithoutKnowledge()).isTrue();
    org.mockito.Mockito.verifyNoInteractions(vectorStore);
  }

  @Test
  void queryWithUseKnowledgeFalseAndNoReferencesSkipsVectorStoreAndMarksAnsweredWithoutKnowledge() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null, currentUserId, false, List.of());

    assertThat(response.getSources()).isEmpty();
    assertThat(response.getMetadata().getAnsweredWithoutKnowledge()).isTrue();
    org.mockito.Mockito.verifyNoInteractions(vectorStore);
  }

  @Test
  void queryWithUseKnowledgeTrueDoesNotMarkAnsweredWithoutKnowledge() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null, currentUserId, true, List.of());

    assertThat(response.getMetadata().getAnsweredWithoutKnowledge()).isFalse();
  }

  @Test
  void queryWithUseKnowledgeTrueIgnoresLibraryIdsAndSearchesAllReadableLibraries() {
    UUID otherReadableLibraryId = UUID.randomUUID();
    when(libraryAccessService.readableLibraryIds(currentUserId, organizationId))
        .thenReturn(Set.of(readableLibraryId, otherReadableLibraryId));
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    // useKnowledge = true with a non-empty libraryIds: the list must be ignored, and the search
    // scope stays every readable library - not just the one referenced here.
    queryService.query("Question", null, currentUserId, true, List.of(readableLibraryId));

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    String filterExpression = captor.getValue().getFilterExpression().toString();
    assertThat(filterExpression).contains(readableLibraryId.toString());
    assertThat(filterExpression).contains(otherReadableLibraryId.toString());
  }

  @Test
  void
      queryWithUseKnowledgeFalseAndNullLibraryIdsSkipsVectorStoreAndMarksAnsweredWithoutKnowledge() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    // null requestedLibraryIds must behave exactly like an empty list, not throw or search
    // everything readable.
    QueryResponse response = queryService.query("Question", null, currentUserId, false, null);

    assertThat(response.getSources()).isEmpty();
    assertThat(response.getMetadata().getAnsweredWithoutKnowledge()).isTrue();
    org.mockito.Mockito.verifyNoInteractions(vectorStore);
  }

  @Test
  void queryPreservesCitedFlagWhenDeduplicatingChunksFromSameFile() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var citedChunk =
        Document.builder()
            .text("Cited chunk")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-1"))
            .score(0.7)
            .build();
    var higherScoreUncitedChunk =
        Document.builder()
            .text("Higher score uncited")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-2"))
            .score(0.95)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(citedChunk, higherScoreUncitedChunk));

    var answer = "Info 【source: doc-1#0 | report.pdf】.";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResponse response = queryService.query("Question", null, currentUserId, true, List.of());

    assertThat(response.getSources()).hasSize(1);
    assertThat(response.getSources().getFirst().getCited()).isTrue();
    assertThat(response.getSources().getFirst().getRelevanceScore()).isEqualTo(0.95);
  }

  @Test
  void queryEnrichesSearchWithConversationHistory() {
    UUID chatId = UUID.randomUUID();
    when(chatMemory.get(currentUserId + ":" + chatId))
        .thenReturn(
            List.of(
                new UserMessage("Was sind meine Ausgaben bei Apple?"),
                new AssistantMessage("Ihre Apple-Ausgaben betragen 500 EUR.")));
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Tabelle"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query(
        "Mach daraus eine tabellarische Auflistung", chatId, currentUserId, true, List.of());

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    assertThat(captor.getValue().getQuery())
        .isEqualTo("Was sind meine Ausgaben bei Apple? Mach daraus eine tabellarische Auflistung");
  }

  @Test
  void queryEnrichesThirdMessageWithFirstUserQuestion() {
    UUID chatId = UUID.randomUUID();
    when(chatMemory.get(currentUserId + ":" + chatId))
        .thenReturn(
            List.of(
                new UserMessage("Was sind meine Ausgaben bei Apple?"),
                new AssistantMessage("Ihre Apple-Ausgaben betragen 500 EUR."),
                new UserMessage("Mach daraus eine Tabelle"),
                new AssistantMessage("Hier ist die Tabelle...")));
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Sortiert"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query("Sortiere nach Datum", chatId, currentUserId, true, List.of());

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    assertThat(captor.getValue().getQuery())
        .isEqualTo("Was sind meine Ausgaben bei Apple? Sortiere nach Datum");
  }

  @Test
  void queryUsesPlainQuestionWhenNoHistory() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query("First question", null, currentUserId, true, List.of());

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    assertThat(captor.getValue().getQuery()).isEqualTo("First question");
  }

  /**
   * #525 review round 2, finding A: {@code query()} deliberately carries no {@code @Transactional}
   * annotation at all - an ambient (even read-only) transaction held for this method's whole
   * duration, including the LLM call, would starve the connection pool once {@code
   * ChatService#appendTurn} needed a second connection to write under load (see {@code
   * QueryService#query}'s Javadoc for the full pool-deadlock reasoning, the same class of bug #299
   * fixed in {@code UserService.findOrCreateUser}). This test pins the absence of the annotation
   * itself, not merely its former {@code readOnly = true} value - the previous version of this test
   * asserted the exact setting finding A required removing.
   */
  @Test
  void queryMethodCarriesNoTransactionalAnnotation() throws NoSuchMethodException {
    Method queryMethod =
        QueryService.class.getMethod(
            "query", String.class, UUID.class, UUID.class, boolean.class, List.class);

    assertThat(queryMethod.getAnnotation(Transactional.class)).isNull();
  }

  @Nested
  class MergeSourceReferences {

    private static final Instant INDEXED_AT = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    void keepsHigherRelevanceScore() {
      var high = sourceReference("file.pdf", 0.9, 1, INDEXED_AT, false);
      var low = sourceReference("file.pdf", 0.5, 1, INDEXED_AT, false);

      var result = QueryService.mergeSourceReferences(high, low);

      assertThat(result.getRelevanceScore()).isEqualTo(0.9);
    }

    @Test
    void keepsHigherScoreRegardlessOfOrder() {
      var low = sourceReference("file.pdf", 0.3, 1, INDEXED_AT, false);
      var high = sourceReference("file.pdf", 0.8, 1, INDEXED_AT, false);

      var result = QueryService.mergeSourceReferences(low, high);

      assertThat(result.getRelevanceScore()).isEqualTo(0.8);
    }

    @Test
    void prefersFirstWhenScoresAreEqual() {
      var first = sourceReference("file.pdf", 0.7, 2, INDEXED_AT, true);
      var second = sourceReference("file.pdf", 0.7, 1, INDEXED_AT, false);

      var result = QueryService.mergeSourceReferences(first, second);

      assertThat(result).isEqualTo(first);
    }

    @Test
    void preservesCitedWhenHigherScoreIsCited() {
      var cited = sourceReference("file.pdf", 0.9, 1, INDEXED_AT, true);
      var uncited = sourceReference("file.pdf", 0.5, 1, INDEXED_AT, false);

      var result = QueryService.mergeSourceReferences(cited, uncited);

      assertThat(result.getCited()).isTrue();
      assertThat(result.getRelevanceScore()).isEqualTo(0.9);
    }

    @Test
    void forcesCitedWhenLowerScoreIsCitedButHigherWins() {
      var citedLow = sourceReference("file.pdf", 0.3, 1, INDEXED_AT, true);
      var uncitedHigh = sourceReference("file.pdf", 0.9, 1, INDEXED_AT, false);

      var result = QueryService.mergeSourceReferences(citedLow, uncitedHigh);

      assertThat(result.getCited()).isTrue();
      assertThat(result.getRelevanceScore()).isEqualTo(0.9);
      assertThat(result.getFileName()).isEqualTo("file.pdf");
    }

    @Test
    void returnsFalseWhenNeitherIsCited() {
      var a = sourceReference("file.pdf", 0.8, 1, INDEXED_AT, false);
      var b = sourceReference("file.pdf", 0.6, 1, INDEXED_AT, false);

      var result = QueryService.mergeSourceReferences(a, b);

      assertThat(result.getCited()).isFalse();
    }

    @Test
    void preservesMetadataFromPreferredSource() {
      var indexedEarly = Instant.parse("2024-01-01T00:00:00Z");
      var indexedLate = Instant.parse("2025-06-01T00:00:00Z");
      var high = sourceReference("report.pdf", 0.95, 3, indexedLate, false);
      var low = sourceReference("report.pdf", 0.4, 1, indexedEarly, true);

      var result = QueryService.mergeSourceReferences(high, low);

      assertThat(result.getMatchCount()).isEqualTo(3);
      assertThat(result.getIndexedAt()).isEqualTo(indexedLate);
      assertThat(result.getCited()).isTrue();
    }

    /**
     * #639: the branch that builds a fresh {@code SourceReference} to force {@code cited = true}
     * (because a lower-scoring duplicate was cited but the higher-scoring one is preferred) carries
     * {@code sourceEntryUrl} over from the preferred source, same as {@code indexedAt}.
     */
    @Test
    void preservesSourceEntryUrlWhenForcingCited() {
      var citedLow =
          sourceReference("report.pdf", 0.3, 1, INDEXED_AT, true, "https://example.com/entry-1");
      var uncitedHigh =
          sourceReference("report.pdf", 0.9, 1, INDEXED_AT, false, "https://example.com/entry-1");

      var result = QueryService.mergeSourceReferences(citedLow, uncitedHigh);

      assertThat(result.getCited()).isTrue();
      assertThat(result.getSourceEntryUrl()).isEqualTo("https://example.com/entry-1");
    }

    /**
     * #666 review: two distinct documents can share a file name, each with its own {@code
     * sourceEntryUrl} - picking either side's URL for the merged citation would be an unverifiable,
     * potentially wrong claim about where the other chunk actually came from. The merge must drop
     * to {@code null} rather than assert one of two disagreeing URLs.
     */
    @Test
    void dropsSourceEntryUrlWhenMergedSourcesDisagree() {
      var a =
          sourceReference("report.pdf", 0.9, 1, INDEXED_AT, false, "https://example.com/entry-1");
      var b =
          sourceReference("report.pdf", 0.5, 1, INDEXED_AT, false, "https://example.com/entry-2");

      var result = QueryService.mergeSourceReferences(a, b);

      assertThat(result.getSourceEntryUrl()).isNull();
    }

    /**
     * #666 review: one side carrying no {@code sourceEntryUrl} at all (not merely a different one)
     * is also a disagreement - a document with a URL and one without do not corroborate each other.
     */
    @Test
    void dropsSourceEntryUrlWhenOnlyOneSourceHasOne() {
      var withUrl =
          sourceReference("report.pdf", 0.9, 1, INDEXED_AT, false, "https://example.com/entry-1");
      var withoutUrl = sourceReference("report.pdf", 0.5, 1, INDEXED_AT, false, null);

      var result = QueryService.mergeSourceReferences(withUrl, withoutUrl);

      assertThat(result.getSourceEntryUrl()).isNull();
    }
  }

  private static SourceReference sourceReference(
      String fileName, double relevanceScore, int matchCount, Instant indexedAt, boolean cited) {
    return sourceReference(fileName, relevanceScore, matchCount, indexedAt, cited, null);
  }

  private static SourceReference sourceReference(
      String fileName,
      double relevanceScore,
      int matchCount,
      Instant indexedAt,
      boolean cited,
      String sourceEntryUrl) {
    SourceReference sourceReference =
        new SourceReference(fileName, relevanceScore, matchCount, cited);
    sourceReference.setIndexedAt(indexedAt);
    sourceReference.setSourceEntryUrl(sourceEntryUrl);
    return sourceReference;
  }

  private Usage createUsage(int promptTokens, int completionTokens) {
    return new Usage() {
      @Override
      public Integer getPromptTokens() {
        return promptTokens;
      }

      @Override
      public Integer getCompletionTokens() {
        return completionTokens;
      }

      @Override
      public Object getNativeUsage() {
        return null;
      }
    };
  }
}
