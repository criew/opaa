package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies Liquibase changelog 066 in isolation against a database built from {@code
 * test-master-through-050.yaml} plus changelog 052 (notifications creation) - the same base {@code
 * Migration052CreateNotificationsTest} uses.
 *
 * <p>Proves #862's acceptance criteria against a real database: {@code chk_notifications_type} no
 * longer exists after 066 runs, a value outside the old closed list is now writable, and the
 * pre-existing value is still accepted. Unlike {@code audit_log}, {@code notifications} is not
 * ownership-restricted - there is no separate grant to re-prove here.
 */
class Migration066DropNotificationsTypeCheckTest extends AbstractMigrationTest {

  private static final String ORGANIZATION_A = "00000000-0000-0000-0000-000000000001";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-050.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    insertOrganization(ORGANIZATION_A);
    applyChangelog(connection, "db/changelog/changes/052-create-notifications.yaml");
    applyChangelog(connection, "db/changelog/changes/066-drop-notifications-type-check.yaml");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void theCheckConstraintNoLongerExists() throws Exception {
    assertThat(constraintExists("chk_notifications_type")).isFalse();
  }

  @Test
  void aValueOutsideTheFormerClosedListIsNowWritable() throws SQLException {
    UUID recipient = insertUser(ORGANIZATION_A);

    insertNotificationWithType(recipient, ORGANIZATION_A, "SOMETHING_ELSE");

    assertThat(countNotifications(recipient)).isEqualTo(1);
  }

  @Test
  void thePreExistingTypeIsStillAccepted() throws SQLException {
    UUID recipient = insertUser(ORGANIZATION_A);

    insertNotificationWithType(recipient, ORGANIZATION_A, "LIBRARY_ASSOCIATED_TO_MIXED_SPACE");

    assertThat(countNotifications(recipient)).isEqualTo(1);
  }

  private boolean constraintExists(String constraintName) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM pg_constraint WHERE conname = '" + constraintName + "'")) {
      result.next();
      return result.getInt(1) > 0;
    }
  }

  private void insertOrganization(String id) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO organizations (id, name, created_at) VALUES ('"
              + id
              + "', 'Org "
              + id
              + "', now()) ON CONFLICT (id) DO NOTHING");
    }
  }

  private UUID insertUser(String organizationId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO users (id, subject, issuer, system_role, organization_id, created_at) "
              + "VALUES ('"
              + id
              + "', '"
              + id
              + "', 'test-issuer', 'USER', '"
              + organizationId
              + "', now())");
    }
    return id;
  }

  private void insertNotificationWithType(UUID recipientId, String organizationId, String type)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO notifications"
              + " (id, organization_id, recipient_user_id, type, title, created_at) "
              + "VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + organizationId
              + "', '"
              + recipientId
              + "', '"
              + type
              + "', 'Titel', now())");
    }
  }

  private int countNotifications(UUID recipientId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM notifications WHERE recipient_user_id = '"
                    + recipientId
                    + "'")) {
      result.next();
      return result.getInt(1);
    }
  }
}
