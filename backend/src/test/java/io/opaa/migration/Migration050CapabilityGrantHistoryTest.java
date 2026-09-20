package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies changelog 050 in isolation (#1813, ADR-0036 Entscheidung 8): the interval table of the
 * capability history, its delete rules per column kind (subject RESTRICT, actor SET NULL, group and
 * organization without a key at all) and the partial unique indexes that allow exactly one open
 * interval per subject.
 */
class Migration050CapabilityGrantHistoryTest extends AbstractMigrationTest {

  private static final UUID ORGANIZATION = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    applyChangelog(connection, "db/changelog/changes/050-create-capability-grant-history.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void allowsOneOpenIntervalPerSubjectAndAnyNumberOfClosedOnes() throws SQLException {
    insertInterval("'CREATE_SPACE'", "'DELIVERED'", "NULL");
    assertThatThrownBy(() -> insertInterval("'CREATE_SPACE'", "'GRANTED'", "NULL"))
        .as("two open intervals for the same subject would be an interleaved chain")
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_capability_grant_history_open_all_accounts");
    assertThatCode(() -> insertInterval("'CREATE_SPACE'", "'REVOKED'", "now()"))
        .as("a closed interval and the zero-length revocation marker are never the open one")
        .doesNotThrowAnyException();
  }

  @Test
  void refusesAnIntervalThatEndsBeforeItBegins() {
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO capability_grant_history"
                        + " (id, organization_id, capability, subject_type, cause, valid_from,"
                        + "  valid_to, created_at)"
                        + " VALUES (gen_random_uuid(), '"
                        + ORGANIZATION
                        + "'::uuid, 'CREATE_SPACE', 'ALL_ACCOUNTS', 'GRANTED',"
                        + " now(), now() - interval '1 day', now())"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_capability_grant_history_interval");
  }

  @Test
  void acceptsOnlyTheThreeKnownCauses() {
    assertThatCode(() -> insertInterval("'CREATE_LIBRARY'", "'DELIVERED'", "NULL"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> insertInterval("'CREATE_SPACE'", "'BACKFILL'", "NULL"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_capability_grant_history_cause");
  }

  /**
   * ADR-0016's split, applied to this table: the person the interval is about keeps the account
   * alive, the actor is merely detached, and the object columns carry no key at all so a history
   * row outlives the group and the organization it speaks about.
   */
  @Test
  void keepsTheSubjectRestrictingTheActorDetachableAndTheObjectColumnsFree() throws SQLException {
    assertThat(deleteRule("fk_capability_grant_history_subject_user_organization")).isEqualTo("r");
    assertThat(deleteRule("fk_capability_grant_history_actor_user_organization")).isEqualTo("n");
    assertThat(foreignKeysOfColumn("subject_group_id"))
        .as("a deleted group must not take its history with it")
        .isZero();
    assertThat(foreignKeysReferencing("organizations"))
        .as(
            "mirrors group_membership_history: organization_id only ever travels as the tenant half"
                + " of a composite key into users, never as a key of its own")
        .isZero();
  }

  private void insertInterval(String capability, String cause, String validTo) throws SQLException {
    execute(
        "INSERT INTO capability_grant_history"
            + " (id, organization_id, capability, subject_type, cause, valid_from, valid_to,"
            + "  created_at)"
            + " VALUES (gen_random_uuid(), '"
            + ORGANIZATION
            + "'::uuid, "
            + capability
            + ", 'ALL_ACCOUNTS', "
            + cause
            + ", now(), "
            + validTo
            + ", now())");
  }

  private String deleteRule(String constraintName) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rule =
            statement.executeQuery(
                "SELECT confdeltype FROM pg_constraint WHERE conname = '" + constraintName + "'")) {
      assertThat(rule.next()).as(constraintName + " is missing").isTrue();
      return rule.getString(1);
    }
  }

  private long foreignKeysOfColumn(String column) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet keys =
            statement.executeQuery(
                "SELECT count(*) FROM pg_constraint fk"
                    + " JOIN LATERAL unnest(fk.conkey) AS key_column(attnum) ON true"
                    + " JOIN pg_attribute column_of_key"
                    + "   ON column_of_key.attrelid = fk.conrelid"
                    + "  AND column_of_key.attnum = key_column.attnum"
                    + " WHERE fk.contype = 'f'"
                    + "   AND fk.conrelid = 'capability_grant_history'::regclass"
                    + "   AND column_of_key.attname = '"
                    + column
                    + "'")) {
      assertThat(keys.next()).isTrue();
      return keys.getLong(1);
    }
  }

  private long foreignKeysReferencing(String referencedTable) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet keys =
            statement.executeQuery(
                "SELECT count(*) FROM pg_constraint"
                    + " WHERE contype = 'f'"
                    + "   AND conrelid = 'capability_grant_history'::regclass"
                    + "   AND confrelid = '"
                    + referencedTable
                    + "'::regclass")) {
      assertThat(keys.next()).isTrue();
      return keys.getLong(1);
    }
  }

  private void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
