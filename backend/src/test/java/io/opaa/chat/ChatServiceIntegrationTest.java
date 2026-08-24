package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import io.opaa.api.dto.ChatCreateRequest;
import io.opaa.api.dto.ChatDetail;
import io.opaa.api.dto.ChatSummary;
import io.opaa.api.dto.ChatUpdateRequest;
import io.opaa.api.dto.SourceReference;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.library.AssetGrantHistoryRepository;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.space.Space;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceMembershipRepository;
import io.opaa.space.SpaceRepository;
import io.opaa.space.SpaceRole;
import io.opaa.space.SpaceVisibility;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runs against a real Postgres database with the real, versioned Liquibase schema applied ({@code
 * spring.liquibase.enabled=true}, {@code ddl-auto=none}), not Hibernate-generated DDL - see #288
 * and {@link io.opaa.space.SpaceServiceIntegrationTest}'s Javadoc, which this class follows the
 * same pattern from, including its cleanup order: {@code userRepository.deleteAll()} deletes every
 * user in the shared test database, not just this class's own, so every non-system library -
 * regardless of which organization or test class created it - must be gone first, or a leftover
 * library from another class sharing this Spring context blocks the delete with a {@code
 * fk_knowledge_libraries_owner_user} RESTRICT violation. {@code chats.author_id} and {@code
 * chats.space_id} are plain {@code UUID} columns without {@code @ManyToOne}; Hibernate does not
 * create foreign keys for those, Liquibase does ({@code fk_chats_space}, {@code fk_chats_author},
 * migration 032).
 */
// Own @MockitoBean set (see below) means Spring's context cache still keys this to its own
// context regardless of the shared @OpaaIntegrationTest base - documented exception per AGENTS.md.
@OpaaIntegrationTest
class ChatServiceIntegrationTest {

  @Autowired private ChatService chatService;
  @Autowired private ChatRepository chatRepository;
  @Autowired private ChatMessageRepository chatMessageRepository;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private SpaceMembershipRepository spaceMembershipRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  // #557: ChatService#appendTurn now triggers ChatTitleGenerationService's real, Spring-managed
  // LLM call on a chat's first turn - without this mock, every appendTurn test in this class would
  // attempt a genuine call against whichever provider the active profile configures. That title job
  // (ChatTitleGenerationService#generateTitleAsync) runs on chatTitleTaskExecutor, genuinely off
  // the
  // calling thread, and several tests below (e.g. appendTurnPersistsBothMessagesAndDerives...,
  // appendTurnOrdersMessagesBySequenceAndTouchesUpdatedAt) trigger it via a first turn without
  // waiting for it to finish, since their assertions only concern the synchronous fallback title.
  // Unlike QueryIntegrationTest (#616/#621), replacing chatTitleTaskExecutor with a synchronous one
  // is not an option here: two tests in this class
  // (appendTurnAsynchronouslyAppliesAnLlmGenerated...,
  // appendTurnNeverOverwritesATitleRenamedWhileGenerationIsStillInFlight) deliberately exercise the
  // real, concurrent timing of that job. Instead, every stub on this class-wide shared mock below
  // uses Mockito's doReturn/doAnswer/doThrow - not when(...).thenReturn(...) - precisely because
  // doReturn(...).when(mock)... never invokes the mock while building the stub, so it cannot race a
  // still in-flight title job from an earlier test's unwaited-for call the way when(mock.call(...))
  // does (#623, same root cause as #616 - a leftover async chatModel.call() from a previous test
  // landing exactly while a later when(...) call is mid-setup throws a MockitoException).
  @MockitoBean private ChatModel chatModel;

  // #758: ChatTitleGenerationService now resolves its ChatClient via ActiveChatModelResolver on
  // every call instead of holding one built once at startup - stubbed once in setUp() below to
  // always hand back a ChatClient wrapping the class-wide chatModel mock above, so every existing
  // doReturn/doAnswer/doThrow stub on chatModel itself (see its own Javadoc for why those, not
  // when(...).thenReturn(...)) keeps working unchanged.
  @MockitoBean private ActiveChatModelResolver activeChatModelResolver;

