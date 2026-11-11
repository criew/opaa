package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The right to read the agent directory joins the closed capability lists of grants and their
 * history on an existing installation, without touching the grants it already holds.
 */
class AgentDirectoryCapabilityTest extends AbstractBaselineTest {

  private static final String SCRIPT =
      "db/migration/rights/V2026.11.11.10__capability_view_agent_directory.sql";

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.except(SCRIPT);
  }

  @Test
  void theDirectoryRightIsAcceptedAfterTheMigrationAndExistingGrantsStay() throws Exception {
    UUID user = insertUser();
    execute(grant("CREATE_AGENT", user));
    assertRejected(grant("VIEW_AGENT_DIRECTORY", user), "chk_capability_grants_capability");

    applyMigrations(connection, SCRIPT);

    execute(grant("VIEW_AGENT_DIRECTORY", user));
    execute(history("VIEW_AGENT_DIRECTORY", user));
    assertThat(countWhere("capability_grants", "subject_user_id = '" + user + "'")).isEqualTo(2);
    assertRejected(grant("READ_EVERYTHING", user), "chk_capability_grants_capability");
    assertRejected(history("READ_EVERYTHING", user), "chk_capability_grant_history_capability");
  }

  private static String grant(String capability, UUID user) {
    return "INSERT INTO capability_grants (id, organization_id, capability, subject_type,"
        + " subject_user_id) VALUES (gen_random_uuid(), '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + capability
        + "', 'USER', '"
        + user
        + "')";
  }

  private static String history(String capability, UUID user) {
    return "INSERT INTO capability_grant_history (id, organization_id, capability, subject_type,"
        + " subject_user_id, cause, valid_from) VALUES (gen_random_uuid(), '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + capability
        + "', 'USER', '"
        + user
        + "', 'GRANTED', now())";
  }
}
