package io.opaa.migration;

import static io.opaa.migration.LocalAccountSchemaSupport.LOCAL_ISSUER;
import static io.opaa.migration.LocalAccountSchemaSupport.count;
import static io.opaa.migration.LocalAccountSchemaSupport.insertUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies changelog 006 in isolation (#1532, ADR-0033 Entscheidung 3): the 1:1 table {@code
 * local_credentials} - its defaults, the enum CHECKs for the two reasons, the pairing of {@code
 * locked_at} with {@code locked_reason} and of {@code password_change_required} with its reason,
 * the mandatory creation reason, the single bootstrap row and the cascade from {@code users}. The
 * rule "a row exists exactly when the user's issuer is the local one" is an application invariant
 * (ADR-0033, Entscheidung 3), deliberately not a trigger, so it is not asserted here.
 */
class Migration006LocalCredentialsTest extends AbstractMigrationTest {

  private Connection connection;
  private UUID userId;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    applyChangelog(connection, "db/changelog/changes/006-create-local-credentials.yaml");
    userId = insertUser(connection, LOCAL_ISSUER, "konto@stadt.example");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void createsARowWithTheAdrDefaults() throws SQLException {
    insertCredentials(userId, "Sachbearbeitung Meldewesen");

    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT password_hash, password_change_required, password_change_reason,"
                    + " password_invalidated_before, locked_at, locked_reason,"
                    + " failed_login_attempts, lockout_until, expires_at, email_verified_at,"
                    + " is_bootstrap, created_at, updated_at, version FROM local_credentials")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString("password_hash")).isNull();
      assertThat(rows.getBoolean("password_change_required")).isFalse();
      assertThat(rows.getString("password_change_reason")).isNull();
      assertThat(rows.getTimestamp("password_invalidated_before")).isNull();
      assertThat(rows.getTimestamp("locked_at")).isNull();
      assertThat(rows.getString("locked_reason")).isNull();
      assertThat(rows.getInt("failed_login_attempts")).isZero();
      assertThat(rows.getTimestamp("lockout_until")).isNull();
      assertThat(rows.getTimestamp("expires_at")).isNull();
      assertThat(rows.getTimestamp("email_verified_at")).isNull();
      assertThat(rows.getBoolean("is_bootstrap")).isFalse();
      assertThat(rows.getTimestamp("created_at")).isNotNull();
      assertThat(rows.getTimestamp("updated_at")).isNotNull();
      assertThat(rows.getLong("version")).isZero();
      assertThat(rows.next()).isFalse();
    }
  }

  @Test
  void requiresANonBlankCreationReasonOfAtMost200Characters() {
    assertThatThrownBy(() -> insertCredentials(userId, null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("created_reason");
    assertThatThrownBy(() -> insertCredentials(userId, "   "))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_credentials_created_reason");
    assertThatThrownBy(() -> insertCredentials(userId, "x".repeat(201)))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("too long");
    assertThatCode(() -> insertCredentials(userId, "x".repeat(200))).doesNotThrowAnyException();
  }

  @Test
  void deletingTheUserDeletesTheCredentials() throws SQLException {
    insertCredentials(userId, "Test");

    LocalAccountSchemaSupport.deleteUser(connection, userId);

    assertThat(count(connection, "local_credentials", "user_id = '" + userId + "'")).isZero();
  }

  @Test
  void rejectsCredentialsForAnUnknownUser() {
    assertThatThrownBy(() -> insertCredentials(UUID.randomUUID(), "Test"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_local_credentials_user");
  }

  @Test
  void rejectsASecondRowForTheSameUser() throws SQLException {
    insertCredentials(userId, "Test");

    assertThatThrownBy(() -> insertCredentials(userId, "Test"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("local_credentials_pkey");
  }

  @Test
  void passwordChangeReasonIsAnEnumAndPairedWithTheFlag() throws SQLException {
    insertCredentials(userId, "Test");

    assertThatThrownBy(
            () -> update("password_change_required = true, password_change_reason = 'BECAUSE'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_credentials_password_change_reason");
    assertThatThrownBy(() -> update("password_change_required = true"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_credentials_password_change_consistent");
    assertThatThrownBy(() -> update("password_change_reason = 'INITIAL'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_credentials_password_change_consistent");
    for (String reason : new String[] {"INITIAL", "ADMIN_RESET", "SECURITY"}) {
      assertThatCode(
              () ->
                  update(
                      "password_change_required = true, password_change_reason = '" + reason + "'"))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void lockReasonIsAnEnumAndPairedWithTheLockTimestamp() throws SQLException {
    insertCredentials(userId, "Test");

    assertThatThrownBy(() -> update("locked_at = now(), locked_reason = 'MOOD'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_credentials_locked_reason");
    assertThatThrownBy(() -> update("locked_at = now()"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_credentials_lock_consistent");
    assertThatThrownBy(() -> update("locked_reason = 'ADMIN'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_credentials_lock_consistent");
    for (String reason : new String[] {"ADMIN", "FAILED_LOGINS", "INACTIVITY"}) {
      assertThatCode(() -> update("locked_at = now(), locked_reason = '" + reason + "'"))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void rejectsANegativeFailedLoginCounter() throws SQLException {
    insertCredentials(userId, "Test");

    assertThatThrownBy(() -> update("failed_login_attempts = -1"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_credentials_failed_login_attempts");
  }

  @Test
  void allowsAtMostOneBootstrapRow() throws SQLException {
    insertCredentials(userId, "Notanker-Konto der Systemverwaltung");
    update("is_bootstrap = true");
    UUID second = insertUser(connection, LOCAL_ISSUER, "zweites@stadt.example");
    insertCredentials(second, "Test");

    assertThatThrownBy(
            () ->
                connection
                    .createStatement()
                    .execute(
                        "UPDATE local_credentials SET is_bootstrap = true WHERE user_id = '"
                            + second
                            + "'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ux_local_credentials_single_bootstrap");
  }

  private void insertCredentials(UUID user, String createdReason) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO local_credentials (user_id, created_reason) VALUES (?, ?)")) {
      statement.setObject(1, user);
      statement.setString(2, createdReason);
      statement.executeUpdate();
    }
  }

  private void update(String setClause) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "UPDATE local_credentials SET " + setClause + " WHERE user_id = '" + userId + "'");
    }
  }
}
