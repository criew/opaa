package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import io.opaa.api.types.ChatNoteItemKind;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.query.QueryResult;
import io.opaa.query.QueryService;
import io.opaa.test.OpaaMockedChatModelIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Gesprächsnotiz end to end (#1487, docs/features/conversation-memory.md, "Bauteil 2"):
 * condensation after a turn, rendering into both prompts, the state an answer carries, and the
 * author-only, archive-refused removal of a single point.
 *
 * <p>Under {@link OpaaMockedChatModelIntegrationTest} the condensation runs <b>inline</b> ({@code
 * SynchronousChatTitleExecutorConfiguration}), so every assertion below sees the state after the
 * executor has finished without an {@code await()}. The model is scripted by what the prompt asks
 * for, not by call order: the number of model calls per turn depends on whether the search scope is
 * empty and whether it is the chat's first turn, and an order-based script would silently answer
 * the wrong call.
 */
@OpaaMockedChatModelIntegrationTest
class ChatNoteIntegrationTest {

  private static final UUID DEFAULT_ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  /** A phrase only the condensation prompt carries - see {@link ChatNoteExtraction}. */
  private static final String CONDENSATION_MARKER = "Nicht festhalten";

  /** A phrase only the sub-question decomposition prompt carries. */
  private static final String DECOMPOSITION_MARKER = "Du zerlegst die aktuelle Nutzerfrage";

  /** A phrase only the answer prompt carries. */
  private static final String ANSWER_MARKER = "CITATION RULES";

  /**
   * A phrase only the <em>rendered</em> note block carries. Deliberately not the word
   * "Gesprächsnotiz": the decomposition instruction names it unconditionally (it is a rule, not
   * context), so asserting on that word would find it even without a single note point.
   */
  private static final String NOTE_BLOCK_MARKER = "Angaben der fragenden Person";

  @Autowired private ChatModel chatModel;
  @Autowired private ActiveChatModelResolver activeChatModelResolver;
  @Autowired private QueryService queryService;
  @Autowired private ChatService chatService;
  @Autowired private ChatNoteService chatNoteService;
  @Autowired private ChatNoteItemRepository chatNoteItemRepository;
  @Autowired private ChatNoteProperties chatNoteProperties;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MeterRegistry meterRegistry;

  private UUID userId;
  private UUID otherUserId;
  private UUID spaceId;
  private UUID chatId;

  /** Every prompt the model was asked, in order - the script and the assertion share this list. */
  private final List<String> promptContents = new ArrayList<>();

  @BeforeEach
  void setUp() {
    promptContents.clear();
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    when(activeChatModelResolver.resolveChatClient())
        .thenReturn(ChatClient.builder(chatModel).build());

    userId = insertUser("note-it");
    otherUserId = insertUser("note-it-other");
    spaceId = insertSpaceWithMembership(userId);
    chatId = insertChat(spaceId, userId);
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM chat_note_items WHERE chat_id = ?", chatId);
    jdbcTemplate.update("DELETE FROM chat_messages WHERE chat_id = ?", chatId);
    jdbcTemplate.update("DELETE FROM chats WHERE author_id = ?", userId);
    jdbcTemplate.update("DELETE FROM space_memberships WHERE space_id = ?", spaceId);
    jdbcTemplate.update("DELETE FROM spaces WHERE id = ?", spaceId);
    jdbcTemplate.update("DELETE FROM users WHERE id in (?, ?)", userId, otherUserId);
  }

  // ---------------------------------------------------------------------------------------
  // Condensation
  // ---------------------------------------------------------------------------------------

  /**
   * The two halves of the lifecycle in one assertion: the answer carries the state that went
   * <em>into</em> it, and the condensation of the very same turn is persisted once the executor is
   * done - visible from the next turn on, never in this response.
   */
  @Test
  void theAnswerCarriesTheStateBeforeTheTurnAndTheTurnsOwnCondensationIsPersisted() {
    scriptModel("RAHMEN: Arbeitet in der Nebenstelle 3");

    QueryResult result =
        queryService.query(
            "Ich arbeite in der Nebenstelle 3. Was kostet ein Ausweis?",
            chatId,
            asCaller(userId),
            true,
            List.of());

    assertThat(result.noteItems())
        .as("the condensation of this very turn must not appear in its own answer")
        .isEmpty();
    assertThat(chatNoteService.points(chatId))
        .extracting(ChatNotePoint::text, ChatNotePoint::kind)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "Arbeitet in der Nebenstelle 3", ChatNoteItemKind.RAHMEN));

    scriptModel("KEINE");
    QueryResult second =
        queryService.query("Und wie lange dauert das?", chatId, asCaller(userId), true, List.of());

    assertThat(second.noteItems())
        .extracting(ChatNotePoint::text)
        .containsExactly("Arbeitet in der Nebenstelle 3");
  }

  /**
   * The decided failure semantics: the note stays as it was, the turn is counted, nothing is
   * retried - and the next turn condenses normally, without catching up on the lost one.
   */
  @Test
  void aFailedCondensationLeavesTheNoteUnchangedAndIsNotCaughtUpOnTheNextTurn() {
    double failedBefore = noteExtractionCount("failed");
    scriptModelFailingCondensation();

    queryService.query("Bezugsjahr ist 2024.", chatId, asCaller(userId), true, List.of());

    assertThat(chatNoteService.points(chatId)).isEmpty();
    assertThat(noteExtractionCount("failed")).isEqualTo(failedBefore + 1);

    scriptModel("RAHMEN: Zuständig für Widersprüche");
    queryService.query("Ich bearbeite Widersprüche.", chatId, asCaller(userId), true, List.of());

    assertThat(chatNoteService.points(chatId))
        .extracting(ChatNotePoint::text)
        .as("the lost turn is gone for good - only the new turn contributes")
        .containsExactly("Zuständig für Widersprüche");
  }

  /**
   * The archive guard in its actual race window: the space is archived <em>during</em> the
   * condensation model call, i.e. after the turn was persisted and before the note is written.
   */
  @Test
  void aSpaceArchivedBetweenAnswerAndCondensationDiscardsTheResult() {
    double discardedBefore = noteExtractionCount("discarded");
    when(chatModel.call(any(Prompt.class)))
        .thenAnswer(
            invocation -> {
              String contents = record(invocation.getArgument(0));
              if (contents.contains(CONDENSATION_MARKER)) {
                jdbcTemplate.update("UPDATE spaces SET archived = true WHERE id = ?", spaceId);
                return response("RAHMEN: Bezugsjahr 2024");
              }
              return response("Antwort");
            });

    queryService.query("Bezugsjahr ist 2024.", chatId, asCaller(userId), true, List.of());

    assertThat(chatNoteService.points(chatId)).isEmpty();
    assertThat(noteExtractionCount("discarded")).isEqualTo(discardedBefore + 1);
  }

  // ---------------------------------------------------------------------------------------
  // Rendering
  // ---------------------------------------------------------------------------------------

  /**
   * The kind's whole purpose: a Darstellungswunsch is noise for the search and the point of the
   * answer, so the decomposition block holds only the {@code RAHMEN} points while the answer block
   * holds every one.
   */
  @Test
  void theDecompositionSeesOnlyRahmenPointsWhileTheAnswerSeesAll() {
    UUID libraryId = insertReadableLibrary();
    try {
      seedNote("Bezugsjahr 2024", ChatNoteItemKind.RAHMEN);
      seedNote("Möchte knappe Antworten", ChatNoteItemKind.ANTWORTFORM);
      scriptModel("KEINE");

      queryService.query("Was kostet der Ausweis?", chatId, asCaller(userId), true, List.of());

      assertThat(promptFor(DECOMPOSITION_MARKER))
          .contains("Bezugsjahr 2024")
          .doesNotContain("Möchte knappe Antworten");
      assertThat(promptFor(ANSWER_MARKER))
          .contains("Bezugsjahr 2024")
          .contains("Möchte knappe Antworten");
    } finally {
      dropLibrary(libraryId);
    }
  }

  /**
   * The kind filter at its boundary: a note of nothing but Darstellungswünsche leaves the
   * decomposition with no block at all, while the answer still gets its own.
   */
  @Test
  void aNoteOfOnlyAntwortformPointsLeavesTheDecompositionWithoutABlock() {
    UUID libraryId = insertReadableLibrary();
    try {
      seedNote("Möchte knappe Antworten", ChatNoteItemKind.ANTWORTFORM);
      scriptModel("KEINE");

      queryService.query("Was kostet der Ausweis?", chatId, asCaller(userId), true, List.of());

      assertThat(promptFor(DECOMPOSITION_MARKER)).doesNotContain(NOTE_BLOCK_MARKER);
      assertThat(promptFor(ANSWER_MARKER))
          .contains(NOTE_BLOCK_MARKER)
          .contains("Möchte knappe Antworten");
    } finally {
      dropLibrary(libraryId);
    }
  }

  @Test
  void withoutAnyPointNeitherPromptCarriesANoteBlock() {
    UUID libraryId = insertReadableLibrary();
    try {
      scriptModel("KEINE");

      queryService.query("Was kostet der Ausweis?", chatId, asCaller(userId), true, List.of());

      // Not the word "Gesprächsnotiz": the decomposition instruction names it unconditionally,
      // by design - only the rendered block carries the header below.
      assertThat(promptFor(DECOMPOSITION_MARKER)).doesNotContain(NOTE_BLOCK_MARKER);
      assertThat(promptFor(ANSWER_MARKER)).doesNotContain(NOTE_BLOCK_MARKER);
    } finally {
      dropLibrary(libraryId);
    }
  }

  // ---------------------------------------------------------------------------------------
  // Cap
  // ---------------------------------------------------------------------------------------

  /** Appending past the cap drops the oldest point - FIFO, without rewriting the list. */
  @Test
  void appendingPastTheCapDropsTheOldestPoint() {
    int cap = chatNoteProperties.maxItems();
    for (int i = 0; i < cap; i++) {
      chatNoteService.append(
          chatId, List.of(new ChatNoteCandidate("Angabe " + i, ChatNoteItemKind.RAHMEN)));
    }

    chatNoteService.append(
        chatId, List.of(new ChatNoteCandidate("Neueste Angabe", ChatNoteItemKind.RAHMEN)));

    List<String> texts = chatNoteService.points(chatId).stream().map(ChatNotePoint::text).toList();
    assertThat(texts).hasSize(cap);
    assertThat(texts).doesNotContain("Angabe 0").endsWith("Neueste Angabe");
  }

  // ---------------------------------------------------------------------------------------
  // Reading and removing a point
  // ---------------------------------------------------------------------------------------

  /**
   * Exercised through {@link ChatService} rather than over HTTP: under this suite's {@code dev}
   * auth profile every request runs as a configured dev user, so a MockMvc request could not act as
   * this test's own chat author at all. {@code ChatControllerTest} covers the endpoint wiring and
   * its status codes.
   */
  @Test
  void theChatCarriesItsNote() {
    UUID itemId = seedNote("Bezugsjahr 2024", ChatNoteItemKind.RAHMEN);

    assertThat(chatService.getChat(chatId, userId).getNoteItems())
        .extracting(ChatNotePoint::id, ChatNotePoint::text, ChatNotePoint::kind)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                itemId, "Bezugsjahr 2024", ChatNoteItemKind.RAHMEN));
  }

  @Test
  void theAuthorRemovesOnePoint() {
    UUID kept = seedNote("Bezugsjahr 2024", ChatNoteItemKind.RAHMEN);
    UUID removed = seedNote("Möchte knappe Antworten", ChatNoteItemKind.ANTWORTFORM);

    chatService.deleteNoteItem(chatId, removed, userId);

    assertThat(chatNoteService.points(chatId)).extracting(ChatNotePoint::id).containsExactly(kept);
  }

  /** Same 404 for "no such point" and "not your chat" - a foreign chat must stay unprobeable. */
  @Test
  void anotherUserGetsTheSameNotFoundAsAnUnknownPoint() {
    UUID itemId = seedNote("Bezugsjahr 2024", ChatNoteItemKind.RAHMEN);

    assertThatThrownBy(() -> chatService.deleteNoteItem(chatId, itemId, otherUserId))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> chatService.deleteNoteItem(chatId, UUID.randomUUID(), userId))
        .isInstanceOf(NotFoundException.class);

    assertThat(chatNoteItemRepository.findByIdAndChatId(itemId, chatId)).isPresent();
  }

  /** An archived space accepts no change to an existing chat - including removing a note point. */
  @Test
  void removingAPointIsRefusedWhileTheSpaceIsArchived() {
    UUID itemId = seedNote("Bezugsjahr 2024", ChatNoteItemKind.RAHMEN);
    jdbcTemplate.update("UPDATE spaces SET archived = true WHERE id = ?", spaceId);

    assertThatThrownBy(() -> chatService.deleteNoteItem(chatId, itemId, userId))
        .isInstanceOf(ConflictException.class);

    assertThat(chatNoteItemRepository.findByIdAndChatId(itemId, chatId)).isPresent();
  }

  @Test
  void deletingTheChatDeletesItsNote() {
    UUID itemId = seedNote("Bezugsjahr 2024", ChatNoteItemKind.RAHMEN);

    chatService.deleteChat(chatId, userId);

    assertThat(chatNoteItemRepository.findById(itemId)).isEmpty();
  }

  // ---------------------------------------------------------------------------------------
  // Fixture
  // ---------------------------------------------------------------------------------------

  /** Answers the condensation prompt with {@code condensation}, every other prompt with prose. */
  private void scriptModel(String condensation) {
    when(chatModel.call(any(Prompt.class)))
        .thenAnswer(
            invocation -> {
              String contents = record(invocation.getArgument(0));
              return contents.contains(CONDENSATION_MARKER)
                  ? response(condensation)
                  : response("Antwort");
            });
  }

  private void scriptModelFailingCondensation() {
    when(chatModel.call(any(Prompt.class)))
        .thenAnswer(
            invocation -> {
              String contents = record(invocation.getArgument(0));
              if (contents.contains(CONDENSATION_MARKER)) {
                throw new IllegalStateException("Verdichtungsmodell nicht erreichbar");
              }
              return response("Antwort");
            });
  }

  private String record(Prompt prompt) {
    String contents = prompt.getContents();
    promptContents.add(contents);
    return contents;
  }

  private String promptFor(String marker) {
    return promptContents.stream()
        .filter(contents -> contents.contains(marker))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No prompt containing " + marker + " was sent"));
  }

  private static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  private double noteExtractionCount(String reason) {
    return Search.in(meterRegistry)
        .name("opaa.chat.note.extraction")
        .tag("reason", reason)
        .counter()
        .count();
  }

  private static CurrentUser asCaller(UUID userId) {
    return CurrentUser.of(userId, DEFAULT_ORGANIZATION_ID, SystemRole.USER, null);
  }

  private UUID seedNote(String text, ChatNoteItemKind kind) {
    return chatNoteItemRepository
        .save(new ChatNoteItem(chatId, chatNoteItemRepository.nextPositionFor(chatId), text, kind))
        .getId();
  }

  private UUID insertUser(String subject) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, ?, now(), 'USER', ?)",
        id,
        subject,
        subject + "@example.com",
        subject,
        DEFAULT_ORGANIZATION_ID);
    return id;
  }

  private UUID insertSpaceWithMembership(UUID memberId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO spaces (id, name, is_default, visibility, owner_id, organization_id,"
            + " created_at, updated_at) VALUES (?, 'Notiz-Fachbereich', false, 'PRIVATE', ?, ?,"
            + " now(), now())",
        id,
        memberId,
        DEFAULT_ORGANIZATION_ID);
    jdbcTemplate.update(
        "INSERT INTO space_memberships (id, user_id, space_id, role, organization_id, created_at)"
            + " VALUES (?, ?, ?, 'ADMIN', ?, now())",
        UUID.randomUUID(),
        memberId,
        id,
        DEFAULT_ORGANIZATION_ID);
    return id;
  }

  private UUID insertChat(UUID spaceId, UUID authorId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO chats (id, space_id, author_id, organization_id, use_knowledge, status,"
            + " created_at, updated_at) VALUES (?, ?, ?, ?, true, 'PRIVATE', now(), now())",
        id,
        spaceId,
        authorId,
        DEFAULT_ORGANIZATION_ID);
    return id;
  }

  /**
   * A library the user may read, which is what makes the search scope non-empty and therefore the
   * sub-question decomposition run at all - without it the query short-circuits past it.
   */
  private UUID insertReadableLibrary() {
    UUID libraryId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, listed, source_type, created_at, updated_at)"
            + " VALUES (?, ?, 'Notiz-Bibliothek', 'USER', ?, 'PRIVATE', false, 'UPLOAD', now(),"
            + " now())",
        libraryId,
        DEFAULT_ORGANIZATION_ID,
        userId);
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type, subject_user_id,"
            + " role, created_at, updated_at) VALUES (?, ?, ?, 'USER', ?, 'OWNER', now(), now())",
        UUID.randomUUID(),
        libraryId,
        DEFAULT_ORGANIZATION_ID,
        userId);
    return libraryId;
  }

  private void dropLibrary(UUID libraryId) {
    jdbcTemplate.update("DELETE FROM asset_grants WHERE library_id = ?", libraryId);
    jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE id = ?", libraryId);
  }
}
