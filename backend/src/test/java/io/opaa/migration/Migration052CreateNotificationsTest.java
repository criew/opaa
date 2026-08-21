package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Applies Liquibase changelog 052 in isolation against a database built from {@code
 * test-master-through-050.yaml} - {@code uk_users_id_organization} (migration 047) is required for
 * {@code fk_notifications_recipient_organization}, so this cannot stop at 046 like most other
 * migration tests.
 *
 * <p>#203: notifications is a minimal, persisted in-app notification (see {@code
 * io.opaa.notification.Notification}'s Javadoc). {@link #anUnrecognisedTypeIsRejected()} proves the
 * closed vocabulary {@code chk_notifications_type} enforces, mirroring {@code
 * chk_audit_log_event_type}.
 */
class Migration052CreateNotificationsTest extends AbstractMigrationTest {

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
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void aNotificationCanBeCreatedAndRead() throws SQLException {
    UUID recipient = insertUser(ORGANIZATION_A);

    insertNotification(recipient, ORGANIZATION_A);

    assertThat(countNotifications(recipient)).isEqualTo(1);
  }

  @Test
  void anUnrecognisedTypeIsRejected() throws SQLException {
    UUID recipient = insertUser(ORGANIZATION_A);

    assertThatThrownBy(
            () -> insertNotificationWithType(recipient, ORGANIZATION_A, "SOMETHING_ELSE"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_notifications_type");
  }

  @Test
  void deletingTheRecipientDeletesTheirNotifications() throws SQLException {
    UUID recipient = insertUser(ORGANIZATION_A);
    insertNotification(recipient, ORGANIZATION_A);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM users WHERE id = '" + recipient + "'");
    }

    assertThat(countNotifications(recipient)).isZero();
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

  private void insertNotification(UUID recipientId, String organizationId) throws SQLException {
    insertNotificationWithType(recipientId, organizationId, "LIBRARY_ASSOCIATED_TO_MIXED_SPACE");
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