  private UUID organizationA;

  @BeforeEach
  void setUp() {
    // Spring AI 2.0 merges ChatModel.getOptions() into every request; a bare mock returns null.
    // doReturn, not when(...).thenReturn(...) (#623): a previous test's title generation job can
    // still be in flight on chatTitleTaskExecutor when the next test's @BeforeEach runs and calls
    // this getOptions() as well - see the chatModel field's Javadoc.
    doReturn(ChatOptions.builder().build()).when(chatModel).getOptions();
    doReturn(ChatClient.builder(chatModel).build())
        .when(activeChatModelResolver)
        .resolveChatClient();
    chatMessageRepository.deleteAll();
    chatRepository.deleteAll();
    spaceMembershipRepository.deleteAll();
    spaceRepository.deleteAll();
    libraryRepository.deleteAll();
    grantHistoryRepository.deleteAll();
    membershipHistoryRepository.deleteAll();
    userRepository.deleteAll();
    organizationA =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org A")).getId();
  }

  @AfterEach
  void tearDown() {
    chatMessageRepository.deleteAll();
    chatRepository.deleteAll();
    spaceMembershipRepository.deleteAll();
    spaceRepository.deleteAll();
    libraryRepository.deleteAll();
    grantHistoryRepository.deleteAll();
    membershipHistoryRepository.deleteAll();
    userRepository.deleteAll();
    organizationRepository.deleteById(organizationA);
  }

