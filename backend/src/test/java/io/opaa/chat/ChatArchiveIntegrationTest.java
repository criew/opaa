package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.space.Space;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceOverview;
import io.opaa.space.SpaceRepository;
import io.opaa.space.SpaceService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The chat archive as a personal filing (docs/features/chat-list.md, "Archivieren") against the
 * real Liquibase schema: it moves a chat between the active list and the archive without changing
 * the chat, and it is distinct from an archived space.
 */
@OpaaIntegrationTest
class ChatArchiveIntegrationTest {

  @Autowired private ChatService chatService;
  @Autowired private SpaceService spaceService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;

  private UUID organizationId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org Chat-Archiv")).getId();
  }

  @AfterEach
  void tearDown() {
    ownOrganizationFixtures.removeOrganizations(organizationId);
  }

  @Test
  void anArchivedChatLeavesTheActiveListAndAppearsInTheArchive() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMembers(author);
    UUID archived = createChat(spaceId, author, "Erlass vom März");
    UUID active = createChat(spaceId, author, "Rückfrage Kämmerei");

    Instant archivedAt = chatService.archiveChat(archived, author).archivedAt();

    assertThat(archivedAt).isNotNull();
    assertThat(activeIds(spaceId, author)).containsExactly(active);
    List<ChatListEntry> archive =
        chatService.listArchivedChats(spaceId, author, PageRequest.of(0, 25)).getContent();
    assertThat(archive).extracting(entry -> entry.chat().getId()).containsExactly(archived);
    assertThat(archive.getFirst().archivedAt()).isEqualTo(archivedAt);
  }

  @Test
  void anArchivedChatStaysReadableAndCarriesItsArchivedAt() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMembers(author);
    UUID chatId = createChat(spaceId, author, "Fristen");
    chatService.appendTurn(chatOf(chatId), "Wie lang ist die Frist?", "Vier Wochen.", List.of());

    Instant archivedAt = chatService.archiveChat(chatId, author).archivedAt();
    ChatConversation conversation = chatService.getChat(chatId, author);

    assertThat(conversation.getArchivedAt()).isEqualTo(archivedAt);
    assertThat(conversation.getMessages()).hasSize(2);
  }

  @Test
  void unarchivingBringsTheChatBackUnpinned() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMembers(author);
    UUID chatId = createChat(spaceId, author, "Fristen");
    chatService.pinChat(chatId, author);
    chatService.archiveChat(chatId, author);

    ChatListEntry entry = chatService.unarchiveChat(chatId, author);

    assertThat(entry.archivedAt()).isNull();
    assertThat(entry.pinnedAt()).isNull();
    assertThat(activeIds(spaceId, author)).containsExactly(chatId);
    assertThat(chatService.listChats(spaceId, author).getFirst().pinnedAt()).isNull();
    assertThat(chatService.getChat(chatId, author).getArchivedAt()).isNull();
    assertThat(markRowCount(chatId)).isZero();
  }

  @Test
  void archivingUnpinsAndNeitherDirectionChangesTheChat() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMembers(author);
    UUID chatId = createChat(spaceId, author, "Fristen Übersicht");
    chatService.pinChat(chatId, author);
    Timestamp updatedBefore = updatedAtOf(chatId);

    ChatListEntry archived = chatService.archiveChat(chatId, author);

    assertThat(archived.pinnedAt()).isNull();
    assertThat(pinnedAtColumn(chatId, author)).isNull();
    assertThat(updatedAtOf(chatId)).isEqualTo(updatedBefore);
    assertThat(titleOf(chatId)).isEqualTo("Fristen Übersicht");

    chatService.unarchiveChat(chatId, author);

    assertThat(updatedAtOf(chatId)).isEqualTo(updatedBefore);
    assertThat(titleOf(chatId)).isEqualTo("Fristen Übersicht");
  }

  @Test
  void archivingTwiceKeepsTheFirstArchivedAt() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMembers(author);
    UUID chatId = createChat(spaceId, author, "Fristen");

    Instant first = chatService.archiveChat(chatId, author).archivedAt();
    Instant second = chatService.archiveChat(chatId, author).archivedAt();

    assertThat(second).isEqualTo(first);
  }

  @Test
  void pinningAnArchivedChatBringsItBack() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMembers(author);
    UUID chatId = createChat(spaceId, author, "Fristen");
    chatService.archiveChat(chatId, author);

    chatService.pinChat(chatId, author);

    assertThat(activeIds(spaceId, author)).containsExactly(chatId);
    assertThat(chatService.getChat(chatId, author).getArchivedAt()).isNull();
  }

  @Test
  void theOwnNewMessageBringsAnArchivedChatBack() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMembers(author);
    UUID chatId = createChat(spaceId, author, "Fristen");
    chatService.archiveChat(chatId, author);

    chatService.appendTurn(chatOf(chatId), "Und die Nachfrist?", "Zwei Wochen.", List.of());

    assertThat(activeIds(spaceId, author)).containsExactly(chatId);
    assertThat(chatService.getChat(chatId, author).getArchivedAt()).isNull();
    assertThat(markRowCount(chatId)).isZero();
  }

  /**
   * Only the sender's own filing changes: a mark another person holds on the chat - the shape a
   * shared chat will have - stays archived when the author writes.
   */
  @Test
  void aNewMessageLeavesAnotherPersonsArchiveAlone() {
    UUID author = createUser();
    UUID otherMember = createUser();
    UUID spaceId = createSpaceWithMembers(author, otherMember);
    UUID chatId = createChat(spaceId, author, "Fristen");
    jdbcTemplate.update(
        "INSERT INTO chat_personal_marks (chat_id, user_id, archived_at) VALUES (?, ?, now())",
        chatId,
        otherMember);

    chatService.appendTurn(chatOf(chatId), "Frage", "Antwort", List.of());

    assertThat(archivedAtColumn(chatId, otherMember)).isNotNull();
  }

  @Test
  void archivingWorksInAnArchivedSpaceAndLeavesTheSpaceVisibleWithItsChatCount() {
    UUID owner = createUser();
    UUID author = createUser();
    UUID spaceId = createSpaceOwnedBy(owner, author);
    UUID chatId = createChat(spaceId, author, "Fristen");
    archiveSpace(spaceId);
    CurrentUser authorCaller = CurrentUser.of(author, organizationId, SystemRole.USER, "Autorin");

    chatService.archiveChat(chatId, author);

    assertThat(activeIds(spaceId, author)).isEmpty();
    SpaceOverview overview = overviewOf(spaceId, authorCaller);
    assertThat(overview.chatCount()).isOne();

    chatService.unarchiveChat(chatId, author);
    assertThat(activeIds(spaceId, author)).containsExactly(chatId);
  }

  @Test
  void anArchivedChatInAnArchivedSpaceIsReadableButCannotBeContinued() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMembers(author);
    UUID chatId = createChat(spaceId, author, "Fristen");
    chatService.appendTurn(chatOf(chatId), "Frage", "Antwort", List.of());
    chatService.archiveChat(chatId, author);
    archiveSpace(spaceId);

    assertThat(chatService.getChat(chatId, author).getMessages()).hasSize(2);
    assertThatThrownBy(() -> chatService.appendTurn(chatOf(chatId), "Weiter?", "Nein.", List.of()))
        .isInstanceOf(ConflictException.class);
    assertThat(chatService.getChat(chatId, author).getArchivedAt()).isNotNull();
    assertThat(chatService.getChat(chatId, author).getMessages()).hasSize(2);
  }

  @Test
  void anotherPersonCannotArchiveOrUnarchiveTheChat() {
    UUID author = createUser();
    UUID otherMember = createUser();
    UUID spaceId = createSpaceWithMembers(author, otherMember);
    UUID chatId = createChat(spaceId, author, "Fristen");
    chatService.archiveChat(chatId, author);

    assertThatThrownBy(() -> chatService.archiveChat(chatId, otherMember))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> chatService.unarchiveChat(chatId, otherMember))
        .isInstanceOf(NotFoundException.class);
    assertThat(archivedAtColumn(chatId, author)).isNotNull();
    assertThat(
            chatService
                .listArchivedChats(spaceId, otherMember, PageRequest.of(0, 25))
                .getTotalElements())
        .isZero();
  }

  @Test
  void theArchiveIsPagedMostRecentlyArchivedFirst() {
    UUID author = createUser();
    UUID spaceId = createSpaceWithMembers(author);
    UUID first = createChat(spaceId, author, "Eins");
    UUID second = createChat(spaceId, author, "Zwei");
    UUID third = createChat(spaceId, author, "Drei");
    archiveAt(first, author, "2026-09-10T08:00:00Z");
    archiveAt(second, author, "2026-09-11T08:00:00Z");
    archiveAt(third, author, "2026-09-12T08:00:00Z");

    Page<ChatListEntry> page0 =
        chatService.listArchivedChats(spaceId, author, PageRequest.of(0, 2));
    Page<ChatListEntry> page1 =
        chatService.listArchivedChats(spaceId, author, PageRequest.of(1, 2));

    assertThat(page0.getTotalElements()).isEqualTo(3);
    assertThat(page0.getContent())
        .extracting(entry -> entry.chat().getId())
        .containsExactly(third, second);
    assertThat(page1.getContent()).extracting(entry -> entry.chat().getId()).containsExactly(first);
  }

  @Test
  void bulkActionsApplyOnlyToTheCallersOwnChatsOfTheSpace() {
    UUID author = createUser();
    UUID otherMember = createUser();
    UUID spaceId = createSpaceWithMembers(author, otherMember);
    UUID otherSpaceId = createSpaceWithMembers(author);
    UUID own1 = createChat(spaceId, author, "Eins");
    UUID own2 = createChat(spaceId, author, "Zwei");
    UUID ownElsewhere = createChat(otherSpaceId, author, "Anderswo");
    UUID foreign = createChat(spaceId, otherMember, "Fremd");
    UUID unknown = UUID.randomUUID();
    List<UUID> requested = List.of(own1, own2, ownElsewhere, foreign, unknown);

    assertThat(chatService.archiveChats(spaceId, author, requested))
        .containsExactlyInAnyOrder(own1, own2);
    assertThat(activeIds(spaceId, author)).isEmpty();
    assertThat(activeIds(otherSpaceId, author)).containsExactly(ownElsewhere);
    assertThat(markRowCount(foreign)).isZero();

    assertThat(chatService.unarchiveChats(spaceId, author, requested))
        .containsExactlyInAnyOrder(own1, own2);
    assertThat(activeIds(spaceId, author)).containsExactlyInAnyOrder(own1, own2);

    assertThat(chatService.deleteChats(spaceId, author, requested))
        .containsExactlyInAnyOrder(own1, own2);
    assertThat(chatExists(own1)).isFalse();
    assertThat(chatExists(own2)).isFalse();
    assertThat(chatExists(ownElsewhere)).isTrue();
    assertThat(chatExists(foreign)).isTrue();
  }

  /** A foreign id and an unknown id answer identically - the result reveals neither. */
  @Test
  void aBulkActionOnlyOnForeignOrUnknownIdsAppliesToNothing() {
    UUID author = createUser();
    UUID otherMember = createUser();
    UUID spaceId = createSpaceWithMembers(author, otherMember);
    UUID foreign = createChat(spaceId, otherMember, "Fremd");

    assertThat(chatService.archiveChats(spaceId, author, List.of(foreign))).isEmpty();
    assertThat(chatService.archiveChats(spaceId, author, List.of(UUID.randomUUID()))).isEmpty();
    assertThat(chatService.deleteChats(spaceId, author, List.of(foreign))).isEmpty();
    assertThat(chatExists(foreign)).isTrue();
    assertThat(activeIds(spaceId, otherMember)).containsExactly(foreign);
  }

  private List<UUID> activeIds(UUID spaceId, UUID userId) {
    return chatService.listChats(spaceId, userId).stream()
        .map(entry -> entry.chat().getId())
        .toList();
  }

  private SpaceOverview overviewOf(UUID spaceId, CurrentUser caller) {
    return spaceService.listSpaces(caller).stream()
        .filter(overview -> overview.space().getId().equals(spaceId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("space not listed for the caller"));
  }

  private Chat chatOf(UUID chatId) {
    return chatService.findOwnedChat(chatId, authorOf(chatId)).orElseThrow();
  }

  private UUID authorOf(UUID chatId) {
    return jdbcTemplate.queryForObject(
        "SELECT author_id FROM chats WHERE id = ?", UUID.class, chatId);
  }

  private void archiveAt(UUID chatId, UUID userId, String instant) {
    jdbcTemplate.update(
        "INSERT INTO chat_personal_marks (chat_id, user_id, archived_at) VALUES (?, ?, ?)",
        chatId,
        userId,
        Timestamp.from(Instant.parse(instant)));
  }

  private void archiveSpace(UUID spaceId) {
    Space space = spaceRepository.findById(spaceId).orElseThrow();
    space.archive();
    spaceRepository.save(space);
  }

  private int markRowCount(UUID chatId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM chat_personal_marks WHERE chat_id = ?", Integer.class, chatId);
  }

  private Timestamp pinnedAtColumn(UUID chatId, UUID userId) {
    return markColumn("pinned_at", chatId, userId);
  }

  private Timestamp archivedAtColumn(UUID chatId, UUID userId) {
    return markColumn("archived_at", chatId, userId);
  }

  private Timestamp markColumn(String column, UUID chatId, UUID userId) {
    List<Timestamp> values =
        jdbcTemplate.queryForList(
            "SELECT " + column + " FROM chat_personal_marks WHERE chat_id = ? AND user_id = ?",
            Timestamp.class,
            chatId,
            userId);
    return values.isEmpty() ? null : values.getFirst();
  }

  private boolean chatExists(UUID chatId) {
    return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chats WHERE id = ?", Integer.class, chatId)
        > 0;
  }

  private Timestamp updatedAtOf(UUID chatId) {
    return jdbcTemplate.queryForObject(
        "SELECT updated_at FROM chats WHERE id = ?", Timestamp.class, chatId);
  }

  private String titleOf(UUID chatId) {
    return jdbcTemplate.queryForObject(
        "SELECT title FROM chats WHERE id = ?", String.class, chatId);
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "archiv@example.com", "Test User");
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }

  private UUID createSpaceWithMembers(UUID owner, UUID... otherMembers) {
    return createSpaceOwnedBy(owner, otherMembers);
  }

  private UUID createSpaceOwnedBy(UUID owner, UUID... otherMembers) {
    Space space =
        new Space(
            "Widerspruch " + UUID.randomUUID(),
            null,
            false,
            SpaceVisibility.PRIVATE,
            owner,
            organizationId);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationId));
    for (UUID member : otherMembers) {
      space.addMembership(new SpaceMembership(member, SpaceRole.MEMBER, organizationId));
    }
    return spaceRepository.save(space).getId();
  }

  private UUID createChat(UUID spaceId, UUID author, String title) {
    return chatService.createChat(spaceId, author, new ChatCreation().title(title)).getId();
  }
}
