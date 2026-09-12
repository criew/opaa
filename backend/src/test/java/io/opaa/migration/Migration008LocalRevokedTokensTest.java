package io.opaa.migration;

import static io.opaa.migration.LocalAccountSchemaSupport.LOCAL_ISSUER;
import static io.opaa.migration.LocalAccountSchemaSupport.count;
import static io.opaa.migration.LocalAccountSchemaSupport.indexDefinition;
import static io.opaa.migration.LocalAccountSchemaSupport.insertUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies changelog 008 in isolation (#1532, ADR-0033 Entscheidung 7): the {@code jti} denylist -
 * the hash is the key, {@code revoked_at} defaults to now, rows cascade with their user and the
 * cleanup index on {@code expires_at} exists.
 */
class Migration008LocalRevokedTokensTest extends AbstractMigrationTest {

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
    applyChangelog(connection, "db/changelog/changes/008-create-local-revoked-tokens.yaml");
    userId = insertUser(connection, LOCAL_ISSUER, "konto@stadt.example");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void rejectsADuplicateJtiHash() throws SQLException {
    insertRevoked("a".repeat(64));

    assertThatThrownBy(() -> insertRevoked("a".repeat(64)))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("local_revoked_tokens_pkey");
  }

  @Test
  void defaultsRevokedAtToNow() throws SQLException {
    insertRevoked("b".repeat(64));

    assertThat(count(connection, "local_revoked_tokens", "revoked_at IS NOT NULL")).isEqualTo(1);
  }

  @Test
  void deletingTheUserDeletesItsDenylistRows() throws SQLException {
    insertRevoked("c".repeat(64));

    LocalAccountSchemaSupport.deleteUser(connection, userId);

    assertThat(count(connection, "local_revoked_tokens", "user_id = '" + userId + "'")).isZero();
  }

  @Test
  void rejectsARowForAnUnknownUser() {
    assertThatThrownBy(() -> insertRevoked("d".repeat(64), UUID.randomUUID()))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_local_revoked_tokens_user");
  }

  @Test
  void indexesExpiryForTheCleanupRun() throws SQLException {
    assertThat(indexDefinition(connection, "idx_local_revoked_tokens_expires_at"))
        .isNotNull()
        .contains("(expires_at)");
  }

  private void insertRevoked(String jtiHash) throws SQLException {
    insertRevoked(jtiHash, userId);
  }

  private void insertRevoked(String jtiHash, UUID user) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO local_revoked_tokens (jti_hash, user_id, expires_at)"
                + " VALUES (?, ?, now() + interval '15 minutes')")) {
      statement.setString(1, jtiHash);
      statement.setObject(2, user);
      statement.executeUpdate();
    }
  }
}
