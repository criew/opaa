package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The capabilities {@code CREATE_TEMPLATE} and {@code CREATE_AGENT} on an existing installation:
 * grants written before stay, both new values are accepted in grants and their history, an unknown
 * one is still refused, and neither is delivered to anybody.
 */
class CapabilityCreateTemplateMigrationTest extends AbstractBaselineTest {

  private static final String SCRIPT =
      "db/migration/rights/V2026.11.10.10__capability_create_template.sql";

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.except(SCRIPT);
  }

  @Test
  void existingGrantsStayAndTheNewCapabilitiesAreAcceptedButNotDelivered() throws SQLException {
    long before = countWhere("capability_grants", "true");

    applyMigrations(connection, SCRIPT);

    assertThat(countWhere("capability_grants", "true")).isEqualTo(before);
    assertThat(countWhere("capability_grants", "capability IN ('CREATE_TEMPLATE', 'CREATE_AGENT')"))
        .isZero();
    for (String capability : List.of("CREATE_TEMPLATE", "CREATE_AGENT")) {
      execute(grantSql(capability));
      execute(historySql(capability));
    }
    assertRejected(grantSql("CREATE_UNKNOWN"), "chk_capability_grants_capability");
    assertRejected(historySql("CREATE_UNKNOWN"), "chk_capability_grant_history_capability");
  }

  private static String grantSql(String capability) {
    return "INSERT INTO capability_grants (id, organization_id, capability, subject_type)"
        + " VALUES ('"
        + UUID.randomUUID()
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + capability
        + "', 'ALL_ACCOUNTS')";
  }

  private static String historySql(String capability) {
    return "INSERT INTO capability_grant_history (id, organization_id, capability, subject_type,"
        + " cause, valid_from, created_at) VALUES ('"
        + UUID.randomUUID()
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + capability
        + "', 'ALL_ACCOUNTS', 'DELIVERED', now(), now())";
  }
}
