package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.account.LocalUserService;
import io.opaa.api.types.ChatCleanupAfterLockStatus;
import io.opaa.api.types.LockReason;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.NotFoundException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.space.Space;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceRepository;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import io.opaa.test.SecondInstance;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The deletion of the chats of local accounts locked by the administration against the real Flyway
 * schema, with the default period of 365 days. Runs are driven with an explicit instant; the moment
 * from which periods count is set far back for each test and restored afterwards.
 */
@OpaaIntegrationTest
class LockedAccountChatCleanupIntegrationTest {

  private static final Duration DAY = Duration.ofDays(1);

  @Autowired private LockedAccountChatCleanupService cleanupService;
  @Autowired private LockedAccountChatCleanupStore store;
  @Autowired private LockedAccountChatCleanupDates dates;
  @Autowired private LockedAccountChatCleanupSettings settings;
  @Autowired private LockedAccountChatCleanupSettingRepository settingRepository;
  @Autowired private ChatService chatService;
  @Autowired private ChatRepository chatRepository;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private LocalCredentialsRepository credentialsRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private LocalUserService localUserService;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ConfigurableEnvironment environment;
  @Autowired private DataSource dataSource;
  @Autowired private Clock clock;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;

  private UUID organizationId;
  private Instant now;
  private Timestamp countingSinceBefore;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Org Sperrlöschung"))
            .getId();
    now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    countingSinceBefore =
        jdbc.queryForObject(
            "SELECT counting_since FROM chat_locked_account_cleanup_state WHERE id = 1",
            Timestamp.class);
    setCountingSince(now.minus(DAY.multipliedBy(5000)));
  }

  @AfterEach
  void tearDown() {
    jdbc.update(
        "UPDATE chat_locked_account_cleanup_state SET counting_since = ? WHERE id = 1",
        countingSinceBefore);
    jdbc.update(
        "DELETE FROM audit_incident_scope_grants WHERE organization_id = ?", organizationId);
    ownOrganizationFixtures.removeOrganizations(organizationId);
  }

  @Test
  void anAccountLockedLongerThanThePeriodLosesEveryChatInEverySpaceButKeepsAccountAndSpace() {
    UUID person = localAccount();
    UUID personal = space(person, true);
    UUID shared = space(person, false);
    UUID first = chat(personal, person);
    UUID pinned = chat(shared, person);
    chatService.pinChat(pinned, person);
    chatService.appendTurn(chatOf(first, person), "Wie lang?", "Vier Wochen.", List.of());
    lockByAdministration(person, now.minus(DAY.multipliedBy(366)));

    cleanupService.runOnce(now);

    assertThat(chatCountOf(person)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM chat_messages WHERE chat_id = ?", Integer.class, first))
        .isZero();
    assertThat(userRepository.findById(person)).isPresent();
    assertThat(spaceRepository.findById(personal)).isPresent();
    assertThat(spaceRepository.findById(shared)).isPresent();
  }

  @Test
  void chatsOfOtherPersonsStayUntouched() {
    UUID person = localAccount();
    UUID colleague = localAccount();
    UUID spaceId = space(person, false, colleague);
    chat(spaceId, person);
    UUID colleagues = chat(spaceId, colleague);
    lockByAdministration(person, now.minus(DAY.multipliedBy(400)));

    cleanupService.runOnce(now);

    assertThat(chatCountOf(person)).isZero();
    assertThat(chatRepository.existsById(colleagues)).isTrue();
  }

  @Test
  void anAccountLockedShorterThanThePeriodKeepsItsChats() {
    UUID person = localAccount();
    chat(space(person, true), person);
    lockByAdministration(person, now.minus(DAY.multipliedBy(364)));

    cleanupService.runOnce(now);

    assertThat(chatCountOf(person)).isOne();
  }

  @Test
  void anUnlockedAccountKeepsItsChatsAndANewLockStartsThePeriodAnew() {
    UUID person = localAccount();
    chat(space(person, true), person);
    lockByAdministration(person, now.minus(DAY.multipliedBy(500)));
    unlock(person);

    cleanupService.runOnce(now);
    assertThat(chatCountOf(person)).isOne();

    lockByAdministration(person, now.minus(DAY.multipliedBy(10)));
    cleanupService.runOnce(now);
    assertThat(chatCountOf(person)).isOne();

    cleanupService.runOnce(now.plus(DAY.multipliedBy(356)));
    assertThat(chatCountOf(person)).isZero();
  }

  @Test
  void aLockForInactivityStartsNoPeriod() {
    UUID person = localAccount();
    chat(space(person, true), person);
    LocalCredentials row = credentials(person);
    row.lock(LockReason.INACTIVITY, now.minus(DAY.multipliedBy(800)), null);
    credentialsRepository.save(row);

    cleanupService.runOnce(now);

    assertThat(chatCountOf(person)).isOne();
    assertThat(cleanupService.stateOf(organizationId, person).status())
        .isEqualTo(ChatCleanupAfterLockStatus.NOT_LOCKED);
  }

  @Test
  void aLockAfterFailedSignInsStartsNoPeriod() {
    UUID person = localAccount();
    chat(space(person, true), person);
    LocalCredentials row = credentials(person);
    row.recordLockoutUntil(now.plus(DAY), now.minus(DAY.multipliedBy(800)));
    credentialsRepository.save(row);
    jdbc.update(
        "UPDATE local_credentials SET locked_at = ? WHERE user_id = ?",
        Timestamp.from(now.minus(DAY.multipliedBy(800))),
        person);

    cleanupService.runOnce(now);

    assertThat(chatCountOf(person)).isOne();
  }

  @Test
  void anExpiredAccountStartsNoPeriod() {
    UUID person = localAccount();
    chat(space(person, true), person);
    LocalCredentials row = credentials(person);
    row.setExpiresAt(now.minus(DAY.multipliedBy(800)), now.minus(DAY.multipliedBy(900)));
    credentialsRepository.save(row);

    cleanupService.runOnce(now);

    assertThat(chatCountOf(person)).isOne();
  }

  @Test
  void anAccountOfAnIdentityProviderLockedByItsDirectoryStartsNoPeriod() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "anbieter@example.com", "Anbieter");
    user.setOrganizationId(organizationId);
    UUID person = userRepository.save(user).getId();
    chat(space(person, true), person);
    jdbc.update(
        "UPDATE users SET directory_locked_at = ? WHERE id = ?",
        Timestamp.from(now.minus(DAY.multipliedBy(800))),
        person);

    cleanupService.runOnce(now);

    assertThat(chatCountOf(person)).isOne();
    assertThatThrownBy(() -> cleanupService.stateOf(organizationId, person))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void aLockFromBeforeTheIntroductionKeepsItsChatsInTheFirstNight() {
    UUID person = localAccount();
    chat(space(person, true), person);
    lockByAdministration(person, now.minus(DAY.multipliedBy(400)));
    setCountingSince(now);

    cleanupService.runOnce(now.plus(DAY));
    assertThat(chatCountOf(person)).isOne();
    cleanupService.runOnce(now.plus(DAY.multipliedBy(364)));
    assertThat(chatCountOf(person)).isOne();

    cleanupService.runOnce(now.plus(DAY.multipliedBy(366)));
    assertThat(chatCountOf(person)).isZero();
  }

  @Test
  void switchedOffNothingIsDeletedAndSwitchingOnStartsEveryPeriodAnew() {
    UUID person = localAccount();
    chat(space(person, true), person);
    lockByAdministration(person, now.minus(DAY.multipliedBy(800)));

    service(false).runOnce(now);
    assertThat(chatCountOf(person)).isOne();
    assertThat(store.countingSince()).isEmpty();
    assertThat(service(false).stateOf(organizationId, person).status())
        .isEqualTo(ChatCleanupAfterLockStatus.SWITCHED_OFF);

    service(true).runOnce(now);
    assertThat(chatCountOf(person)).isOne();
    Instant switchedOn = store.countingSince().orElseThrow();

    cleanupService.runOnce(switchedOn.plus(DAY.multipliedBy(364)));
    assertThat(chatCountOf(person)).isOne();
    cleanupService.runOnce(switchedOn.plus(DAY.multipliedBy(366)));
    assertThat(chatCountOf(person)).isZero();
  }

  @Test
  void aHeldAccountKeepsItsChatsAndResumingCatchesUpInTheNextRun() {
    UUID admin = admin();
    UUID person = localAccount();
    chat(space(person, true), person);
    lockByAdministration(person, now.minus(DAY.multipliedBy(800)));

    assertThat(cleanupService.hold(actor(admin), person).status())
        .isEqualTo(ChatCleanupAfterLockStatus.HELD);
    cleanupService.hold(actor(admin), person);
    cleanupService.runOnce(now);
    assertThat(chatCountOf(person)).isOne();

    assertThat(cleanupService.resume(actor(admin), person).status())
        .isEqualTo(ChatCleanupAfterLockStatus.SCHEDULED);
    cleanupService.resume(actor(admin), person);
    cleanupService.runOnce(now);
    assertThat(chatCountOf(person)).isZero();
    assertThat(eventsOf(person, "LOCKED_ACCOUNT_CHAT_CLEANUP_HELD")).isOne();
    assertThat(eventsOf(person, "LOCKED_ACCOUNT_CHAT_CLEANUP_RESUMED")).isOne();
  }

  @Test
  void anOpenKlaerungSuspendsTheDeletionAndItsEndCatchesUp() {
    UUID person = localAccount();
    UUID requester = admin();
    UUID approver = admin();
    chat(space(person, true), person);
    lockByAdministration(person, now.minus(DAY.multipliedBy(800)));
    UUID grant = requestKlaerung(person, requester);

    cleanupService.runOnce(now);
    assertThat(chatCountOf(person)).isOne();

    approve(grant, approver, now.plus(DAY.multipliedBy(30)));
    cleanupService.runOnce(now);
    assertThat(chatCountOf(person)).isOne();

    approve(grant, approver, now.minus(DAY));
    cleanupService.runOnce(now);
    assertThat(chatCountOf(person)).isZero();
  }

  @Test
  void theDeletionDateIsTheSameWithAndWithoutChatsAndWithAndWithoutAnOpenKlaerung() {
    Instant lockedAt = now.minus(DAY.multipliedBy(30));
    UUID withChats = localAccount();
    chat(space(withChats, true), withChats);
    lockByAdministration(withChats, lockedAt);
    UUID withoutChats = localAccount();
    lockByAdministration(withoutChats, lockedAt);
    UUID inKlaerung = localAccount();
    chat(space(inKlaerung, true), inKlaerung);
    lockByAdministration(inKlaerung, lockedAt);
    requestKlaerung(inKlaerung, admin());

    LockedAccountChatCleanup expected =
        new LockedAccountChatCleanup(
            ChatCleanupAfterLockStatus.SCHEDULED, false, 365, lockedAt.plus(DAY.multipliedBy(365)));
    assertThat(cleanupService.stateOf(organizationId, withChats)).isEqualTo(expected);
    assertThat(cleanupService.stateOf(organizationId, withoutChats)).isEqualTo(expected);
    assertThat(cleanupService.stateOf(organizationId, inKlaerung)).isEqualTo(expected);
    assertThat(dates.earliestDeletionAt(withChats, lockedAt))
        .contains(lockedAt.plus(DAY.multipliedBy(365)));
  }

  @Test
  void theRunRecordsOneEntryPerAccountWithoutCountTitleOrContent() {
    UUID person = localAccount();
    UUID spaceId = space(person, true);
    chatService.createChat(spaceId, person, new ChatCreation().title("Disziplinarsache Meier"));
    chat(spaceId, person);
    Instant lockedAt = now.minus(DAY.multipliedBy(400));
    lockByAdministration(person, lockedAt);

    cleanupService.runOnce(now);

    List<Map<String, Object>> rows = deletionEventsOf(person);
    assertThat(rows).hasSize(1);
    Map<String, Object> row = rows.getFirst();
    assertThat(row.get("actor_kind")).isEqualTo("SYSTEM_PROCESS");
    assertThat(row.get("actor_ref")).isEqualTo(LockedAccountChatCleanupService.SYSTEM_ACTOR);
    assertThat(row.get("object_type")).isEqualTo("USER_ACCOUNT");
    assertThat(row.get("object_label")).isNull();
    assertThat(row.get("after")).isNull();
    assertThat(row.get("reason")).isNull();
    String before = String.valueOf(row.get("before"));
    assertThat(before)
        .contains("periodStart", lockedAt.toString(), "lockReason", "ADMIN")
        .doesNotContain("Disziplinarsache", "Fristen", "count", "chats", spaceId.toString());
  }

  @Test
  void oneRunDeletesAtMostItsCapAndTheNextRunTheRestWithAnEntryEach() {
    UUID person = localAccount();
    UUID spaceId = space(person, true);
    for (int i = 0; i < 5; i++) {
      chat(spaceId, person);
    }
    lockByAdministration(person, now.minus(DAY.multipliedBy(400)));

    LockedAccountChatCleanupService.Result first = cleanupService.runOnce(now, 3);
    assertThat(first.deleted()).isEqualTo(3);
    assertThat(chatCountOf(person)).isEqualTo(2);

    cleanupService.runOnce(now, 3);
    assertThat(chatCountOf(person)).isZero();
    assertThat(deletionEventsOf(person)).hasSize(2);
  }

  @Test
  void oneRunDeletesMoreChatsThanFitIntoOneBatch() {
    UUID person = localAccount();
    UUID spaceId = space(person, true);
    for (int i = 0; i <= LockedAccountChatCleanupService.DELETE_BATCH_SIZE; i++) {
      chat(spaceId, person);
    }
    lockByAdministration(person, now.minus(DAY.multipliedBy(400)));

    cleanupService.runOnce(now);

    assertThat(chatCountOf(person)).isZero();
    assertThat(deletionEventsOf(person)).hasSize(1);
  }

  @Test
  void chatsThatReappearAfterARestoreAreDeletedAgainWithANewEntry() {
    UUID person = localAccount();
    UUID spaceId = space(person, true);
    chat(spaceId, person);
    lockByAdministration(person, now.minus(DAY.multipliedBy(400)));
    cleanupService.runOnce(now);
    assertThat(chatCountOf(person)).isZero();

    chat(spaceId, person);
    cleanupService.runOnce(now.plus(DAY));

    assertThat(chatCountOf(person)).isZero();
    assertThat(deletionEventsOf(person)).hasSize(2);
  }

  @Test
  void aBatchLeavesTheChatsOfAnAccountUnlockedSinceItWasFoundDue() {
    UUID person = localAccount();
    chat(space(person, true), person);
    lockByAdministration(person, now.minus(DAY.multipliedBy(400)));
    Instant cutoff = now.minus(DAY.multipliedBy(365));
    LockedAccountChatCleanupStore.DueAccount due = store.findDue(cutoff, now, 10).getFirst();

    unlock(person);
    int deleted = cleanupService.deleteBatch(due, cutoff, now, 10, true);

    assertThat(deleted).isZero();
    assertThat(chatCountOf(person)).isOne();
    assertThat(deletionEventsOf(person)).isEmpty();
  }

  @Test
  void anAccountLockedForInactivityIsLockedByTheAdministrationAndThePeriodStartsThen() {
    UUID admin = admin();
    UUID person = localAccount();
    chat(space(person, true), person);
    LocalCredentials row = credentials(person);
    row.lock(LockReason.INACTIVITY, now.minus(DAY.multipliedBy(800)), null);
    credentialsRepository.save(row);

    localUserService.lock(actor(admin), person);

    LocalCredentials locked = credentials(person);
    assertThat(locked.getLockedReason()).isEqualTo(LockReason.ADMIN);
    Instant lockedAt = locked.getLockedAt();
    assertThat(lockedAt).isAfter(now.minus(DAY));
    assertThat(eventsOf(person, "LOCAL_USER_LOCKED")).isOne();
    assertThat(eventsOf(person, "LOCAL_USER_UNLOCKED")).isZero();
    assertThat(cleanupService.stateOf(organizationId, person).earliestDeletionAt())
        .isEqualTo(lockedAt.plus(DAY.multipliedBy(365)));

    cleanupService.runOnce(now.plus(DAY));
    assertThat(chatCountOf(person)).isOne();
    cleanupService.runOnce(lockedAt.plus(DAY.multipliedBy(366)));
    assertThat(chatCountOf(person)).isZero();
  }

  @Test
  void switchingOffInTheAdministrationStartsEveryPeriodAnewWithoutANightInBetween() {
    UUID person = localAccount();
    chat(space(person, true), person);
    lockByAdministration(person, now.minus(DAY.multipliedBy(800)));

    settings.change(actor(admin()), false, 365);
    assertThat(store.countingSince()).isEmpty();
    settings.change(actor(admin()), null, null);

    cleanupService.runOnce(now);
    assertThat(chatCountOf(person)).isOne();
    assertThat(store.countingSince()).isPresent();
  }

  /**
   * A second instance that still switches the retired deletion off pauses it, whichever instance
   * runs; before, the start would have switched it on again without a word. Removing the variable
   * ends the pause.
   */
  @Test
  void aRetiredSwitchOffOnASecondInstancePausesTheDeletionUntilItIsGone() {
    UUID person = localAccount();
    chat(space(person, true), person);
    lockByAdministration(person, now.minus(DAY.multipliedBy(800)));
    try (SecondInstance second =
        SecondInstance.startWith(
            environment,
            dataSource,
            clock,
            Map.of("OPAA_CHAT_LOCKED_ACCOUNT_CLEANUP_ENABLED", "false"))) {
      cleanupService.runOnce(now);
      assertThat(chatCountOf(person)).isOne();
    }

    cleanupService.runOnce(now);
    assertThat(chatCountOf(person)).isZero();
  }

  @Test
  void switchedOffLocalAccountsStartNoPeriod() {
    UUID person = localAccount();
    chat(space(person, true), person);
    List<Map<String, Object>> before =
        jdbc.queryForList("SELECT id, enabled FROM oidc_providers WHERE provider_type = 'LOCAL'");
    LocalAccountFixtures fixtures = fixturesFactory.create();
    io.opaa.auth.OidcProvider local = fixtures.localProvider(false);
    try {
      cleanupService.runOnce(now.plus(DAY.multipliedBy(1000)));

      assertThat(chatCountOf(person)).isOne();
      assertThat(cleanupService.stateOf(organizationId, person).status())
          .isEqualTo(ChatCleanupAfterLockStatus.NOT_LOCKED);
    } finally {
      if (before.isEmpty()) {
        fixtures.deleteProvider(local.getId());
      } else {
        fixtures.localProvider(Boolean.TRUE.equals(before.getFirst().get("enabled")));
      }
    }
  }

  @Test
  void anAccountOfASwitchedOffOrRemovedProviderStartsNoPeriod() {
    LocalAccountFixtures fixtures = fixturesFactory.create();
    String issuer = "https://idp-" + UUID.randomUUID() + ".stadt.example/realms/x";
    io.opaa.auth.OidcProvider provider = fixtures.oidcProvider("Abgeschaltet", issuer, "opaa");
    try {
      fixtures.disableProvider(provider.getId());
      UUID disabled = providerAccount(issuer);
      UUID removed = providerAccount("https://entfernt-" + UUID.randomUUID() + ".stadt.example");
      chat(space(disabled, true), disabled);
      chat(space(removed, true), removed);

      cleanupService.runOnce(now.plus(DAY.multipliedBy(1000)));

      assertThat(chatCountOf(disabled)).isOne();
      assertThat(chatCountOf(removed)).isOne();
    } finally {
      fixtures.deleteProvider(provider.getId());
    }
  }

  private UUID providerAccount(String issuer) {
    User user = new User(UUID.randomUUID().toString(), issuer, "anbieter@example.com", "Anbieter");
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }

  /** The service after the switch was set to {@code enabled} with the default period. */
  private LockedAccountChatCleanupService service(boolean enabled) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> settingRepository.upsert(enabled, 365));
    return cleanupService;
  }

  private UUID localAccount() {
    User user = User.localAccount(UUID.randomUUID() + "@stadt.example", "Gesperrte Person");
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    credentialsRepository.save(
        new LocalCredentials(id, "Testkonto", now.minus(DAY.multipliedBy(2000))));
    return id;
  }

  private UUID admin() {
    User user = User.localAccount(UUID.randomUUID() + "@stadt.example", "Verwaltung");
    user.setOrganizationId(organizationId);
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    UUID id = userRepository.save(user).getId();
    credentialsRepository.save(new LocalCredentials(id, "Testkonto", now));
    return id;
  }

  private CurrentUser actor(UUID adminId) {
    return CurrentUser.of(
        adminId, organizationId, SystemRole.SYSTEM_ADMIN, "Verwaltung", "admin@stadt.example");
  }

  private LocalCredentials credentials(UUID userId) {
    return credentialsRepository.findById(userId).orElseThrow();
  }

  private void lockByAdministration(UUID userId, Instant lockedAt) {
    LocalCredentials row = credentials(userId);
    row.unlock(lockedAt);
    row.lock(LockReason.ADMIN, lockedAt, null);
    credentialsRepository.save(row);
  }

  private void unlock(UUID userId) {
    LocalCredentials row = credentials(userId);
    row.unlock(now);
    credentialsRepository.save(row);
  }

  private void setCountingSince(Instant instant) {
    jdbc.update(
        "UPDATE chat_locked_account_cleanup_state SET counting_since = ? WHERE id = 1",
        Timestamp.from(instant));
  }

  private UUID space(UUID owner, boolean isDefault, UUID... members) {
    Space space = new Space("Referat " + UUID.randomUUID(), null, isDefault, owner, organizationId);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, organizationId));
    for (UUID member : members) {
      space.addMembership(SpaceMembership.ofUser(member, SpaceRole.MEMBER, organizationId));
    }
    return spaceRepository.save(space).getId();
  }

  private UUID chat(UUID spaceId, UUID author) {
    return chatService.createChat(spaceId, author, new ChatCreation().title("Fristen")).getId();
  }

  private Chat chatOf(UUID chatId, UUID author) {
    return chatService.findOwnedChat(chatId, author).orElseThrow();
  }

  private int chatCountOf(UUID author) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM chats WHERE author_id = ?", Integer.class, author);
  }

  private UUID requestKlaerung(UUID subject, UUID requester) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO audit_incident_scope_grants (id, organization_id, subject_user_id,"
            + " scope_start, scope_end, purpose, reason, requested_by_user_id, requested_at,"
            + " status) VALUES (?, ?, ?, ?, ?, 'SECURITY_INCIDENT', 'Verdacht', ?, ?, 'PENDING')",
        id,
        organizationId,
        subject,
        Timestamp.from(now.minus(DAY.multipliedBy(7))),
        Timestamp.from(now),
        requester,
        Timestamp.from(now));
    return id;
  }

  private void approve(UUID grant, UUID approver, Instant usableUntil) {
    jdbc.update(
        "UPDATE audit_incident_scope_grants SET status = 'APPROVED', approved_by_user_id = ?,"
            + " approved_at = ?, usable_until = ? WHERE id = ?",
        approver,
        Timestamp.from(now.minus(DAY.multipliedBy(2))),
        Timestamp.from(usableUntil),
        grant);
  }

  private List<Map<String, Object>> deletionEventsOf(UUID userId) {
    return jdbc.queryForList(
        "SELECT actor_kind, actor_ref, object_type, object_label, CAST(before AS text) AS before,"
            + " CAST(after AS text) AS after, reason FROM audit_log"
            + " WHERE event_type = 'LOCKED_ACCOUNT_CHATS_DELETED' AND subject_ref IN"
            + " (SELECT CAST(pseudonym_id AS text) FROM audit_actor_pseudonyms WHERE user_id = ?)",
        userId);
  }

  private long eventsOf(UUID userId, String type) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE event_type = ? AND subject_ref IN"
            + " (SELECT CAST(pseudonym_id AS text) FROM audit_actor_pseudonyms WHERE user_id = ?)",
        Long.class,
        type,
        userId);
  }
}
