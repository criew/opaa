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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies changelog 009 in isolation (#1532, ADR-0033 Entscheidung 3): one action-token table with
 * a purpose instead of qnop's two identical ones - the four purposes as a CHECK, the HMAC hash
 * unique, {@code consumed_at} empty until redeemed, the cascade from {@code users}.
 */
class Migration009LocalActionTokensTest extends AbstractMigrationTest {

  static final List<String> PURPOSES =
      List.of("SET_PASSWORD", "RESET_PASSWORD", "VERIFY_EMAIL", "HANDOVER");

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
    applyChangelog(connection, "db/changelog/changes/009-create-local-action-tokens.yaml");
    userId = insertUser(connection, LOCAL_ISSUER, "konto@stadt.example");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void acceptsEveryPurposeAndNoOther() throws SQLException {
    for (String purpose : PURPOSES) {
      assertThatCode(() -> insertToken(purpose, purpose.toLowerCase().repeat(8).substring(0, 64)))
          .doesNotThrowAnyException();
    }
    assertThatThrownBy(() -> insertToken("DELETE_ACCOUNT", "z".repeat(64)))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_action_tokens_purpose");
  }

  @Test
  void rejectsADuplicateHash() throws SQLException {
    insertToken("SET_PASSWORD", "a".repeat(64));

    assertThatThrownBy(() -> insertToken("RESET_PASSWORD", "a".repeat(64)))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ux_local_action_tokens_hash");
  }

  @Test
  void startsUnconsumedWithACreationTimestamp() throws SQLException {
    insertToken("VERIFY_EMAIL", "b".repeat(64));

    assertThat(
            count(
                connection,
                "local_action_tokens",
                "consumed_at IS NULL AND created_at IS NOT NULL"))
        .isEqualTo(1);
  }

  @Test
  void deletingTheUserDeletesItsTokens() throws SQLException {
    insertToken("HANDOVER", "c".repeat(64));

    LocalAccountSchemaSupport.deleteUser(connection, userId);

    assertThat(count(connection, "local_action_tokens", "user_id = '" + userId + "'")).isZero();
  }

  @Test
  void indexesUserAndPurposeForSupersedingOpenLinks() throws SQLException {
    assertThat(indexDefinition(connection, "idx_local_action_tokens_user_purpose"))
        .isNotNull()
        .contains("(user_id, purpose)");
    assertThat(indexDefinition(connection, "idx_local_action_tokens_expires_at"))
        .isNotNull()
        .contains("(expires_at)");
  }

  private void insertToken(String purpose, String hash) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO local_action_tokens (id, user_id, purpose, token_hash, expires_at)"
                + " VALUES (?, ?, ?, ?, now() + interval '30 minutes')")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, userId);
      statement.setString(3, purpose);
      statement.setString(4, hash);
      statement.executeUpdate();
    }
  }
}
