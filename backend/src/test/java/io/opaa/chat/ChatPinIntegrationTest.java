package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.NotFoundException;
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
 * Pinning as a personal mark (docs/features/chat-list.md, "Anheften") against the real Liquibase
 * schema: it never touches the chat row, works in an archived space, and stays invisible to - and
 * unreachable for - every other person.
 */
@OpaaIntegrationTest
class ChatPinIntegrationTest {

  @Autowired private ChatService chatService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;

  private UUID organizationId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org Anheften")).getId();
  }

  @AfterEach
  void tearDown() {
    ownOrganizationFixtures.removeOrganizations(organizationId);
  }

  @Test
  void aPinnedChatIsListedWithItsPinnedAtAndUnpinningClearsIt() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMembers(author);
    UUID pinned = createChat(spaceId, author, "Fristen Übersicht");
    UUID other = createChat(spaceId, author, "Erlass vom März");

    Instant pinnedAt = chatService.pinChat(pinned, author).pinnedAt();

    assertThat(pinnedAt).isNotNull();
    assertThat(pinnedAtInList(spaceId, author, pinned)).isEqualTo(pinnedAt);
    assertThat(pinnedAtInList(spaceId, author, other)).isNull();

    chatService.unpinChat(pinned, author);

    assertThat(pinnedAtInList(spaceId, author, pinned)).isNull();
    assertThat(markRowCount(pinned)).isZero();
  }

  @Test
  void pinningTwiceKeepsTheFirstPinnedAt() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMembers(author);
    UUID chatId = createChat(spaceId, author, "Fristen");

    Instant first = chatService.pinChat(chatId, author).pinnedAt();
    Instant second = chatService.pinChat(chatId, author).pinnedAt();

    assertThat(second).isEqualTo(first);
  }

  @Test
  void unpinningAChatThatWasNeverPinnedIsANoOp() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMembers(author);
    UUID chatId = createChat(spaceId, author, "Fristen");

    chatService.unpinChat(chatId, author);

    assertThat(markRowCount(chatId)).isZero();
  }

  @Test
  void pinningAndUnpinningLeaveTheChatsLastActivityUntouched() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMembers(author);
    UUID chatId = createChat(spaceId, author, "Fristen");
    Timestamp before = updatedAtOf(chatId);

    chatService.pinChat(chatId, author);
    assertThat(updatedAtOf(chatId)).isEqualTo(before);

    chatService.unpinChat(chatId, author);
    assertThat(updatedAtOf(chatId)).isEqualTo(before);
  }

  @Test
  void pinningWorksInAnArchivedSpace() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMembers(author);
    UUID chatId = createChat(spaceId, author, "Fristen");
    Space space = spaceRepository.findById(spaceId).orElseThrow();
    space.archive();
    spaceRepository.save(space);

    chatService.pinChat(chatId, author);
    assertThat(pinnedAtInList(spaceId, author, chatId)).isNotNull();

    chatService.unpinChat(chatId, author);
    assertThat(pinnedAtInList(spaceId, author, chatId)).isNull();
  }

  @Test
  void anotherPersonInTheSameSpaceCannotPinOrUnpinTheChat() {
    UUID author = createUser();
    UUID otherMember = createUser();
    UUID spaceId = createSpaceWithMembers(author, otherMember);
    UUID chatId = createChat(spaceId, author, "Fristen");
    chatService.pinChat(chatId, author);

    assertThatThrownBy(() -> chatService.pinChat(chatId, otherMember))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> chatService.unpinChat(chatId, otherMember))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> chatService.pinChat(UUID.randomUUID(), otherMember))
        .isInstanceOf(NotFoundException.class);
    // The author's pin survived the foreign unpin attempt.
    assertThat(pinnedAtInList(spaceId, author, chatId)).isNotNull();
  }

  /**
   * Each person sees only their own marks: the other member's list shows their own chat unpinned,
   * and a mark another person holds on the author's chat - the shape a shared chat will have - does
   * not reach the author's list.
   */
  @Test
  void noPersonSeesAnotherPersonsPin() {
    UUID author = createUser();
    UUID otherMember = createUser();
    UUID spaceId = createSpaceWithMembers(author, otherMember);
    UUID authorsChat = createChat(spaceId, author, "Fristen");
    UUID othersChat = createChat(spaceId, otherMember, "Eigener Chat");
    chatService.pinChat(authorsChat, author);

    List<ChatListEntry> othersList = chatService.listChats(spaceId, otherMember);
    assertThat(othersList).extracting(entry -> entry.chat().getId()).containsExactly(othersChat);
    assertThat(othersList.getFirst().pinnedAt()).isNull();

    chatService.unpinChat(authorsChat, author);
    jdbcTemplate.update(
        "INSERT INTO chat_personal_marks (chat_id, user_id, pinned_at) VALUES (?, ?, now())",
        authorsChat,
        otherMember);

    assertThat(pinnedAtInList(spaceId, author, authorsChat)).isNull();
  }

  @Test
  void deletingAPinnedChatRemovesItsMarkRow() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMembers(author);
    UUID chatId = createChat(spaceId, author, "Fristen");
    chatService.pinChat(chatId, author);
    assertThat(markRowCount(chatId)).isOne();

    chatService.deleteChat(chatId, author);

    assertThat(markRowCount(chatId)).isZero();
  }

  private Instant pinnedAtInList(UUID spaceId, UUID userId, UUID chatId) {
    return chatService.listChats(spaceId, userId).stream()
        .filter(entry -> entry.chat().getId().equals(chatId))
        .findFirst()
        .orElseThrow()
        .pinnedAt();
  }

  private int markRowCount(UUID chatId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM chat_personal_marks WHERE chat_id = ?", Integer.class, chatId);
  }

  private Timestamp updatedAtOf(UUID chatId) {
    return jdbcTemplate.queryForObject(
        "SELECT updated_at FROM chats WHERE id = ?", Timestamp.class, chatId);
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "pin@example.com", "Test User");
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }

  private UUID createSpaceWithMembers(UUID owner, UUID... otherMembers) {
    Space space =
        new Space("Widerspruch", null, false, SpaceVisibility.PRIVATE, owner, organizationId);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, organizationId));
    for (UUID member : otherMembers) {
      space.addMembership(SpaceMembership.ofUser(member, SpaceRole.MEMBER, organizationId));
    }
    return spaceRepository.save(space).getId();
  }

  private UUID createChat(UUID spaceId, UUID author, String title) {
    return chatService.createChat(spaceId, author, new ChatCreation().title(title)).getId();
  }
}
