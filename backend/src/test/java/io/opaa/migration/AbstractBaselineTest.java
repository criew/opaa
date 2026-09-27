package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base of the per-module baseline test classes: every test method gets its own clone of a database
 * built from {@code db/changelog/test-master-through-baseline.yaml}, a connection in auto-commit
 * mode, and the row helpers the modules share. A helper writes the minimal valid row and returns
 * its id; everything a test asserts about is spelled out in the test itself.
 */
abstract class AbstractBaselineTest extends AbstractMigrationTest {

  static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  protected Connection connection;

  @Override
  protected List<String> baseFixtureChangelogs() {
    return List.of("db/changelog/test-master-through-baseline.yaml");
  }

  @BeforeEach
  void openConnection() throws SQLException {
    connection = connect();
    connection.setAutoCommit(true);
  }

  @AfterEach
  void closeConnection() throws SQLException {
    connection.close();
  }

  // ---------------------------------------------------------------------------------------------
  // Statements and assertions
  // ---------------------------------------------------------------------------------------------

  protected void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  /** Asserts that {@code sql} fails and that the database names {@code expected} as the reason. */
  protected void assertRejected(String sql, String expected) {
    assertThatThrownBy(() -> execute(sql))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining(expected);
  }

  protected long countRows(String table) throws SQLException {
    return countWhere(table, "true");
  }

  protected long countWhere(String table, String whereClause) throws SQLException {
    return longOf("SELECT count(*) FROM " + table + " WHERE " + whereClause);
  }

  protected long longOf(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      assertThat(rs.next()).as("a row for: %s", sql).isTrue();
      return rs.getLong(1);
    }
  }

  protected String stringOf(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      assertThat(rs.next()).as("a row for: %s", sql).isTrue();
      return rs.getString(1);
    }
  }

  protected boolean booleanOf(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      assertThat(rs.next()).as("a row for: %s", sql).isTrue();
      return rs.getBoolean(1);
    }
  }

  protected static String quoted(Object value) {
    return value == null ? "NULL" : "'" + value + "'";
  }

  // ---------------------------------------------------------------------------------------------
  // Rows the modules share
  // ---------------------------------------------------------------------------------------------

  /** A new organization; its AFTER INSERT trigger delivers the default capabilities. */
  protected UUID insertOrganization() throws SQLException {
    UUID id = UUID.randomUUID();
    execute("INSERT INTO organizations (id, name) VALUES ('" + id + "', 'Org " + id + "')");
    return id;
  }

  protected UUID insertUser() throws SQLException {
    return insertUser(UUID.fromString(SEEDED_ORGANIZATION_ID));
  }

  protected UUID insertUser(UUID organizationId) throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, system_role, organization_id) VALUES ('"
            + id
            + "', '"
            + id
            + "', 'test-issuer', 'USER', '"
            + organizationId
            + "')");
    return id;
  }

  /** An OIDC provider row; {@code issuer} must be unique after trimming trailing slashes. */
  protected UUID insertProvider(String issuer, boolean isDefault) throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO oidc_providers (id, display_name, is_default, issuer_uri, client_id) VALUES ('"
            + id
            + "', 'Anbieter', "
            + isDefault
            + ", '"
            + issuer
            + "', 'opaa-frontend')");
    return id;
  }

  protected UUID insertProvider() throws SQLException {
    return insertProvider("https://idp.example/realms/" + UUID.randomUUID(), false);
  }

  /** An internal (AD_HOC) group of the seeded organization. */
  protected UUID insertInternalGroup() throws SQLException {
    return insertGroup("AD_HOC", null, null);
  }

  protected UUID insertGroup(String kind, UUID providerId, String externalId) throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO groups (id, organization_id, kind, name, provider_id, external_id) VALUES ('"
            + id
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', '"
            + kind
            + "', 'Gruppe', "
            + quoted(providerId)
            + ", "
            + quoted(externalId)
            + ")");
    return id;
  }

  /** The shell row of an asset of the given type, owned by {@code ownerId}. */
  protected UUID insertAsset(String assetType, UUID ownerId) throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_user_id)"
            + " VALUES ('"
            + id
            + "', '"
            + assetType
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'Asset', 'USER', '"
            + ownerId
            + "')");
    return id;
  }

  /** An UPLOAD knowledge library: its shell row plus the type row. */
  protected UUID insertLibrary() throws SQLException {
    return insertLibrary("UPLOAD", null);
  }

  protected UUID insertLibrary(String sourceType, String sourceUrl) throws SQLException {
    UUID id = insertAsset("KNOWLEDGE_LIBRARY", insertUser());
    execute(
        "INSERT INTO knowledge_libraries (id, organization_id, source_type, source_url) VALUES ('"
            + id
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', '"
            + sourceType
            + "', "
            + quoted(sourceUrl)
            + ")");
    return id;
  }

  protected UUID insertDocument(UUID libraryId, String filePath) throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
            + " organization_id) VALUES ('"
            + id
            + "', 'report.pdf', '"
            + filePath
            + "', 'INDEXED', 'HTTP_DIRECTORY', '"
            + libraryId
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "')");
    return id;
  }

  protected UUID insertSpace(UUID ownerId) throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO spaces (id, name, owner_id, organization_id) VALUES ('"
            + id
            + "', 'Space', '"
            + ownerId
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "')");
    return id;
  }

  protected UUID insertChat(UUID authorId) throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO chats (id, space_id, author_id, organization_id) VALUES ('"
            + id
            + "', '"
            + insertSpace(authorId)
            + "', '"
            + authorId
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "')");
    return id;
  }

  protected UUID insertPermissionTransfer() throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO permission_transfers (id, organization_id, source_type, source_user_id,"
            + " target_type, target_user_id, scope, performed_at) VALUES ('"
            + id
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'USER', '"
            + insertUser()
            + "', 'USER', '"
            + insertUser()
            + "', 'GRANTS', now())");
    return id;
  }
}
