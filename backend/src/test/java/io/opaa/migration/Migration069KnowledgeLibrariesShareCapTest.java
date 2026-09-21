package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/069-knowledge-libraries-share-cap.yaml} (#797): the two new
 * columns are added with the delivered, unrestrictive default so an existing library's effective
 * reach does not change on the migration day.
 */
class Migration069KnowledgeLibrariesShareCapTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/069-knowledge-libraries-share-cap.yaml";

  private static final UUID DEFAULT_ORGANIZATION =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheChangesetTheColumnsDoNotExist() throws Exception {
    assertThat(columnExists("knowledge_libraries", "visibility_cap")).isFalse();
    assertThat(columnExists("knowledge_libraries", "listed_cap")).isFalse();
  }

  @Test
  void anExistingLibraryGetsTheUnrestrictiveDefault() throws Exception {
    UUID library = seedLibrary();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnExists("knowledge_libraries", "visibility_cap")).isTrue();
    assertThat(columnExists("knowledge_libraries", "listed_cap")).isTrue();
    assertThat(
            count(
                "SELECT count(*) FROM knowledge_libraries WHERE id = '"
                    + library
                    + "' AND visibility_cap = 'ORGANIZATION' AND listed_cap = true"))
        .as("a pre-existing library is delivered unrestricted, not clamped")
        .isEqualTo(1);
  }

  @Test
  void visibilityCapRejectsAValueOutsideTheThreeStages() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID library = seedLibrary();

    assertThatThrownBy(
            () ->
                execute(
                    "UPDATE knowledge_libraries SET visibility_cap = 'DEPARTMENT' WHERE id = ?",
                    library))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_visibility_cap");
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(masterChangelog()).contains(CHANGELOG_PATH);
  }

  // -----------------------------------------------------------------------------------------

  private UUID seedLibrary() throws SQLException {
    UUID owner = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, ?, ?)",
        owner,
        "share-cap-" + owner,
        "https://issuer.example",
        DEFAULT_ORGANIZATION);
    UUID library = UUID.randomUUID();
    execute(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, source_type)"
            + " VALUES (?, ?, ?, 'USER', ?, 'PRIVATE', 'UPLOAD')",
        library,
        DEFAULT_ORGANIZATION,
        "Bibliothek",
        owner);
    return library;
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  private long count(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  private boolean columnExists(String table, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema()"
                + " AND table_name = ? AND column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private String masterChangelog() throws Exception {
    return new String(
        requireNonNull(
                getClass()
                    .getClassLoader()
                    .getResourceAsStream("db/changelog/db.changelog-master.yaml"))
            .readAllBytes(),
        StandardCharsets.UTF_8);
  }
}