  /** A library {@code readerId} may actually read - i.e. also holds an explicit grant on. */
  private UUID createLibrary(UUID readerId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, listed, source_type, created_at, updated_at)"
            + " VALUES (?, ?, 'Testbibliothek', 'USER', ?, 'PRIVATE', false, 'UPLOAD',"
            + " now(), now())",
        id,
        organizationA,
        readerId);
    // #202: ownership alone does not grant read access - LibraryAccessService#readableLibraryIds
    // only ever consults asset_grants (plus group grants and ORGANIZATION visibility).
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
            + " subject_user_id, role, created_at, updated_at)"
            + " VALUES (?, ?, ?, 'USER', ?, 'OWNER', now(), now())",
        UUID.randomUUID(),
        id,
        organizationA,
        readerId);
    return id;
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationA);
    return userRepository.save(user).getId();
  }

  private UUID createSpaceWithMember(UUID memberId) {
    Space space =
        new Space("Fachbereich", null, false, SpaceVisibility.PRIVATE, memberId, organizationA);
    space.addMembership(new SpaceMembership(memberId, SpaceRole.MEMBER, organizationA));
    return spaceRepository.save(space).getId();
  }

  @Test
  void memberCanCreateAndRetrieveTheirOwnChat() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);

    ChatDetail created =
        chatService.createChat(spaceId, author, new ChatCreateRequest().title("Frage zur Frist"));

    assertThat(created.getSpaceId()).isEqualTo(spaceId);
    assertThat(created.getAuthorId()).isEqualTo(author);
    assertThat(created.getTitle()).isEqualTo("Frage zur Frist");
    assertThat(created.getUseKnowledge()).isTrue();
    assertThat(created.getMessages()).isEmpty();

    ChatDetail fetched = chatService.getChat(created.getId(), author);
    assertThat(fetched.getId()).isEqualTo(created.getId());
  }

  @Test
  void chattingRequiresSpaceMembership() {
    UUID outsider = createUser();
    UUID member = createUser();
    UUID spaceId = createSpaceWithMember(member);

    assertThatThrownBy(() -> chatService.createChat(spaceId, outsider, new ChatCreateRequest()))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void creatingAChatInAnArchivedSpaceIsRejected() {
    // #543: an archived space accepts no new content - docs/features/spaces-and-assets.md#einen-
    // space-stilllegen-archivieren-statt-löschen.
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    Space space = spaceRepository.findById(spaceId).orElseThrow();
    space.archive();
    spaceRepository.save(space);

    assertThatThrownBy(() -> chatService.createChat(spaceId, author, new ChatCreateRequest()))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void updatingAChatInAnArchivedSpaceIsRejected() {
    // #613 review, finding 2: an archived space accepts no new content - not only no new chats,
    // but also no changes to an existing one.
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    ChatDetail created =
        chatService.createChat(spaceId, author, new ChatCreateRequest().title("Vor Archivierung"));
    Space space = spaceRepository.findById(spaceId).orElseThrow();
    space.archive();
    spaceRepository.save(space);

    assertThatThrownBy(
            () ->
                chatService.updateChat(
                    created.getId(), author, new ChatUpdateRequest().title("Neuer Titel")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void appendingATurnInAnArchivedSpaceIsRejected() {
    // #613 review, finding 2: "kein neuer Inhalt" also means no new message in an existing chat -
    // otherwise an archived space could never actually empty out into a state deleteSpace accepts.
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    Chat chat = chatRepository.save(new Chat(spaceId, author, organizationA, null, true, Set.of()));
    Space space = spaceRepository.findById(spaceId).orElseThrow();
    space.archive();
    spaceRepository.save(space);

    assertThatThrownBy(() -> chatService.appendTurn(chat, "Frage?", "Antwort.", List.of()))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    // The rejected turn must not have been persisted.
    assertThat(chatMessageRepository.findByChatIdOrderBySequenceAsc(chat.getId())).isEmpty();
  }

  @Test
  void creatingAChatInANonExistentSpaceReturnsNotFound() {
    UUID author = createUser();

    assertThatThrownBy(
            () -> chatService.createChat(UUID.randomUUID(), author, new ChatCreateRequest()))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void aChatIsInvisibleToEveryoneButItsAuthorIncludingASpaceMember() {
    UUID author = createUser();
    UUID otherMember = createUser();
    UUID spaceId = createSpaceWithMember(author);
    Space space = spaceRepository.findByIdWithMemberships(spaceId).orElseThrow();
    space.addMembership(new SpaceMembership(otherMember, SpaceRole.ADMIN, organizationA));
    spaceRepository.save(space);

    ChatDetail chat = chatService.createChat(spaceId, author, new ChatCreateRequest());

    // #525 acceptance criterion: a foreign user - even a fellow space admin - gets 404, not 403,
    // which would confirm the chat's existence.
    assertThatThrownBy(() -> chatService.getChat(chat.getId(), otherMember))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void listChatsOnlyReturnsTheCallersOwnChatsInThatSpace() {
    UUID author = createUser();
    UUID otherMember = createUser();
    UUID spaceId = createSpaceWithMember(author);
    Space space = spaceRepository.findByIdWithMemberships(spaceId).orElseThrow();
    space.addMembership(new SpaceMembership(otherMember, SpaceRole.MEMBER, organizationA));
    spaceRepository.save(space);

    chatService.createChat(spaceId, author, new ChatCreateRequest().title("Meine Frage"));
    chatService.createChat(spaceId, otherMember, new ChatCreateRequest().title("Fremde Frage"));

    List<ChatSummary> authorsChats = chatService.listChats(spaceId, author);

    assertThat(authorsChats).hasSize(1);
    assertThat(authorsChats.getFirst().getTitle()).isEqualTo("Meine Frage");
  }

  @Test
  void updateChatOnlyOverwritesFieldsThatWereProvided() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    UUID libraryId = createLibrary(author);
    ChatDetail created =
        chatService.createChat(
            spaceId, author, new ChatCreateRequest().title("Ursprünglich").useKnowledge(false));

    ChatDetail updated =
        chatService.updateChat(
            created.getId(),
            author,
            new ChatUpdateRequest().referencedLibraryIds(List.of(libraryId)));

    assertThat(updated.getTitle()).isEqualTo("Ursprünglich");
    assertThat(updated.getUseKnowledge()).isFalse();
    assertThat(updated.getReferencedLibraryIds()).containsExactly(libraryId);
  }

  @Test
  void createChatRejectsAnUnreadableReferencedLibraryWithoutRevealingWhy() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    UUID unreadableLibrary = UUID.randomUUID(); // does not even exist

    assertThatThrownBy(
            () ->
                chatService.createChat(
                    spaceId,
                    author,
                    new ChatCreateRequest().referencedLibraryIds(List.of(unreadableLibrary))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex -> {
              ResponseStatusException statusException = (ResponseStatusException) ex;
              assertThat(statusException.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              // #525 review, finding/nit b: identical message whether the library exists and is
              // merely unreadable, or does not exist at all - no existence oracle.
              assertThat(statusException.getReason())
                  .isEqualTo("referencedLibraryIds enthält eine Bibliothek, die nicht lesbar ist");
            });
  }

  @Test
  void createChatRejectsAnExistingButUnreadableLibraryWithTheIdenticalMessage() {
    UUID author = createUser();
    UUID otherUser = createUser();
    UUID spaceId = createSpaceWithMember(author);
    // Owned and readable by otherUser, not by author - exists, but author has no grant on it.
    UUID unreadableLibrary = createLibrary(otherUser);

    assertThatThrownBy(
            () ->
                chatService.createChat(
                    spaceId,
                    author,
                    new ChatCreateRequest().referencedLibraryIds(List.of(unreadableLibrary))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex -> {
              ResponseStatusException statusException = (ResponseStatusException) ex;
              assertThat(statusException.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(statusException.getReason())
                  .isEqualTo("referencedLibraryIds enthält eine Bibliothek, die nicht lesbar ist");
            });
  }

  @Test
  void updateChatRejectsAnUnreadableReferencedLibrary() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    ChatDetail created = chatService.createChat(spaceId, author, new ChatCreateRequest());
    UUID unreadableLibrary = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                chatService.updateChat(
                    created.getId(),
                    author,
                    new ChatUpdateRequest().referencedLibraryIds(List.of(unreadableLibrary))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void deleteChatRemovesItForItsAuthor() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    ChatDetail created = chatService.createChat(spaceId, author, new ChatCreateRequest());

    chatService.deleteChat(created.getId(), author);

    assertThatThrownBy(() -> chatService.getChat(created.getId(), author))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void appendTurnPersistsBothMessagesAndDerivesTheTitleFromTheFirstQuestion() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    Chat chat = chatRepository.save(new Chat(spaceId, author, organizationA, null, true, Set.of()));

    chatService.appendTurn(
        chat,
        "Wie hoch ist die Rückstellung für Altlastensanierung?",
        "Die Rückstellung beträgt 42.000 EUR.",
        List.of(new SourceReference("bericht.pdf", 0.9, 1, true)));

    ChatDetail detail = chatService.getChat(chat.getId(), author);
    assertThat(detail.getTitle())
        .isEqualTo("Wie hoch ist die Rückstellung für Altlastensanierung?");
    assertThat(detail.getMessages()).hasSize(2);
    assertThat(detail.getMessages().get(0).getRole()).isEqualTo(ChatRole.USER);
    assertThat(detail.getMessages().get(1).getRole()).isEqualTo(ChatRole.ASSISTANT);
    assertThat(detail.getMessages().get(1).getSources()).hasSize(1);
    assertThat(detail.getMessages().get(1).getSources().getFirst().getFileName())
        .isEqualTo("bericht.pdf");
  }

  @Test
  void appendTurnDoesNotOverwriteAnExplicitlySetTitle() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    ChatDetail created =
        chatService.createChat(spaceId, author, new ChatCreateRequest().title("Mein Titel"));
    Chat chat = chatRepository.findById(created.getId()).orElseThrow();

    chatService.appendTurn(chat, "Irgendeine Frage?", "Antwort.", List.of());

    ChatDetail detail = chatService.getChat(created.getId(), author);
    assertThat(detail.getTitle()).isEqualTo("Mein Titel");
  }

  /** #557 acceptance criterion 1: the first answer in a new chat gets an LLM-generated title. */
  @Test
  void appendTurnAsynchronouslyAppliesAnLlmGeneratedTitleOnTheFirstTurn() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    Chat chat = chatRepository.save(new Chat(spaceId, author, organizationA, null, true, Set.of()));
    // doReturn, not when(...).thenReturn(...) - see the chatModel field's Javadoc (#623).
    doReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("Rückstellung Altlastensanierung")))))
        .when(chatModel)
        .call(any(Prompt.class));

    chatService.appendTurn(
        chat,
        "Wie hoch ist die Rückstellung für Altlastensanierung?",
        "Die Rückstellung beträgt 42.000 EUR.",
        List.of());

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(chatService.getChat(chat.getId(), author).getTitle())
                    .isEqualTo("Rückstellung Altlastensanierung"));
  }

  /**
   * #557 acceptance criterion 2 / #561 review nit: a title the user set - even in the narrow window
   * between the question being asked and the async title generation actually completing - always
   * wins. Synchronized on an observable event, not a fixed wait: the mocked LLM call signals {@code
   * llmCallStarted} the instant it is invoked, then blocks on {@code renameApplied} until this test
   * explicitly releases it - so the rename below is deterministically guaranteed to commit while
   * {@link ChatTitleGenerationService#generateTitleAsync} is genuinely still in flight, not merely
   * "probably, if the machine isn't too slow today" the way a fixed {@code pollDelay} against a
   * fixed {@code Thread.sleep} would only probabilistically guarantee.
   */
  @Test
  void appendTurnNeverOverwritesATitleRenamedWhileGenerationIsStillInFlight() throws Exception {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    Chat chat = chatRepository.save(new Chat(spaceId, author, organizationA, null, true, Set.of()));
    CountDownLatch llmCallStarted = new CountDownLatch(1);
    CountDownLatch renameApplied = new CountDownLatch(1);
    // doAnswer, not when(...).thenAnswer(...) - see the chatModel field's Javadoc (#623). This does
    // not weaken the latch-based synchronization this test relies on: doAnswer merely avoids
    // invoking the mock while the stub is being built, the returned Answer still runs exactly once,
    // on the async job's own thread, when generateTitleAsync actually calls chatModel.call(...).
    doAnswer(
            invocation -> {
              llmCallStarted.countDown();
              assertThat(renameApplied.await(5, TimeUnit.SECONDS))
                  .as("the test must release the blocked LLM call within the timeout")
                  .isTrue();
              return new ChatResponse(List.of(new Generation(new AssistantMessage("LLM-Titel"))));
            })
        .when(chatModel)
        .call(any(Prompt.class));

    chatService.appendTurn(chat, "Frage", "Antwort", List.of());
    assertThat(llmCallStarted.await(5, TimeUnit.SECONDS))
        .as("generateTitleAsync must have called the LLM by now")
        .isTrue();

    chatService.updateChat(
        chat.getId(), author, new ChatUpdateRequest().title("Mein eigener Titel"));
    renameApplied.countDown();

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(chatService.getChat(chat.getId(), author).getTitle())
                    .isEqualTo("Mein eigener Titel"));
  }

  /**
   * #561 review, finding 2, Interleaving (a): {@code findById -> PATCH sets CUSTOM -> save writes
   * the title AND title_source=GENERATED back}. Deterministic, no timing involved at all - the
   * atomic {@link ChatRepository#applyGeneratedTitleIfGenerated} update itself is the fix, so
   * simply performing the rename (fully committed) before attempting the generated-title write
   * reproduces the exact interleaving regardless of when either would happen to run in production.
   */
  @Test
  void applyGeneratedTitleIfGeneratedNeverOverwritesATitleThatBecameCustomInTheMeantime() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    Chat chat = chatRepository.save(new Chat(spaceId, author, organizationA, null, true, Set.of()));
    chatService.updateChat(
        chat.getId(), author, new ChatUpdateRequest().title("Mein eigener Titel"));

    int updated = chatRepository.applyGeneratedTitleIfGenerated(chat.getId(), "LLM-Titel");

    assertThat(updated).isZero();
    Chat reloaded = chatRepository.findById(chat.getId()).orElseThrow();
    assertThat(reloaded.getTitle()).isEqualTo("Mein eigener Titel");
    assertThat(reloaded.getTitleSource()).isEqualTo(TitleSource.CUSTOM);
  }

  /**
   * #561 review, finding 2, Interleaving (b): {@code QueryService#query} loads a {@link Chat}
   * before retrieval and LLM answer generation (which can take seconds); a concurrent {@code PATCH}
   * renaming the chat in that window used to be clobbered when {@code appendTurn} wrote that stale
   * in-memory snapshot back via a full merge {@code save()}. {@code staleSnapshot} here plays
   * exactly that role: it is handed to {@code appendTurn} still carrying {@code title = null}, even
   * though the chat was already renamed (and is CUSTOM) in the database by the time this call
   * happens - deterministic, no timing involved, since {@code appendTurn}'s targeted, atomic
   * updates (see {@link ChatRepository}'s Javadoc) never read anything from the {@code Chat}
   * argument's own title/titleSource fields in the first place.
   */
  @Test
  void appendTurnNeverClobbersAConcurrentRenameEvenGivenAStaleInMemoryChatSnapshot() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    Chat staleSnapshot =
        chatRepository.save(new Chat(spaceId, author, organizationA, null, true, Set.of()));
    chatService.updateChat(
        staleSnapshot.getId(), author, new ChatUpdateRequest().title("Mein eigener Titel"));

    chatService.appendTurn(staleSnapshot, "Frage", "Antwort", List.of());

    ChatDetail detail = chatService.getChat(staleSnapshot.getId(), author);
    assertThat(detail.getTitle()).isEqualTo("Mein eigener Titel");
  }

  /**
   * #557 acceptance criterion 3: an LLM failure during title generation never surfaces - the
   * synchronous prefix-derived fallback {@code appendTurn} already committed stands unchanged.
   */
  @Test
  void appendTurnKeepsTheFallbackTitleWhenLlmTitleGenerationFails() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    Chat chat = chatRepository.save(new Chat(spaceId, author, organizationA, null, true, Set.of()));
    // doThrow, not when(...).thenThrow(...) - see the chatModel field's Javadoc (#623).
    doThrow(new RuntimeException("LLM nicht erreichbar")).when(chatModel).call(any(Prompt.class));

    chatService.appendTurn(chat, "Wie hoch ist die Rückstellung?", "42.000 EUR.", List.of());

    await()
        .pollDelay(500, TimeUnit.MILLISECONDS)
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(chatService.getChat(chat.getId(), author).getTitle())
                    .isEqualTo("Wie hoch ist die Rückstellung?"));
  }

  @Test
  void appendTurnOrdersMessagesBySequenceAndTouchesUpdatedAt() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    ChatDetail created = chatService.createChat(spaceId, author, new ChatCreateRequest());
    Chat chat = chatRepository.findById(created.getId()).orElseThrow();

    chatService.appendTurn(chat, "Erste Frage", "Erste Antwort", List.of());
    // #525 review round 2, finding/nit 1: the first turn's title-set already produces an UPDATE
    // by itself (ChatRepository#deriveTitleFromFirstQuestionIfAbsent), so comparing against the
    // pre-turn updatedAt would pass even without the separate touch() UPDATE - the assertion that
    // actually exercises touch() must compare against the state *after* a turn that changes
    // nothing else, i.e. the second one, whose only field-level change is that timestamp.
    Chat afterFirstTurn = chatRepository.findById(chat.getId()).orElseThrow();
    java.time.Instant updatedAtAfterFirstTurn = afterFirstTurn.getUpdatedAt();

    chatService.appendTurn(chat, "Zweite Frage", "Zweite Antwort", List.of());

    List<ChatMessage> messages = chatMessageRepository.findByChatIdOrderBySequenceAsc(chat.getId());
    assertThat(messages).hasSize(4);
    assertThat(messages).extracting(ChatMessage::getSequence).containsExactly(0, 1, 2, 3);
    assertThat(messages.get(0).getRole()).isEqualTo(ChatRole.USER);
    assertThat(messages.get(0).getContent()).isEqualTo("Erste Frage");
    assertThat(messages.get(2).getContent()).isEqualTo("Zweite Frage");

    // #525 review, finding/nit d: updated_at must move even though appendTurn's second call
    // changes no other field (the title was already set by the first call).
    Chat reloaded = chatRepository.findById(chat.getId()).orElseThrow();
    assertThat(reloaded.getUpdatedAt()).isAfter(updatedAtAfterFirstTurn);
  }

  /**
   * #525 review round 2, finding/nit 2: {@code nextSequenceFor} is a plain {@code COUNT(*)}, not a
   * locking read, so two turns appended to the same chat at nearly the same instant can compute the
   * same next sequence and race to insert it. Deterministically forces exactly that collision by
   * pre-inserting a row at the sequence {@code appendTurn} is about to compute, instead of relying
   * on genuine thread concurrency (flaky and slow) - {@code appendTurn} must recover via its retry
   * loop rather than surfacing the {@code uk_chat_messages_chat_sequence} violation to the caller.
   */
  @Test
  void appendTurnRetriesPastASequenceCollisionInsteadOfFailing() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    Chat chat = chatRepository.save(new Chat(spaceId, author, organizationA, null, true, Set.of()));

    // Simulates a concurrent turn that already won sequence 0 by the time this call's own
    // COUNT(*)-based nextSequenceFor would otherwise compute the same number.
    jdbcTemplate.update(
        "INSERT INTO chat_messages (id, chat_id, sequence, role, content, created_at)"
            + " VALUES (?, ?, 0, 'USER', 'Konkurrierende Frage', now())",
        UUID.randomUUID(),
        chat.getId());

    chatService.appendTurn(chat, "Meine Frage", "Meine Antwort", List.of());

    List<ChatMessage> messages = chatMessageRepository.findByChatIdOrderBySequenceAsc(chat.getId());
    assertThat(messages).hasSize(3);
    assertThat(messages).extracting(ChatMessage::getSequence).containsExactly(0, 1, 2);
    assertThat(messages.get(1).getContent()).isEqualTo("Meine Frage");
    assertThat(messages.get(2).getContent()).isEqualTo("Meine Antwort");
  }

  @Test
  void requireStillSpaceMemberRejectsAnAuthorNoLongerInTheSpace() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    Chat chat = chatRepository.save(new Chat(spaceId, author, organizationA, null, true, Set.of()));
    spaceMembershipRepository.deleteAll(spaceMembershipRepository.findBySpaceId(spaceId));

    assertThatThrownBy(() -> chatService.requireStillSpaceMember(chat))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void requireStillSpaceMemberAllowsAnAuthorStillInTheSpace() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    Chat chat = chatRepository.save(new Chat(spaceId, author, organizationA, null, true, Set.of()));

    assertThatCode(() -> chatService.requireStillSpaceMember(chat)).doesNotThrowAnyException();
  }

  @Test
  void findOwnedChatReturnsEmptyForAForeignChat() {
    UUID author = createUser();
    UUID stranger = createUser();
    UUID spaceId = createSpaceWithMember(author);
    ChatDetail created = chatService.createChat(spaceId, author, new ChatCreateRequest());

    assertThat(chatService.findOwnedChat(created.getId(), stranger)).isEmpty();
    assertThat(chatService.findOwnedChat(created.getId(), author)).isPresent();
    assertThat(chatService.findOwnedChat(null, author)).isEmpty();
  }

  @Test
  void effectiveLibraryScopeIntersectsReferencesWithReadableLibrariesWhenUseKnowledgeIsOff() {
    UUID readable = UUID.randomUUID();
    UUID notReadable = UUID.randomUUID();
    Chat chat =
        new Chat(
            UUID.randomUUID(),
            UUID.randomUUID(),
            organizationA,
            null,
            false,
            Set.of(readable, notReadable));

    Set<UUID> scope = chatService.effectiveLibraryScope(chat, Set.of(readable));

    assertThat(scope).containsExactly(readable);
  }

  @Test
  void effectiveLibraryScopeIsEveryReadableLibraryWhenUseKnowledgeIsOn() {
    UUID readableA = UUID.randomUUID();
    UUID readableB = UUID.randomUUID();
    Chat chat = new Chat(UUID.randomUUID(), UUID.randomUUID(), organizationA, null, true, Set.of());

    Set<UUID> scope = chatService.effectiveLibraryScope(chat, Set.of(readableA, readableB));

    assertThat(scope).containsExactlyInAnyOrder(readableA, readableB);
  }

  // #203: a space without any association never narrows - the fallback above already covers a
  // space id nobody ever inserted a row for, this covers the same rule for a real, persisted
  // space that genuinely has zero associations.
  @Test
  void effectiveLibraryScopeIsEveryReadableLibraryWhenTheSpaceHasNoAssociations() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    UUID readableA = createLibrary(author);
    UUID readableB = createLibrary(author);
    Chat chat = new Chat(spaceId, author, organizationA, null, true, Set.of());

    Set<UUID> scope = chatService.effectiveLibraryScope(chat, Set.of(readableA, readableB));

    assertThat(scope).containsExactlyInAnyOrder(readableA, readableB);
  }

  // #203: once a space has at least one association, @Alles-Wissen narrows to the associated
  // libraries intersected with the readable ones - a readable library that is not associated with
  // this space no longer appears, and an associated library the caller cannot read still does not
  // appear either.
  @Test
  void effectiveLibraryScopeIntersectsAssociatedWithReadableLibrariesWhenUseKnowledgeIsOn() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    UUID associatedAndReadable = createLibrary(author);
    UUID readableButNotAssociated = createLibrary(author);
    associateLibrary(spaceId, associatedAndReadable, author);
    Chat chat = new Chat(spaceId, author, organizationA, null, true, Set.of());

    Set<UUID> scope =
        chatService.effectiveLibraryScope(
            chat, Set.of(associatedAndReadable, readableButNotAssociated));

    assertThat(scope).containsExactly(associatedAndReadable);
  }

  // #706 review, finding 7a: the fail-open branch - a space with at least one association, none
  // of which are readable by this caller, must resolve to an EMPTY scope, never fall back to
  // "every readable library". Distinct from
  // effectiveLibraryScopeIsEveryReadableLibraryWhenTheSpaceHasNoAssociations, which covers the
  // unrelated "no curation at all" case.
  @Test
  void effectiveLibraryScopeIsEmptyWhenTheSpaceIsCuratedButNothingIsReadable() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);
    UUID associatedButUnreadable = createLibrary(author);
    UUID readableButNotAssociated = createLibrary(author);
    associateLibrary(spaceId, associatedButUnreadable, author);
    Chat chat = new Chat(spaceId, author, organizationA, null, true, Set.of());

    // The caller's own readable set does not even contain associatedButUnreadable - simulating a
    // caller with no grant on the one library this space curates.
    Set<UUID> scope = chatService.effectiveLibraryScope(chat, Set.of(readableButNotAssociated));

    assertThat(scope).isEmpty();
  }

  @Test
  void spaceHasLibraryAssociationsReflectsWhetherAnyAssociationExists() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMember(author);

    assertThat(chatService.spaceHasLibraryAssociations(spaceId)).isFalse();

    UUID library = createLibrary(author);
    associateLibrary(spaceId, library, author);

    assertThat(chatService.spaceHasLibraryAssociations(spaceId)).isTrue();
  }

  private void associateLibrary(UUID spaceId, UUID libraryId, UUID createdByUserId) {
    jdbcTemplate.update(
        "INSERT INTO space_asset_associations"
            + " (id, space_id, library_id, organization_id, created_by_user_id, created_at)"
            + " VALUES (?, ?, ?, ?, ?, now())",
        UUID.randomUUID(),
        spaceId,
        libraryId,
        organizationA,
        createdByUserId);
  }
}
