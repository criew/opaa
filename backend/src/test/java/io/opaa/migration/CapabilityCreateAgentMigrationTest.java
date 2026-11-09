package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The capabilities {@code CREATE_AGENT} and {@code CREATE_TEMPLATE} on an existing installation:
 * grants written before stay, no organization receives one of the new rights, both are grantable
 * from now on and an unknown capability is still refused in both tables.
 */
class CapabilityCreateAgentMigrationTest extends AbstractBaselineTest {

  private static final String SCRIPT =
      "db/migration/rights/V2026.11.10.22__capability_create_agent.sql";

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.except(SCRIPT);
  }

  @Test
  void existingGrantsStayAndTheNewRightsAreGrantableButNotDelivered() throws SQLException {
    long before = countWhere("capability_grants", "true");
    assertRejected(grantSql("CREATE_AGENT"), "chk_capability_grants_capability");

    applyMigrations(connection, SCRIPT);

    assertThat(countWhere("capability_grants", "true")).isEqualTo(before);
    assertThat(countWhere("capability_grants", "capability IN ('CREATE_AGENT', 'CREATE_TEMPLATE')"))
        .isZero();
    for (String capability : List.of("CREATE_AGENT", "CREATE_TEMPLATE")) {
      execute(grantSql(capability));
      execute(historySql(capability));
    }
    assertRejected(grantSql("CREATE_UNKNOWN"), "chk_capability_grants_capability");
    assertRejected(historySql("CREATE_UNKNOWN"), "chk_capability_grant_history_capability");
  }

  private static String grantSql(String capability) {
    return "INSERT INTO capability_grants (id, organization_id, capability, subject_type)"
        + " VALUES (gen_random_uuid(), '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + capability
        + "', 'ALL_ACCOUNTS')";
  }

  private static String historySql(String capability) {
    return "INSERT INTO capability_grant_history (id, organization_id, capability, subject_type,"
        + " cause, valid_from) VALUES (gen_random_uuid(), '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + capability
        + "', 'ALL_ACCOUNTS', 'GRANTED', now())";
  }
}
