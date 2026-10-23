package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.LockReason;
import io.opaa.api.types.SpaceRole;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.space.Space;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceRepository;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import io.opaa.test.TwoInstanceRace;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two instances run the deletion of a locked account's chats at the same moment (ADR-0053,
 * Entscheidung 7): the scheduled-run lock keeps that from happening, but a run outliving its lock
 * must neither delete twice nor write the account's audit entry twice.
 */
@OpaaIntegrationTest
class LockedAccountChatCleanupTwoInstanceIntegrationTest {

  private static final Duration DAY = Duration.ofDays(1);

  @Autowired private LockedAccountChatCleanupService cleanupService;
  @Autowired private ChatService chatService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private LocalCredentialsRepository credentialsRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;

  private UUID organizationId;
  private Timestamp countingSinceBefore;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Org Sperrlöschung zwei Instanzen"))
            .getId();
    countingSinceBefore =
        jdbc.queryForObject(
            "SELECT counting_since FROM chat_locked_account_cleanup_state WHERE id = 1",
            Timestamp.class);
    jdbc.update(
        "UPDATE chat_locked_account_cleanup_state SET counting_since = ? WHERE id = 1",
        Timestamp.from(Instant.now().minus(DAY.multipliedBy(5000))));
  }

  @AfterEach
  void tearDown() {
    jdbc.update(
        "UPDATE chat_locked_account_cleanup_state SET counting_since = ? WHERE id = 1",
        countingSinceBefore);
    ownOrganizationFixtures.removeOrganizations(organizationId);
  }

  @Test
  void twoInstancesDeleteTheChatsOnceAndRecordOneEntry() throws Exception {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    User user = User.localAccount(UUID.randomUUID() + "@stadt.example", "Gesperrte Person");
    user.setOrganizationId(organizationId);
    UUID person = userRepository.save(user).getId();
    LocalCredentials row =
        new LocalCredentials(person, "Testkonto", now.minus(DAY.multipliedBy(2000)));
    row.lock(LockReason.ADMIN, now.minus(DAY.multipliedBy(400)), null);
    credentialsRepository.save(row);
    Space space = new Space("Persönlich", null, true, person, organizationId);
    space.addMembership(SpaceMembership.ofUser(person, SpaceRole.ADMIN, organizationId));
    UUID spaceId = spaceRepository.save(space).getId();
    for (int i = 0; i < 3; i++) {
      chatService.createChat(spaceId, person, new ChatCreation().title("Fristen"));
    }

    new TwoInstanceRace(dataSource)
        .run(
            () -> cleanupService.runOnce(now),
            () -> cleanupService.runOnce(now),
            "SELECT 1 FROM local_credentials WHERE user_id = ? FOR UPDATE",
            person);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM chats WHERE author_id = ?", Integer.class, person))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE event_type = 'LOCKED_ACCOUNT_CHATS_DELETED'"
                    + " AND subject_ref IN (SELECT CAST(pseudonym_id AS text)"
                    + " FROM audit_actor_pseudonyms WHERE user_id = ?)",
                Long.class,
                person))
        .isEqualTo(1L);
  }
}
