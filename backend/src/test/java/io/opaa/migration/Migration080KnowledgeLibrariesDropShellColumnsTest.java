package io.opaa.migration;

import static io.opaa.migration.AssetShellMigrationFixtures.CREATE_ASSETS;
import static io.opaa.migration.AssetShellMigrationFixtures.DROP_SHELL_COLUMNS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/080-knowledge-libraries-drop-shell-columns.yaml} (#1899): the
 * shell columns leave the type table, the type columns and the library's data stay, and the owner
 * guarantees the dropped foreign keys gave are still held by the shell.
 */
class Migration080KnowledgeLibrariesDropShellColumnsTest extends AbstractMigrationTest {

  private static final List<String> SHELL_COLUMNS =
      List.of(
          "name",
          "description",
          "owner_type",
          "owner_user_id",
          "owner_group_id",
          "visibility",
          "listed",
          "created_at",
          "updated_at");

  private Connection connection;
  private AssetShellMigrationFixtures fixtures;

  @Override
  protected String baseFixtureChangelogPath() {
    return AssetShellMigrationFixtures.FIXTURE_CHAIN;
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    fixtures = new AssetShellMigrationFixtures(connection);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void theShellColumnsLeaveTheTypeTableAndTheTypeColumnsStay() throws Exception {
    UUID owner = fixtures.user();
    UUID library = fixtures.legacyLibrary("Bleibt", "USER", owner, null, "SHARED");
    applyChangelog(connection, CREATE_ASSETS);

    applyChangelog(connection, DROP_SHELL_COLUMNS);

    for (String column : SHELL_COLUMNS) {
      assertThat(fixtures.columnExists("knowledge_libraries", column)).as(column).isFalse();
    }
    assertThat(fixtures.columnExists("knowledge_libraries", "organization_id")).isTrue();
    assertThat(fixtures.string("SELECT source_type FROM knowledge_libraries WHERE id = ?", library))
        .isEqualTo("UPLOAD");
    assertThat(fixtures.string("SELECT name FROM assets WHERE id = ?", library))
        .isEqualTo("Bleibt");
  }

  @Test
  void anOwningGroupStaysUndeletableThroughTheShell() throws Exception {
    UUID group = fixtures.group();
    fixtures.legacyLibrary("Gruppe", "GROUP", null, group, "PRIVATE");
    applyChangelog(connection, CREATE_ASSETS);
    applyChangelog(connection, DROP_SHELL_COLUMNS);

    assertThatThrownBy(() -> fixtures.execute("DELETE FROM groups WHERE id = ?", group))
        .hasMessageContaining("fk_assets_owner_group_organization");
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(AssetShellMigrationFixtures.masterChangelog()).contains(DROP_SHELL_COLUMNS);
  }
}
