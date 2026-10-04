package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The profile requirement on {@code connector_type_policies}: applied to an existing installation,
 * a lock row already there survives without requirement, and the requirement is set only together
 * with what happens to the libraries with their own address.
 */
class ConnectorProfileRequirementMigrationTest extends AbstractBaselineTest {

  private static final String FILE = "db/changelog/connections/2026-10-04-profile-requirement.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void anExistingLockRowSurvivesWithoutRequirement() throws Exception {
    execute(
        "INSERT INTO connector_type_policies (source_type, locked_at, updated_at)"
            + " VALUES ('RSS_FEED', now(), now())");

    applyChangelog(connection, FILE);

    assertThat(
            countWhere(
                "connector_type_policies",
                "source_type = 'RSS_FEED' AND locked_at IS NOT NULL"
                    + " AND profile_required_at IS NULL AND own_address_stock IS NULL"))
        .isEqualTo(1);
  }

  @Test
  void theRequirementAndTheStockAreSetTogetherAndTheStockIsKnown() throws Exception {
    applyChangelog(connection, FILE);

    assertRejected(
        insert("'S3'", "now()", "NULL"), "chk_connector_type_policies_profile_requirement");
    assertRejected(
        insert("'S3'", "NULL", "'RUNS'"), "chk_connector_type_policies_profile_requirement");
    assertRejected(
        insert("'S3'", "now()", "'PAUSED'"), "chk_connector_type_policies_own_address_stock");
    execute(insert("'S3'", "now()", "'RUNS'"));
    execute(insert("'CONFLUENCE'", "now()", "'LOCKED'"));
    execute(insert("'NEXTCLOUD'", "NULL", "NULL"));

    assertThat(countWhere("connector_type_policies", "profile_required_at IS NOT NULL"))
        .isEqualTo(2);
  }

  private static String insert(String type, String requiredAt, String stock) {
    return "INSERT INTO connector_type_policies (source_type, updated_at, profile_required_at,"
        + " own_address_stock) VALUES ("
        + type
        + ", now(), "
        + requiredAt
        + ", "
        + stock
        + ")";
  }
}
