package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.account.LocalUserService;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.organization.Organization;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The state intervals of an account go with it only when nothing else in the history names it: an
 * account with a grant interval is still refused deletion, so its locks stay to cut that grant in
 * the Stichtagsauskunft (ADR-0016, Entscheidung 2) - a deleted account can never read as a reader
 * without its locks.
 */
@OpaaLocalAuthMockMvcTest
class AccountStateDeletionIntegrationTest {

  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private LocalUserService localUsers;
  @Autowired private JdbcTemplate jdbc;

  private LocalAccountFixtures fixtures;
  private CurrentUser actor;
  private UUID grantInterval;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
    LocalAccount admin = fixtures.activeAdmin("verwaltung-" + UUID.randomUUID() + "@stadt.example");
    actor =
        CurrentUser.of(admin.id(), Organization.DEFAULT_ID, SystemRole.SYSTEM_ADMIN, "Verwaltung");
  }

  @AfterEach
  void tearDown() {
    if (grantInterval != null) {
      jdbc.update("DELETE FROM asset_grant_history WHERE id = ?", grantInterval);
    }
    fixtures.cleanUp();
  }

  @Test
  void anAccountNamedInAnotherHistoryKeepsItsLocksBecauseItCannotBeDeleted() {
    LocalAccount reader = fixtures.activeUser("leser-" + UUID.randomUUID() + "@stadt.example");
    grantInterval = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO asset_grant_history (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_user_id, role, cause, valid_from, created_at) VALUES (?,"
            + " 'KNOWLEDGE_LIBRARY', ?, ?, 'USER', ?, 'VIEWER', 'GRANTED',"
            + " now() - interval '1 day', now())",
        grantInterval,
        UUID.randomUUID(),
        Organization.DEFAULT_ID,
        reader.id());
    localUsers.lock(actor, reader.id());

    assertThatThrownBy(() -> localUsers.delete(actor, reader.id()))
        .isInstanceOf(ConflictException.class);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM account_state_history WHERE user_id = ?"
                    + " AND state = 'LOCKED' AND valid_to IS NULL",
                Long.class,
                reader.id()))
        .isEqualTo(1L);
  }
}
