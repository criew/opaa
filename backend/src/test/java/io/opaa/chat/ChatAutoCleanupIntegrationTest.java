package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.space.Space;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceRepository;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The automatic chat cleanup (#1923) against the real Liquibase schema, with the default periods of
 * 90 days without activity and 365 days in the archive. A run is driven with an explicit instant,
 * so the periods pass without waiting; instants in the past are set on the rows directly.
 */
@OpaaIntegrationTest
class ChatAutoCleanupIntegrationTest {

  private static final Duration DAY = Duration.ofDays(1);

  @Autowired private ChatAutoCleanupService cleanupService;
  @Autowired private ChatService chatService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private ChatRepository chatRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;

  private UUID organizationId;
  private Instant now;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Org Chat-Bereinigung"))
            .getId();
    now = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  @AfterEach
  void tearDown() {
    ownOrganizationFixtures.removeOrganizations(organizationId);
  }

  @Test
  void aSpaceWithTheCleanupOffKeepsEveryChat() {
    UUID author = createUser();
    UUID spaceId = createSpace(author, null);
    UUID chatId = createChat(spaceId, author);
    setLastActivity(chatId, now.minus(DAY.multipliedBy(800)));
    setArchivedAt(createArchivedChat(spaceId, author), now.minus(DAY.multipliedBy(800)));

    cleanupService.runOnce(now.plus(DAY.multipliedBy(1000)));

    assertThat(archivedAt(chatId, author)).isNull();
    assertThat(chatCount(spaceId)).isEqualTo(2);
  }

  @Test
  void anInactiveChatIsArchivedOnlyOnceTheArchivePeriodHasRunSinceSwitchingOn() {
    UUID author = createUser();
    UUID spaceId = createSpace(author, now);
    UUID chatId = createChat(spaceId, author);
    setLastActivity(chatId, now.minus(DAY.multipliedBy(800)));

    cleanupService.runOnce(now.plus(DAY.multipliedBy(89)));
    assertThat(archivedAt(chatId, author)).isNull();

    Instant run = now.plus(DAY.multipliedBy(91));
    cleanupService.runOnce(run);
    assertThat(archivedAt(chatId, author)).isEqualTo(Timestamp.from(run));
  }

  @Test
  void aChatArchivedLongBeforeSwitchingOnIsDeletedOnlyOnceTheDeletePeriodHasRunSinceThen() {
    UUID author = createUser();
    UUID spaceId = createSpace(author, now);
    UUID chatId = createArchivedChat(spaceId, author);
    setArchivedAt(chatId, now.minus(DAY.multipliedBy(800)));

    cleanupService.runOnce(now.plus(DAY.multipliedBy(364)));
    assertThat(chatExists(chatId)).isTrue();

    cleanupService.runOnce(now.plus(DAY.multipliedBy(366)));
    assertThat(chatExists(chatId)).isFalse();
  }

  @Test
  void anAutomaticallyArchivedChatIsDeletedWithItsMessagesAfterTheDeletePeriod() {
    UUID author = createUser();
    UUID spaceId = createSpace(author, now.minus(DAY.multipliedBy(200)));
    UUID chatId = createChat(spaceId, author);
    chatService.appendTurn(chatOf(chatId), "Wie lang ist die Frist?", "Vier Wochen.", List.of());
    setLastActivity(chatId, now.minus(DAY.multipliedBy(100)));

    cleanupService.runOnce(now);
    assertThat(archivedAt(chatId, author)).isEqualTo(Timestamp.from(now));
    cleanupService.runOnce(now.plus(DAY.multipliedBy(364)));
    assertThat(chatExists(chatId)).isTrue();

    cleanupService.runOnce(now.plus(DAY.multipliedBy(366)));
    assertThat(chatExists(chatId)).isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chat_messages WHERE chat_id = ?", Integer.class, chatId))
        .isZero();
  }

  @Test
  void aPinnedChatIsNeitherArchivedNorDeleted() {
    UUID author = createUser();
    UUID spaceId = createSpace(author, now.minus(DAY.multipliedBy(800)));
    UUID chatId = createChat(spaceId, author);
    chatService.pinChat(chatId, author);
    setLastActivity(chatId, now.minus(DAY.multipliedBy(800)));

    cleanupService.runOnce(now.plus(DAY.multipliedBy(1000)));

    assertThat(chatExists(chatId)).isTrue();
    assertThat(archivedAt(chatId, author)).isNull();
  }

  @Test
  void aNewQuestionBringsTheChatBackAndStartsTheArchivePeriodAnew() {
    UUID author = createUser();
    UUID spaceId = createSpace(author, now.minus(DAY.multipliedBy(200)));
    UUID chatId = createChat(spaceId, author);
    setLastActivity(chatId, now.minus(DAY.multipliedBy(100)));
    cleanupService.runOnce(now);
    assertThat(archivedAt(chatId, author)).isNotNull();

    chatService.appendTurn(chatOf(chatId), "Und die Ausnahme?", "Zwei Wochen.", List.of());
    assertThat(archivedAt(chatId, author)).isNull();

    cleanupService.runOnce(now.plus(DAY.multipliedBy(89)));
    assertThat(archivedAt(chatId, author)).isNull();
    cleanupService.runOnce(now.plus(DAY.multipliedBy(91)));
    assertThat(archivedAt(chatId, author)).isNotNull();
  }

  @Test
  void pinningAnAutomaticallyArchivedChatBringsItBackAndKeepsItFromTheCleanup() {
    UUID author = createUser();
    UUID spaceId = createSpace(author, now.minus(DAY.multipliedBy(200)));
    UUID chatId = createChat(spaceId, author);
    setLastActivity(chatId, now.minus(DAY.multipliedBy(100)));
    cleanupService.runOnce(now);

    chatService.pinChat(chatId, author);
    cleanupService.runOnce(now.plus(DAY.multipliedBy(1000)));

    assertThat(chatExists(chatId)).isTrue();
    assertThat(archivedAt(chatId, author)).isNull();
  }

  @Test
  void aChatPinnedAfterItWasFoundDueIsNotDeletedByItsBatch() {
    UUID author = createUser();
    UUID spaceId = createSpace(author, now.minus(DAY.multipliedBy(800)));
    UUID chatId = createArchivedChat(spaceId, author);
    setArchivedAt(chatId, now.minus(DAY.multipliedBy(800)));
    Instant cutoff = now.minus(DAY.multipliedBy(365));
    List<UUID> due = chatRepository.findDueForAutoCleanupDeletion(cutoff);
    assertThat(due).contains(chatId);

    chatService.pinChat(chatId, author);
    cleanupService.deleteBatch(due, cutoff);

    assertThat(chatExists(chatId)).isTrue();
  }

  @Test
  void aSpaceSwitchedOffAfterItsChatsWereFoundDueLosesNoneOfThemToTheBatch() {
    UUID author = createUser();
    UUID spaceId = createSpace(author, now.minus(DAY.multipliedBy(800)));
    UUID chatId = createArchivedChat(spaceId, author);
    setArchivedAt(chatId, now.minus(DAY.multipliedBy(800)));
    Instant cutoff = now.minus(DAY.multipliedBy(365));
    List<UUID> due = chatRepository.findDueForAutoCleanupDeletion(cutoff);

    setCleanupEnabledAt(spaceId, null);
    cleanupService.deleteBatch(due, cutoff);

    assertThat(chatExists(chatId)).isTrue();
  }

  @Test
  void afterSwitchingOffNothingIsArchivedOrDeleted() {
    UUID author = createUser();
    UUID spaceId = createSpace(author, now.minus(DAY.multipliedBy(800)));
    UUID inactive = createChat(spaceId, author);
    setLastActivity(inactive, now.minus(DAY.multipliedBy(800)));
    UUID archived = createArchivedChat(spaceId, author);
    setArchivedAt(archived, now.minus(DAY.multipliedBy(800)));

    setCleanupEnabledAt(spaceId, null);
    cleanupService.runOnce(now.plus(DAY.multipliedBy(1000)));

    assertThat(archivedAt(inactive, author)).isNull();
    assertThat(chatExists(archived)).isTrue();
  }

  @Test
  void switchingOnAgainStartsTheDeletePeriodAnew() {
    UUID author = createUser();
    UUID spaceId = createSpace(author, now.minus(DAY.multipliedBy(800)));
    UUID chatId = createArchivedChat(spaceId, author);
    setArchivedAt(chatId, now.minus(DAY.multipliedBy(800)));

    setCleanupEnabledAt(spaceId, now);
    cleanupService.runOnce(now.plus(DAY.multipliedBy(364)));
    assertThat(chatExists(chatId)).isTrue();

    cleanupService.runOnce(now.plus(DAY.multipliedBy(366)));
    assertThat(chatExists(chatId)).isFalse();
  }

  @Test
  void oneRunDeletesMoreChatsThanFitIntoOneBatch() {
    UUID author = createUser();
    UUID spaceId = createSpace(author, now.minus(DAY.multipliedBy(800)));
    for (int i = 0; i <= ChatAutoCleanupService.DELETE_BATCH_SIZE; i++) {
      createChat(spaceId, author);
    }
    jdbcTemplate.update(
        "INSERT INTO chat_personal_marks (chat_id, user_id, archived_at)"
            + " SELECT id, author_id, ? FROM chats WHERE space_id = ?",
        Timestamp.from(now.minus(DAY.multipliedBy(800))),
        spaceId);

    cleanupService.runOnce(now);

    assertThat(chatCount(spaceId)).isZero();
  }

  @Test
  void anEmptyMarkRowDoesNotStopTheArchiving() {
    UUID author = createUser();
    UUID spaceId = createSpace(author, now.minus(DAY.multipliedBy(200)));
    UUID chatId = createChat(spaceId, author);
    setLastActivity(chatId, now.minus(DAY.multipliedBy(100)));
    jdbcTemplate.update(
        "INSERT INTO chat_personal_marks (chat_id, user_id) VALUES (?, ?)", chatId, author);

    cleanupService.runOnce(now);

    assertThat(archivedAt(chatId, author)).isEqualTo(Timestamp.from(now));
  }

  @Test
  void anArchivedChatShowsWhenTheCleanupDeletesIt() {
    UUID author = createUser();
    Instant enabledAt = now.minus(DAY.multipliedBy(10));
    UUID spaceId = createSpace(author, enabledAt);
    UUID earlier = createArchivedChat(spaceId, author);
    setArchivedAt(earlier, now.minus(DAY.multipliedBy(800)));
    UUID later = createChat(spaceId, author);
    Instant laterArchivedAt = chatService.archiveChat(later, author).archivedAt();
    UUID active = createChat(spaceId, author);

    assertThat(chatService.getChat(earlier, author).getDeletionDueAt())
        .isEqualTo(enabledAt.plus(DAY.multipliedBy(365)));
    assertThat(chatService.getChat(later, author).getDeletionDueAt())
        .isEqualTo(laterArchivedAt.plus(DAY.multipliedBy(365)));
    assertThat(chatService.getChat(active, author).getDeletionDueAt()).isNull();
    assertThat(chatService.listArchivedChats(spaceId, author, PageRequest.of(0, 25)).getContent())
        .allSatisfy(entry -> assertThat(entry.deletionDueAt()).isNotNull());
  }

  @Test
  void anArchivedChatInASpaceWithoutCleanupShowsNoDeletionDate() {
    UUID author = createUser();
    UUID spaceId = createSpace(author, null);
    UUID chatId = createArchivedChat(spaceId, author);

    assertThat(chatService.getChat(chatId, author).getDeletionDueAt()).isNull();
    assertThat(chatService.archiveChat(chatId, author).deletionDueAt()).isNull();
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "frist@example.com", "Test User");
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }

  private UUID createSpace(UUID owner, Instant cleanupEnabledAt) {
    Space space =
        new Space(
            "Referat " + UUID.randomUUID(),
            null,
            false,
            SpaceVisibility.PRIVATE,
            owner,
            organizationId);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, organizationId));
    if (cleanupEnabledAt != null) {
      space.switchChatAutoCleanup(true, cleanupEnabledAt);
    }
    return spaceRepository.save(space).getId();
  }

  private UUID createChat(UUID spaceId, UUID author) {
    return chatService.createChat(spaceId, author, new ChatCreation().title("Fristen")).getId();
  }

  private UUID createArchivedChat(UUID spaceId, UUID author) {
    UUID chatId = createChat(spaceId, author);
    chatService.archiveChat(chatId, author);
    return chatId;
  }

  private Chat chatOf(UUID chatId) {
    return chatService.findOwnedChat(chatId, authorOf(chatId)).orElseThrow();
  }

  private UUID authorOf(UUID chatId) {
    return jdbcTemplate.queryForObject(
        "SELECT author_id FROM chats WHERE id = ?", UUID.class, chatId);
  }

  private void setCleanupEnabledAt(UUID spaceId, Instant instant) {
    if (instant == null) {
      jdbcTemplate.update(
          "UPDATE spaces SET chat_auto_cleanup_enabled_at = NULL WHERE id = ?", spaceId);
    } else {
      jdbcTemplate.update(
          "UPDATE spaces SET chat_auto_cleanup_enabled_at = ? WHERE id = ?",
          Timestamp.from(instant),
          spaceId);
    }
  }

  private void setLastActivity(UUID chatId, Instant instant) {
    jdbcTemplate.update(
        "UPDATE chats SET updated_at = ? WHERE id = ?", Timestamp.from(instant), chatId);
  }

  private void setArchivedAt(UUID chatId, Instant instant) {
    jdbcTemplate.update(
        "UPDATE chat_personal_marks SET archived_at = ? WHERE chat_id = ?",
        Timestamp.from(instant),
        chatId);
  }

  private Timestamp archivedAt(UUID chatId, UUID userId) {
    List<Timestamp> values =
        jdbcTemplate.queryForList(
            "SELECT archived_at FROM chat_personal_marks WHERE chat_id = ? AND user_id = ?",
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

  private int chatCount(UUID spaceId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM chats WHERE space_id = ?", Integer.class, spaceId);
  }
}
