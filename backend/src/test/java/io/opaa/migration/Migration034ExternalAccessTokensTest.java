package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
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
 * Delta tests for {@code changes/034-create-external-access-tokens.yaml} (#1718): the two tables of
 * the personal access tokens do not exist in the baseline and are created here.
 *
 * <p>What the assertions are about is the shape the feature depends on: one row per raw value (the
 * unique hash), a revocation that cannot be half-written, a selection entry that cannot be
 * duplicated, and the two delete rules - the tokens of a deleted person go with them, and a deleted
 * library does not make its selecting tokens undeletable.
 */
class Migration034ExternalAccessTokensTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/034-create-external-access-tokens.yaml";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws SQLException {
    connection = connect();
    connection.setAutoCommit(true);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void theBaselineDoesNotYetKnowTheTables() throws Exception {
    assertThat(tableExists("external_access_tokens")).isFalse();
    assertThat(tableExists("external_access_token_libraries")).isFalse();
  }

  @Test
  void theChangesetCreatesBothTablesWithTheirConstraints() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(tableExists("external_access_tokens")).isTrue();
    assertThat(tableExists("external_access_token_libraries")).isTrue();
    assertThat(constraintExists("external_access_tokens_pkey")).isTrue();
    assertThat(constraintExists("ux_external_access_tokens_hash")).isTrue();
    assertThat(constraintExists("chk_external_access_tokens_revocation")).isTrue();
    assertThat(constraintExists("chk_external_access_tokens_revocation_reason")).isTrue();
    assertThat(constraintExists("chk_external_access_tokens_name")).isTrue();
    assertThat(constraintExists("external_access_token_libraries_pkey")).isTrue();
  }

  @Test
  void carriesNoColumnForANetworkRangeAndNoUsageCounter() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnNames("external_access_tokens"))
        .contains(
            "id",
            "user_id",
            "name",
            "token_prefix",
            "token_lookup_hash",
            "created_at",
            "expires_at",
            "last_used_on",
            "revoked_at",
            "revocation_reason",
            "lapse_recorded_at")
        .doesNotContain("allowed_cidrs", "cidr", "usage_count", "last_used_at");
  }

  @Test
  void storesTheDayOfUseAsADateNotATimestamp() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(dataTypeOf("external_access_tokens", "last_used_on")).isEqualTo("date");
  }

  @Test
  void refusesASecondRowForTheSameRawValue() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID userId = seedUser();
    insertToken(UUID.randomUUID(), userId, "a".repeat(64));

    assertThatThrownBy(() -> insertToken(UUID.randomUUID(), userId, "a".repeat(64)))
        .hasMessageContaining("ux_external_access_tokens_hash");
  }

  @Test
  void refusesAHalfWrittenRevocation() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID userId = seedUser();
    UUID tokenId = UUID.randomUUID();
    insertToken(tokenId, userId, "b".repeat(64));

    assertThatThrownBy(
            () ->
                execute(
                    "UPDATE external_access_tokens SET revoked_at = now() WHERE id = '"
                        + tokenId
                        + "'"))
        .hasMessageContaining("chk_external_access_tokens_revocation");
    assertThatCode(
            () ->
                execute(
                    "UPDATE external_access_tokens SET revoked_at = now(), revocation_reason ="
                        + " 'OWNER' WHERE id = '"
                        + tokenId
                        + "'"))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesAnUnknownRevocationReasonAndABlankName() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID userId = seedUser();
    UUID tokenId = UUID.randomUUID();
    insertToken(tokenId, userId, "c".repeat(64));

    assertThatThrownBy(
            () ->
                execute(
                    "UPDATE external_access_tokens SET revoked_at = now(), revocation_reason ="
                        + " 'BOREDOM' WHERE id = '"
                        + tokenId
                        + "'"))
        .hasMessageContaining("chk_external_access_tokens_revocation_reason");
    assertThatThrownBy(() -> insertToken(UUID.randomUUID(), userId, "d".repeat(64), "   "))
        .hasMessageContaining("chk_external_access_tokens_name");
  }

  @Test
  void aDeletedPersonTakesTheirTokensAndSelectionsWithThem() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID userId = seedUser();
    UUID tokenId = UUID.randomUUID();
    insertToken(tokenId, userId, "e".repeat(64));
    UUID libraryId = seedLibrary(userId);
    execute(
        "INSERT INTO external_access_token_libraries (token_id, library_id) VALUES ('"
            + tokenId
            + "', '"
            + libraryId
            + "')");

    execute("DELETE FROM knowledge_libraries WHERE id = '" + libraryId + "'");
    assertThat(count("SELECT COUNT(*) FROM external_access_token_libraries")).isZero();
    assertThat(count("SELECT COUNT(*) FROM external_access_tokens")).isEqualTo(1);

    execute("DELETE FROM asset_grants WHERE subject_user_id = '" + userId + "'");
    execute("DELETE FROM users WHERE id = '" + userId + "'");
    assertThat(count("SELECT COUNT(*) FROM external_access_tokens")).isZero();
  }

  @Test
  void refusesTheSameLibraryTwiceInOneSelection() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID userId = seedUser();
    UUID tokenId = UUID.randomUUID();
    insertToken(tokenId, userId, "f".repeat(64));
    UUID libraryId = seedLibrary(userId);
    String insert =
        "INSERT INTO external_access_token_libraries (token_id, library_id) VALUES ('"
            + tokenId
            + "', '"
            + libraryId
            + "')";
    execute(insert);

    assertThatThrownBy(() -> execute(insert))
        .hasMessageContaining("external_access_token_libraries_pkey");
  }

  private void insertToken(UUID id, UUID userId, String hash) throws SQLException {
    insertToken(id, userId, hash, "Claude Code");
  }

  private void insertToken(UUID id, UUID userId, String hash, String name) throws SQLException {
    execute(
        "INSERT INTO external_access_tokens (id, user_id, name, token_prefix, token_lookup_hash,"
            + " expires_at) VALUES ('"
            + id
            + "', '"
            + userId
            + "', '"
            + name
            + "', 'opaa_pat_abc123', '"
            + hash
            + "', now() + interval '30 days')");
  }

  private UUID seedUser() throws SQLException {
    UUID organizationId = singleOrganizationId();
    UUID userId = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, organization_id, subject, issuer, email, display_name,"
            + " system_role) VALUES ('"
            + userId
            + "', '"
            + organizationId
            + "', '"
            + userId
            + "', 'opaa-test', '"
            + userId
            + "@test.example', 'Testperson', 'USER')");
    return userId;
  }

  private UUID seedLibrary(UUID ownerUserId) throws SQLException {
    UUID organizationId = singleOrganizationId();
    UUID libraryId = UUID.randomUUID();
    execute(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, listed, source_type) VALUES ('"
            + libraryId
            + "', '"
            + organizationId
            + "', 'Testbibliothek', 'USER', '"
            + ownerUserId
            + "', 'PRIVATE', false, 'UPLOAD')");
    return libraryId;
  }

  private UUID singleOrganizationId() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT id FROM organizations LIMIT 1")) {
      rs.next();
      return rs.getObject("id", UUID.class);
    }
  }

  private boolean tableExists(String table) throws SQLException {
    return scalarExists(
        "SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND"
            + " table_name = ?",
        table);
  }

  private boolean constraintExists(String name) throws SQLException {
    return scalarExists("SELECT 1 FROM pg_constraint WHERE conname = ?", name);
  }

  private boolean scalarExists(String sql, String parameter) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, parameter);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private List<String> columnNames(String table) throws SQLException {
    List<String> names = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT column_name FROM information_schema.columns WHERE table_schema ="
                + " current_schema() AND table_name = ?")) {
      statement.setString(1, table);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          names.add(rows.getString(1));
        }
      }
    }
    return names;
  }

  private String dataTypeOf(String table, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT data_type FROM information_schema.columns WHERE table_schema ="
                + " current_schema() AND table_name = ? AND column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getString(1);
      }
    }
  }

  private void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private long count(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
