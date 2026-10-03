package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The removal of the growth signal's stored figure (#2134): {@code member_count_at_grant} on {@code
 * asset_grants}, {@code space_memberships} and {@code space_membership_history}, with the checks
 * that went with it. Applied to an existing installation, i.e. the master without these two files;
 * the rows already there survive.
 */
class DropMemberCountAtGrantMigrationTest extends AbstractBaselineTest {

  private static final String RIGHTS_FILE =
      "db/changelog/rights/2026-10-03-drop-grant-member-count.yaml";
  private static final String WORKSPACE_FILE =
      "db/changelog/workspace/2026-10-03-drop-membership-member-count.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(RIGHTS_FILE, WORKSPACE_FILE);
  }

  @Test
  void theColumnsAndTheirChecksAreGoneAndExistingRowsSurvive() throws Exception {
    UUID owner = insertUser();
    UUID asset = insertAsset("KNOWLEDGE_LIBRARY", owner);
    UUID group = insertInternalGroup();
    UUID space = insertSpace(owner);
    execute(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_group_id, role, member_count_at_grant) VALUES (gen_random_uuid(),"
            + " 'KNOWLEDGE_LIBRARY', '"
            + asset
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'GROUP', '"
            + group
            + "', 'VIEWER', 23)");
    execute(
        "INSERT INTO space_memberships (id, space_id, organization_id, role, subject_type,"
            + " group_id, member_count_at_grant) VALUES (gen_random_uuid(), '"
            + space
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'MEMBER', 'GROUP', '"
            + group
            + "', 23)");
    execute(
        "INSERT INTO space_membership_history (id, space_id, organization_id, subject_type,"
            + " subject_group_id, role, member_count_at_grant, cause, valid_from) VALUES"
            + " (gen_random_uuid(), '"
            + space
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'GROUP', '"
            + group
            + "', 'MEMBER', 23, 'ADDED', now())");

    applyChangelog(connection, RIGHTS_FILE);
    applyChangelog(connection, WORKSPACE_FILE);

    assertThat(columnExists("asset_grants")).isFalse();
    assertThat(columnExists("space_memberships")).isFalse();
    assertThat(columnExists("space_membership_history")).isFalse();
    assertThat(
            countWhere(
                "pg_constraint",
                "conname IN ('chk_asset_grants_member_count',"
                    + " 'chk_space_memberships_member_count')"))
        .isZero();
    String ofGroup = "subject_group_id = '" + group + "'";
    assertThat(countWhere("asset_grants", ofGroup)).isEqualTo(1);
    assertThat(countWhere("space_memberships", "group_id = '" + group + "'")).isEqualTo(1);
    assertThat(countWhere("space_membership_history", ofGroup)).isEqualTo(1);
  }

  private boolean columnExists(String table) throws SQLException {
    return countWhere(
            "information_schema.columns",
            "table_schema = current_schema() AND table_name = '"
                + table
                + "' AND column_name = 'member_count_at_grant'")
        > 0;
  }
}
