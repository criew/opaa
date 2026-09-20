package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies changelogs 042, 050 and 051 in order (#1813, ADR-0036 Entscheidung 5): the delivered
 * state "Alle Konten dürfen" - three rows per organization and none for CREATE_INTERNAL_GROUP -
 * plus the trigger that carries the same state to every organization created later.
 */
class Migration051DeliveredCapabilityGrantsTest extends AbstractMigrationTest {

  private static final List<String> DELIVERED =
      List.of("CREATE_CONNECTOR_LIBRARY", "CREATE_LIBRARY", "CREATE_SPACE");

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
    applyChangelog(connection, "db/changelog/changes/050-create-capability-grant-history.yaml");
    applyChangelog(connection, "db/changelog/changes/051-seed-delivered-capability-grants.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void grantsThreeCapabilitiesToAllAccountsOfEveryOrganization() throws SQLException {
    for (UUID organizationId : organizationIds()) {
      assertThat(deliveredCapabilitiesOf(organizationId))
          .as(
              "after the migration every account creates spaces and libraries exactly as before,"
                  + " and internal groups stay with the system administration")
          .containsExactlyElementsOf(DELIVERED);
    }
  }

  @Test
  void opensOneHistoryIntervalPerDeliveredGrant() throws SQLException {
    assertThat(
            queryForBoolean(
                "SELECT NOT EXISTS ("
                    + "  SELECT 1 FROM capability_grants g"
                    + "   WHERE NOT EXISTS ("
                    + "     SELECT 1 FROM capability_grant_history h"
                    + "      WHERE h.organization_id = g.organization_id"
                    + "        AND h.capability = g.capability"
                    + "        AND h.subject_type = g.subject_type"
                    + "        AND h.valid_to IS NULL"
                    + "        AND h.valid_from = g.created_at))"))
        .as(
            "without an interval the delivered state - the longest one - would be the unrecorded one")
        .isTrue();
    assertThat(
            queryForBoolean(
                "SELECT bool_and(cause = 'DELIVERED' AND actor_user_id IS NULL)"
                    + " FROM capability_grant_history"))
        .as("nobody decided the delivered state, so it carries no actor and its own cause")
        .isTrue();
  }

  @Test
  void carriesTheDeliveredStateToAnOrganizationCreatedLater() throws SQLException {
    UUID later = UUID.randomUUID();
    execute(
        "INSERT INTO organizations (id, name, created_at) VALUES ('"
            + later
            + "'::uuid, 'Später', now())");

    assertThat(deliveredCapabilitiesOf(later))
        .as("there is no application path creating an organization - the trigger is the guarantee")
        .containsExactlyElementsOf(DELIVERED);
    assertThat(
            count(
                "SELECT count(*) FROM capability_grant_history WHERE organization_id = '"
                    + later
                    + "'::uuid AND cause = 'DELIVERED' AND valid_to IS NULL"))
        .isEqualTo(3);
  }

  private List<String> deliveredCapabilitiesOf(UUID organizationId) throws SQLException {
    List<String> capabilities = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT capability FROM capability_grants WHERE organization_id = '"
                    + organizationId
                    + "'::uuid AND subject_type = 'ALL_ACCOUNTS' ORDER BY capability")) {
      while (rows.next()) {
        capabilities.add(rows.getString(1));
      }
    }
    return capabilities;
  }

  private List<UUID> organizationIds() throws SQLException {
    List<UUID> ids = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT id FROM organizations")) {
      while (rows.next()) {
        ids.add(rows.getObject(1, UUID.class));
      }
    }
    assertThat(ids).as("the baseline seeds exactly one organization").isNotEmpty();
    return ids;
  }

  private boolean queryForBoolean(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getBoolean(1);
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
