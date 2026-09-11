package io.opaa.migration;

import static io.opaa.migration.LocalAccountSchemaSupport.LOCAL_ISSUER;
import static io.opaa.migration.LocalAccountSchemaSupport.count;
import static io.opaa.migration.LocalAccountSchemaSupport.indexDefinition;
import static io.opaa.migration.LocalAccountSchemaSupport.insertUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies changelog 006 in isolation (#1532, ADR-0033 Entscheidung 7): refresh-token families with
 * an absolute end ({@code family_expires_at}), a unique HMAC lookup hash, the enum of revocation
 * reasons paired with {@code revoked_at}, the rotation self-reference that is cleared when the
 * successor disappears, the cascade from {@code users} and the two partial indexes over active
 * rows.
 */
class Migration006LocalRefreshTokensTest extends AbstractMigrationTest {

  static final List<String> REVOCATION_REASONS =
      List.of(
          "ROTATED",
          "REUSE_DETECTED",
          "LOGOUT",
          "PASSWORD_CHANGED",
          "ADMIN_RESET",
          "ACCOUNT_LOCKED",
          "HANDED_OVER",
          "ADMIN");

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
    applyChangelog(connection, "db/changelog/changes/006-create-local-refresh-tokens.yaml");
    userId = insertUser(connection, LOCAL_ISSUER, "konto@stadt.example");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void rejectsADuplicateLookupHash() throws SQLException {
    insertToken(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64));

    assertThatThrownBy(() -> insertToken(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64)))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ux_local_refresh_tokens_lookup_hash");
  }

  @Test
  void revocationReasonIsAnEnumPairedWithRevokedAt() throws SQLException {
    UUID id = UUID.randomUUID();
    insertToken(id, UUID.randomUUID(), "b".repeat(64));

    assertThatThrownBy(() -> update(id, "revoked_at = now(), revocation_reason = 'BECAUSE'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_refresh_tokens_revocation_reason");
    assertThatThrownBy(() -> update(id, "revoked_at = now()"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_refresh_tokens_revocation_consistent");
    assertThatThrownBy(() -> update(id, "revocation_reason = 'LOGOUT'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_refresh_tokens_revocation_consistent");
    for (String reason : REVOCATION_REASONS) {
      assertThatCode(() -> update(id, "revoked_at = now(), revocation_reason = '" + reason + "'"))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void clearsTheRotationPointerWhenTheSuccessorIsDeleted() throws SQLException {
    UUID family = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    insertToken(first, family, "c".repeat(64));
    insertToken(second, family, "d".repeat(64));
    update(
        first,
        "revoked_at = now(), revocation_reason = 'ROTATED', rotated_to_id = '" + second + "'");

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM local_refresh_tokens WHERE id = '" + second + "'");
    }

    assertThat(
            count(
                connection,
                "local_refresh_tokens",
                "id = '" + first + "' AND rotated_to_id IS NULL"))
        .isEqualTo(1);
  }

  @Test
  void rejectsARotationPointerToAnUnknownToken() throws SQLException {
    UUID id = UUID.randomUUID();
    insertToken(id, UUID.randomUUID(), "e".repeat(64));

    assertThatThrownBy(() -> update(id, "rotated_to_id = '" + UUID.randomUUID() + "'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_local_refresh_tokens_rotated_to");
  }

  @Test
  void deletingTheUserDeletesItsTokens() throws SQLException {
    insertToken(UUID.randomUUID(), UUID.randomUUID(), "f".repeat(64));

    LocalAccountSchemaSupport.deleteUser(connection, userId);

    assertThat(count(connection, "local_refresh_tokens", "user_id = '" + userId + "'")).isZero();
  }

  @Test
  void rejectsATokenThatExpiresBeforeItWasIssued() {
    assertThatThrownBy(
            () ->
                insertToken(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "g".repeat(64),
                    "now()",
                    "now() - interval '1 minute'",
                    "now() + interval '30 days'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_refresh_tokens_expiry_order");
  }

  @Test
  void indexesActiveRowsPerUserAndPerFamilyOnly() throws SQLException {
    assertThat(indexDefinition(connection, "idx_local_refresh_tokens_active_user"))
        .isNotNull()
        .contains("(user_id)")
        .contains("WHERE (revoked_at IS NULL)");
    assertThat(indexDefinition(connection, "idx_local_refresh_tokens_active_family"))
        .isNotNull()
        .contains("(family_id)")
        .contains("WHERE (revoked_at IS NULL)");
    assertThat(indexDefinition(connection, "idx_local_refresh_tokens_expires_at"))
        .isNotNull()
        .contains("(expires_at)");
  }

  private void insertToken(UUID id, UUID family, String hash) throws SQLException {
    insertToken(
        id, family, hash, "now()", "now() + interval '7 days'", "now() + interval '30 days'");
  }

  private void insertToken(
      UUID id, UUID family, String hash, String issuedAt, String expiresAt, String familyExpiresAt)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO local_refresh_tokens (id, family_id, user_id, token_lookup_hash,"
                + " issued_at, expires_at, family_expires_at) VALUES (?, ?, ?, ?, "
                + issuedAt
                + ", "
                + expiresAt
                + ", "
                + familyExpiresAt
                + ")")) {
      statement.setObject(1, id);
      statement.setObject(2, family);
      statement.setObject(3, userId);
      statement.setString(4, hash);
      statement.executeUpdate();
    }
  }

  private void update(UUID id, String setClause) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "UPDATE local_refresh_tokens SET " + setClause + " WHERE id = '" + id + "'");
    }
  }
}
