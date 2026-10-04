package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The last use of a connected account and the lifecycle state of a person, applied to an existing
 * installation: an existing account keeps its row without a recorded use, and a person's state goes
 * with the account and is never deactivated and resting at once.
 */
class ConnectionUsageAndPersonStatesMigrationTest extends AbstractBaselineTest {

  private static final String FILE =
      "db/changelog/connections/2026-10-04-usage-and-person-states.yaml";

  private UUID person;
  private UUID account;

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @BeforeEach
  void anInstallationWithAConnectedAccount() throws Exception {
    person = insertUser();
    UUID profile = UUID.randomUUID();
    execute(
        "INSERT INTO connection_profiles (id, name, source_type, server_url, auth_method,"
            + " ownership, source_insecure_ssl, created_at, updated_at, version) VALUES ('"
            + profile
            + "', 'Zugang "
            + profile
            + "', 'PERSON_PROBE', 'https://person.example.org', 'PERSONAL_SECRET', 'PERSON',"
            + " false, now(), now(), 0)");
    account = UUID.randomUUID();
    execute(
        "INSERT INTO connected_accounts (id, organization_id, user_id, profile_id, state,"
            + " connected_at, version) VALUES ('"
            + account
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', '"
            + person
            + "', '"
            + profile
            + "', 'CONNECTED', now(), 0)");

    applyChangelog(connection, FILE);
  }

  @Test
  void anExistingAccountStaysWithoutARecordedUse() throws Exception {
    assertThat(countWhere("connected_accounts", "id = '" + account + "' AND last_used_at IS NULL"))
        .isEqualTo(1);
    execute("UPDATE connected_accounts SET last_used_at = now() WHERE id = '" + account + "'");
  }

  @Test
  void aPersonsStateGoesWithTheAccountAndHoldsOneStateAtMost() throws Exception {
    UUID leaving = insertUser();
    execute(state(leaving, "now()", "NULL"));
    assertRejected(state(person, "now()", "now()"), "chk_connection_person_states_one_state");
    execute(state(person, "NULL", "now()"));

    execute("DELETE FROM users WHERE id = '" + leaving + "'");

    assertThat(countWhere("connection_person_states", "user_id = '" + leaving + "'")).isZero();
    assertThat(countWhere("connection_person_states", "user_id = '" + person + "'")).isEqualTo(1);
  }

  private static String state(UUID user, String deactivatedSince, String dormantSince) {
    return "INSERT INTO connection_person_states (user_id, deactivated_since, dormant_since,"
        + " checked_at) VALUES ('"
        + user
        + "', "
        + deactivatedSince
        + ", "
        + dormantSince
        + ", now())";
  }
}
