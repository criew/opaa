package io.opaa.migration;

import static io.opaa.migration.LocalAccountSchemaSupport.LOCAL_ISSUER;
import static io.opaa.migration.LocalAccountSchemaSupport.insertUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/014-local-action-token-handover.yaml} (#1563, ADR-0033
 * Entscheidung 12): a handover link binds the provider the administration chose and the reason it
 * gave. The pairing is a schema invariant, not a rule of one code path - only a {@code HANDOVER}
 * row carries the two, and it carries both - and a deleted provider takes the links prepared for it
 * with it instead of leaving a link to an identity nobody can present any more.
 */
class Migration014LocalActionTokenHandoverTest extends AbstractMigrationTest {

  private static final String TOKENS_CHANGELOG =
      "db/changelog/changes/009-create-local-action-tokens.yaml";
  private static final String CHANGELOG_PATH =
      "db/changelog/changes/014-local-action-token-handover.yaml";

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
    applyChangelog(connection, TOKENS_CHANGELOG);
    userId = insertUser(connection, LOCAL_ISSUER, "konto@stadt.example");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void theHandoverColumnsDoNotExistBeforeTheChangeset() throws Exception {
    assertThat(columnExists("provider_id")).isFalse();
    assertThat(columnExists("reason")).isFalse();
  }

  @Test
  void theChangesetAddsBothColumnsWithTheirConstraints() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnExists("provider_id")).isTrue();
    assertThat(columnExists("reason")).isTrue();
    assertThat(constraintExists("fk_local_action_tokens_provider")).isTrue();
    assertThat(constraintExists("chk_local_action_tokens_handover")).isTrue();
  }

  /** The invariant the CHECK carries: the pairing holds in both directions. */
  @Test
  void onlyAHandoverRowCarriesProviderAndReasonAndItCarriesBoth() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID providerId = insertProvider();

    assertThatCode(() -> insertToken("HANDOVER", "a".repeat(64), providerId, "Umstellung auf IdP"))
        .doesNotThrowAnyException();
    assertThatCode(() -> insertToken("SET_PASSWORD", "b".repeat(64), null, null))
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> insertToken("HANDOVER", "c".repeat(64), providerId, null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_action_tokens_handover");
    assertThatThrownBy(() -> insertToken("HANDOVER", "d".repeat(64), null, "Ohne Anbieter"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_action_tokens_handover");
    assertThatThrownBy(() -> insertToken("RESET_PASSWORD", "e".repeat(64), providerId, "Anlass"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_local_action_tokens_handover");
  }

  @Test
  void deletingTheProviderTakesEveryHandoverLinkPreparedForItWithIt() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID providerId = insertProvider();
    insertToken("HANDOVER", "f".repeat(64), providerId, "Umstellung auf IdP");
    insertToken("SET_PASSWORD", "g".repeat(64), null, null);

    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM oidc_providers WHERE id = ?")) {
      statement.setObject(1, providerId);
      statement.executeUpdate();
    }

    assertThat(tokenCount()).isEqualTo(1);
  }

  private UUID insertProvider() throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO oidc_providers (id, display_name, issuer_uri, client_id)"
                + " VALUES (?, ?, ?, ?)")) {
      statement.setObject(1, id);
      statement.setString(2, "Beschäftigte");
      statement.setString(3, "https://idp.test.example/realms/" + id);
      statement.setString(4, "opaa-frontend");
      statement.executeUpdate();
    }
    return id;
  }

  private void insertToken(String purpose, String hash, UUID providerId, String reason)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO local_action_tokens"
                + " (id, user_id, purpose, token_hash, expires_at, provider_id, reason)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, userId);
      statement.setString(3, purpose);
      statement.setString(4, hash);
      statement.setObject(5, Instant.now().plusSeconds(3600).atOffset(java.time.ZoneOffset.UTC));
      statement.setObject(6, providerId);
      statement.setString(7, reason);
      statement.executeUpdate();
    }
  }

  private int tokenCount() throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement("SELECT count(*) FROM local_action_tokens");
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getInt(1);
    }
  }

  private boolean columnExists(String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND table_name = 'local_action_tokens' AND column_name = ?")) {
      statement.setString(1, column);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private boolean constraintExists(String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM pg_constraint WHERE conname = ?")) {
      statement.setString(1, name);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }
}
