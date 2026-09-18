package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.ChatRole;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.chat.ChatSearchMatch.Highlight;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.space.Space;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceRepository;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The chat search against the real Liquibase schema and its full-text column
 * (docs/features/chat-list.md, "Chatsuche"): what is found, how one match per chat is chosen, and
 * above all that nothing of another person's chats - not a match, not a page, not a count - ever
 * reaches the result.
 */
@OpaaIntegrationTest
class ChatSearchIntegrationTest {

  @Autowired private ChatService chatService;
  @Autowired private ChatMessageRepository chatMessageRepository;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;

  private UUID organizationId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org Chatsuche")).getId();
  }

  @AfterEach
  void tearDown() {
    ownOrganizationFixtures.removeOrganizations(organizationId);
  }

  @Test
  void aWordOfAQuestionAndOneOfAnAnswerAreFoundWithGermanStemming() {
    UUID author = createUser();
    UUID spaceId = createSpace(author);
    UUID asked = createChat(spaceId, author, "Erster Chat");
    UUID question = addMessage(asked, 0, ChatRole.USER, "Wie viele Widersprüche liegen vor?");
    addMessage(asked, 1, ChatRole.ASSISTANT, "Es liegen drei vor.");
    UUID answered = createChat(spaceId, author, "Zweiter Chat");
    addMessage(answered, 0, ChatRole.USER, "Was gilt hier?");
    UUID answer =
        addMessage(answered, 1, ChatRole.ASSISTANT, "Die Frist beträgt einen Monat nach Zugang.");

    ChatSearchMatch byQuestion = single(search(spaceId, author, "Widerspruch"));
    assertThat(byQuestion.chatId()).isEqualTo(asked);
    assertThat(byQuestion.messageId()).isEqualTo(question);
    assertThat(byQuestion.role()).isEqualTo(ChatRole.USER);
    assertThat(byQuestion.messageCreatedAt()).isNotNull();
    assertThat(highlighted(byQuestion)).containsExactly("Widersprüche");

    ChatSearchMatch byAnswer = single(search(spaceId, author, "Fristen"));
    assertThat(byAnswer.chatId()).isEqualTo(answered);
    assertThat(byAnswer.messageId()).isEqualTo(answer);
    assertThat(byAnswer.role()).isEqualTo(ChatRole.ASSISTANT);
    assertThat(highlighted(byAnswer)).containsExactly("Frist");
  }

  @Test
  void aTitleMatchWithoutAMessageMatchIsFoundAndNamesNoMessage() {
    UUID author = createUser();
    UUID spaceId = createSpace(author);
    UUID chatId = createChat(spaceId, author, "Rückfrage Kämmerei");
    addMessage(chatId, 0, ChatRole.USER, "Wie ist der Stand?");

    ChatSearchMatch match = single(search(spaceId, author, "Kämmerei"));

    assertThat(match.chatId()).isEqualTo(chatId);
    assertThat(match.title()).isEqualTo("Rückfrage Kämmerei");
    assertThat(match.messageId()).isNull();
    assertThat(match.role()).isNull();
    assertThat(match.messageCreatedAt()).isNull();
    assertThat(match.excerpt()).isEqualTo("Rückfrage Kämmerei");
    assertThat(highlighted(match)).containsExactly("Kämmerei");
  }

  /** A chat matching in its title and in a message names the message, so a click can jump there. */
  @Test
  void aChatMatchingInTitleAndMessageNamesTheMessage() {
    UUID author = createUser();
    UUID spaceId = createSpace(author);
    UUID chatId = createChat(spaceId, author, "Widerspruch Meier");
    UUID message = addMessage(chatId, 0, ChatRole.USER, "Ist der Widerspruch fristgerecht?");

    ChatSearchMatch match = single(search(spaceId, author, "Widerspruch"));

    assertThat(match.messageId()).isEqualTo(message);
  }

  @Test
  void oneMatchPerChatIsTheBestMessageAndTiesGoToTheNewest() {
    UUID author = createUser();
    UUID spaceId = createSpace(author);
    UUID chatId = createChat(spaceId, author, "Verlauf");
    addMessage(chatId, 0, ChatRole.USER, "Frist?");
    UUID best = addMessage(chatId, 1, ChatRole.ASSISTANT, "Die Frist, die Frist, die Frist.");
    addMessage(chatId, 2, ChatRole.USER, "Und danach?");
    UUID newestOfEqual = addMessage(chatId, 3, ChatRole.USER, "Frist?");
    setCreatedAt(best, Instant.parse("2026-09-01T10:00:00Z"));

    assertThat(single(search(spaceId, author, "Frist")).messageId()).isEqualTo(best);

    jdbcTemplate.update("DELETE FROM chat_messages WHERE id = ?", best);
    assertThat(single(search(spaceId, author, "Frist")).messageId()).isEqualTo(newestOfEqual);
  }

  @Test
  void chatsAreOrderedByRankThenByTheTimeOfTheirMatch() {
    UUID author = createUser();
    UUID spaceId = createSpace(author);
    UUID older = createChat(spaceId, author, "A");
    setCreatedAt(
        addMessage(older, 0, ChatRole.USER, "Frist"), Instant.parse("2026-01-01T00:00:00Z"));
    UUID newer = createChat(spaceId, author, "B");
    setCreatedAt(
        addMessage(newer, 0, ChatRole.USER, "Frist"), Instant.parse("2026-06-01T00:00:00Z"));
    UUID stronger = createChat(spaceId, author, "C");
    setCreatedAt(
        addMessage(stronger, 0, ChatRole.USER, "Frist Frist Frist"),
        Instant.parse("2025-01-01T00:00:00Z"));

    assertThat(search(spaceId, author, "Frist").matches())
        .extracting(ChatSearchMatch::chatId)
        .containsExactly(stronger, newer, older);
  }

  /**
   * The core of the issue: another person's chat in the same space with the same word is neither a
   * match nor does it shift a page boundary or {@code hasMore}. The other person is a space admin
   * and a system admin - no role reaches into someone else's chats. Fails without the {@code
   * author_id} condition of the permission filter.
   */
  @Test
  void anotherPersonsChatWithTheSameWordNeitherMatchesNorShiftsThePages() {
    UUID author = createUser();
    UUID admin = createUser();
    setSystemAdmin(admin);
    UUID spaceId = createSpace(admin, author);
    UUID ownStrong = createChat(spaceId, author, "Eigen 1");
    addMessage(ownStrong, 0, ChatRole.USER, "Widerspruch Widerspruch gegen den Bescheid");
    UUID ownWeak = createChat(spaceId, author, "Eigen 2");
    addMessage(ownWeak, 0, ChatRole.USER, "Ein Widerspruch");
    for (int i = 0; i < 3; i++) {
      UUID foreign = createChat(spaceId, admin, "Widerspruch fremd " + i);
      addMessage(foreign, 0, ChatRole.USER, "Widerspruch Widerspruch Widerspruch Widerspruch");
    }

    ChatSearchPage first = chatService.searchChats(spaceId, author, "Widerspruch", 0, 1);
    ChatSearchPage second = chatService.searchChats(spaceId, author, "Widerspruch", 1, 1);
    ChatSearchPage third = chatService.searchChats(spaceId, author, "Widerspruch", 2, 1);

    assertThat(first.matches()).extracting(ChatSearchMatch::chatId).containsExactly(ownStrong);
    assertThat(first.hasMore()).isTrue();
    assertThat(second.matches()).extracting(ChatSearchMatch::chatId).containsExactly(ownWeak);
    assertThat(second.hasMore()).isFalse();
    assertThat(third.matches()).isEmpty();
    assertThat(third.hasMore()).isFalse();
    assertThat(chatService.searchChats(spaceId, author, "Widerspruch", 0, 50).matches())
        .extracting(ChatSearchMatch::chatId)
        .containsExactly(ownStrong, ownWeak);
    // and the other way round: the admin finds exactly their own three
    assertThat(chatService.searchChats(spaceId, admin, "Widerspruch", 0, 50).matches())
        .hasSize(3)
        .extracting(ChatSearchMatch::chatId)
        .doesNotContain(ownStrong, ownWeak);
  }

  @Test
  void aChatOfAnotherSpaceIsNotFound() {
    UUID author = createUser();
    UUID spaceId = createSpace(author);
    UUID otherSpace = createSpace(author);
    UUID elsewhere = createChat(otherSpace, author, "Anderswo");
    addMessage(elsewhere, 0, ChatRole.USER, "Widerspruch");

    assertThat(search(spaceId, author, "Widerspruch").matches()).isEmpty();
    assertThat(single(search(otherSpace, author, "Widerspruch")).chatId()).isEqualTo(elsewhere);
  }

  @Test
  void withoutMembershipTheSearchAnswersLikeTheChatList() {
    UUID author = createUser();
    UUID outsider = createUser();
    UUID spaceId = createSpace(author);
    UUID unknownSpace = UUID.randomUUID();

    assertThatThrownBy(() -> chatService.listChats(spaceId, outsider))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> chatService.searchChats(spaceId, outsider, "Widerspruch", 0, 20))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("Sie sind kein Mitglied dieses Space");
    assertThatThrownBy(() -> chatService.listChats(unknownSpace, author))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> chatService.searchChats(unknownSpace, author, "Widerspruch", 0, 20))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Space nicht gefunden");
  }

  @Test
  void markupInAMessageIsReturnedAsLiteralTextWithOffsetsIntoIt() {
    UUID author = createUser();
    UUID spaceId = createSpace(author);
    UUID chatId = createChat(spaceId, author, "Markup");
    addMessage(chatId, 0, ChatRole.USER, "Steht <b>Frist</b> in a<c oder <script>x</script>?");

    ChatSearchMatch match = single(search(spaceId, author, "Frist"));

    assertThat(match.excerpt()).isEqualTo("Steht <b>Frist</b> in a<c oder <script>x</script>?");
    assertThat(highlighted(match)).containsExactly("Frist");
  }

  @Test
  void theLastWordAlsoMatchesAsAPrefixWhileTyping() {
    UUID author = createUser();
    UUID spaceId = createSpace(author);
    UUID chatId = createChat(spaceId, author, "Tippen");
    addMessage(chatId, 0, ChatRole.USER, "Die Widerspruchsbegründung fehlt noch");

    assertThat(single(search(spaceId, author, "Widerspr")).chatId()).isEqualTo(chatId);
    assertThat(single(search(spaceId, author, "fehlt Widersp")).chatId()).isEqualTo(chatId);
    assertThat(search(spaceId, author, "Bescheid").matches()).isEmpty();
  }

  @Test
  void aFileNumberIsFoundAsOneToken() {
    UUID author = createUser();
    UUID spaceId = createSpace(author);
    UUID chatId = createChat(spaceId, author, "Akte");
    addMessage(chatId, 0, ChatRole.USER, "Bitte prüfen Sie Az. 12/4-2026 bis Freitag.");

    assertThat(single(search(spaceId, author, "12/4-2026")).chatId()).isEqualTo(chatId);
  }

  @Test
  void afterDeletingAChatNothingOfItIsFoundAnymore() {
    UUID author = createUser();
    UUID spaceId = createSpace(author);
    UUID chatId = createChat(spaceId, author, "Rückfrage Kämmerei");
    addMessage(chatId, 0, ChatRole.USER, "Widerspruch");
    assertThat(search(spaceId, author, "Widerspruch").matches()).hasSize(1);
    assertThat(search(spaceId, author, "Kämmerei").matches()).hasSize(1);

    chatService.deleteChat(chatId, author);

    assertThat(search(spaceId, author, "Widerspruch").matches()).isEmpty();
    assertThat(search(spaceId, author, "Kämmerei").matches()).isEmpty();
  }

  @Test
  void aTooShortOrTooLongTermAndANegativePageAreRefusedInGerman() {
    UUID author = createUser();
    UUID spaceId = createSpace(author);

    assertThatThrownBy(() -> chatService.searchChats(spaceId, author, "  ab  ", 0, 20))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Der Suchbegriff muss mindestens 3 Zeichen lang sein");
    assertThatThrownBy(() -> chatService.searchChats(spaceId, author, "x".repeat(201), 0, 20))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Der Suchbegriff darf höchstens 200 Zeichen lang sein");
    assertThatThrownBy(() -> chatService.searchChats(spaceId, author, "Frist", -1, 20))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Die Seitennummer darf nicht negativ sein");
    assertThatThrownBy(() -> chatService.searchChats(spaceId, author, "Frist", 0, 0))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Die Seitengröße muss mindestens 1 betragen");
  }

  @Test
  void aTermOfOnlyStopWordsOrOperatorsFindsNothingAndFailsNothing() {
    UUID author = createUser();
    UUID spaceId = createSpace(author);
    UUID chatId = createChat(spaceId, author, "Die und das");
    addMessage(chatId, 0, ChatRole.USER, "Die und das");

    assertThat(search(spaceId, author, "die und das").matches()).isEmpty();
    assertThat(search(spaceId, author, "!&|:*()'").matches()).isEmpty();
  }

  /** PostgreSQL text holds no NUL; a control character in the term is a blank, not a 409. */
  @Test
  void aControlCharacterInTheTermIsReadAsABlank() {
    UUID author = createUser();
    UUID spaceId = createSpace(author);
    UUID chatId = createChat(spaceId, author, "Steuerzeichen");
    addMessage(chatId, 0, ChatRole.USER, "Frist zum Widerspruch");

    assertThat(single(search(spaceId, author, "Frist\u0000Widerspruch")).chatId())
        .isEqualTo(chatId);
    assertThat(single(search(spaceId, author, "\u0000Frist\n")).chatId()).isEqualTo(chatId);
    assertThatThrownBy(() -> chatService.searchChats(spaceId, author, "ab\u0000", 0, 20))
        .isInstanceOf(ValidationException.class);
  }

  // -----------------------------------------------------------------------------------------
  // Fixture
  // -----------------------------------------------------------------------------------------

  private ChatSearchPage search(UUID spaceId, UUID userId, String term) {
    return chatService.searchChats(spaceId, userId, term, null, null);
  }

  private static ChatSearchMatch single(ChatSearchPage page) {
    assertThat(page.matches()).hasSize(1);
    assertThat(page.hasMore()).isFalse();
    return page.matches().getFirst();
  }

  private static List<String> highlighted(ChatSearchMatch match) {
    return match.highlights().stream()
        .map((Highlight h) -> match.excerpt().substring(h.start(), h.end()))
        .toList();
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "suche@example.com", "Test User");
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }

  private void setSystemAdmin(UUID userId) {
    User user = userRepository.findById(userId).orElseThrow();
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    userRepository.save(user);
  }

  private UUID createSpace(UUID owner, UUID... otherMembers) {
    Space space =
        new Space(
            "Widerspruchsstelle", null, false, SpaceVisibility.PRIVATE, owner, organizationId);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationId));
    for (UUID member : otherMembers) {
      space.addMembership(new SpaceMembership(member, SpaceRole.MEMBER, organizationId));
    }
    return spaceRepository.save(space).getId();
  }

  private UUID createChat(UUID spaceId, UUID author, String title) {
    return chatService.createChat(spaceId, author, new ChatCreation().title(title)).getId();
  }

  private UUID addMessage(UUID chatId, int sequence, ChatRole role, String content) {
    return chatMessageRepository
        .save(new ChatMessage(chatId, sequence, role, content, null))
        .getId();
  }

  private void setCreatedAt(UUID messageId, Instant createdAt) {
    jdbcTemplate.update(
        "UPDATE chat_messages SET created_at = ? WHERE id = ?",
        Timestamp.from(createdAt),
        messageId);
  }
}
