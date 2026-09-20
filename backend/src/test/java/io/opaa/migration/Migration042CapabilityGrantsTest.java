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
 * Applies changelog 042 in isolation (#1813, ADR-0036 Entscheidung 5): the table of the
 * installation-wide capabilities, its three-valued subject and the uniqueness per (organization,
 * capability, subject) the three partial unique indexes carry.
 */
class Migration042CapabilityGrantsTest extends AbstractMigrationTest {

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
    applyChangelog(connection, "db/changelog/changes/042-create-capability-grants.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void createsTheTableWithoutSeedingIt() throws SQLException {
    assertThat(count("SELECT count(*) FROM capability_grants"))
        .as("042 creates the table only - the delivered state is changeset 051")
        .isZero();
  }

  @Test
  void acceptsOnlyTheFourKnownCapabilities() {
    assertThatCode(() -> insertForAllAccounts("CREATE_SPACE")).doesNotThrowAnyException();
    assertThatCode(() -> insertForAllAccounts("CREATE_LIBRARY")).doesNotThrowAnyException();
    assertThatCode(() -> insertForAllAccounts("CREATE_CONNECTOR_LIBRARY"))
        .doesNotThrowAnyException();
    assertThatCode(() -> insertForAllAccounts("CREATE_INTERNAL_GROUP")).doesNotThrowAnyException();
    assertThatThrownBy(() -> insertForAllAccounts("CREATE_ANYTHING"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_capability_grants_capability");
  }

  @Test
  void bindsEachSubjectTypeToItsOwnColumn() {
    assertThatThrownBy(
            () -> insert("'CREATE_SPACE', 'ALL_ACCOUNTS', '" + ORGANIZATION + "'::uuid, NULL"))
        .as("ALL_ACCOUNTS names no subject, so neither column may be filled")
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_capability_grants_subject");
    assertThatThrownBy(() -> insert("'CREATE_SPACE', 'USER', NULL, NULL"))
        .as("a USER subject without a user id")
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_capability_grants_subject");
    assertThatThrownBy(() -> insert("'CREATE_SPACE', 'PLANET', NULL, NULL"))
        .as("an unknown subject type fits none of the three branches and is refused by one of them")
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_capability_grants_subject");
  }

  /**
   * The reason the uniqueness is three partial indexes and not one plain unique constraint: NULL is
   * never equal to NULL in a unique index, so a shared index over the subject columns would let any
   * number of ALL_ACCOUNTS rows of the same capability through.
   */
  @Test
  void acceptsOneAllAccountsRowPerCapabilityAndOrganization() throws SQLException {
    insertForAllAccounts("CREATE_SPACE");
    assertThatThrownBy(() -> insertForAllAccounts("CREATE_SPACE"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_capability_grants_all_accounts");
    assertThatCode(() -> insertForAllAccounts("CREATE_LIBRARY")).doesNotThrowAnyException();
  }

  @Test
  void keepsThePersonColumnsRestrictingAndTheOrganizationCascading() throws SQLException {
    assertThat(deleteRule("fk_capability_grants_subject_user_organization")).isEqualTo("r");
    assertThat(deleteRule("fk_capability_grants_granted_by_user_organization")).isEqualTo("r");
    assertThat(deleteRule("fk_capability_grants_subject_group_organization")).isEqualTo("r");
    assertThat(deleteRule("fk_capability_grants_organization"))
        .as("the rows are configuration of the tenant and are pointless without it")
        .isEqualTo("c");
  }

  private void insertForAllAccounts(String capability) throws SQLException {
    insert("'" + capability + "', 'ALL_ACCOUNTS', NULL, NULL");
  }

  private void insert(String capabilitySubjectAndColumns) throws SQLException {
    execute(
        "INSERT INTO capability_grants"
            + " (id, organization_id, capability, subject_type, subject_user_id, subject_group_id,"
            + "  created_at)"
            + " VALUES (gen_random_uuid(), '"
            + ORGANIZATION
            + "'::uuid, "
            + capabilitySubjectAndColumns
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

  private long count(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getLong(1);
    }
  }

  private void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
