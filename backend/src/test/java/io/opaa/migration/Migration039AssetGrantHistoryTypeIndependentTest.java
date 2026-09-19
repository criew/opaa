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
 * Delta tests for {@code changes/039-asset-grant-history-type-independent.yaml} (#1811): the rights
 * history follows the grant into type independence, and the existing intervals stay readable
 * (ADR-0016).
 */
class Migration039AssetGrantHistoryTypeIndependentTest extends AbstractMigrationTest {

  private static final String PREVIOUS_CHANGELOG_PATH =
      "db/changelog/changes/038-asset-grants-type-independent.yaml";
  private static final String CHANGELOG_PATH =
      "db/changelog/changes/039-asset-grant-history-type-independent.yaml";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    applyChangelog(connection, PREVIOUS_CHANGELOG_PATH);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheChangesetTheHistoryIsBoundToOneAssetTable() throws Exception {
    assertThat(columnExists("asset_grant_history", "library_id")).isTrue();
    assertThat(columnExists("asset_grant_history", "asset_type")).isFalse();
  }

  @Test
  void theChangesetReplacesTheLibraryColumnAndCarriesTheTypeIntoEveryIndex() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnExists("asset_grant_history", "library_id")).isFalse();
    assertThat(columnExists("asset_grant_history", "asset_id")).isTrue();
    assertThat(isNotNull("asset_grant_history", "asset_type")).isTrue();
    assertThat(indexDefinition("idx_asset_grant_history_asset")).contains("(asset_type, asset_id)");
    assertThat(indexDefinition("uk_asset_grant_history_open_user"))
        .contains("(asset_type, asset_id, subject_user_id)")
        .contains("valid_to IS NULL");
    assertThat(indexDefinition("uk_asset_grant_history_open_group"))
        .contains("(asset_type, asset_id, subject_group_id)")
        .contains("valid_to IS NULL");
  }

  /** Datenerhalt: the interval bestand is migrated, not rebuilt. */
  @Test
  void existingIntervalsSurviveWithTheirBoundariesAndBecomeKnowledgeLibraryIntervals()
      throws Exception {
    Fixture fixture = seedHistory();
    long before = count("SELECT count(*) FROM asset_grant_history");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(count("SELECT count(*) FROM asset_grant_history")).isEqualTo(before);
    assertThat(
            count(
                "SELECT count(*) FROM asset_grant_history WHERE asset_type <> 'KNOWLEDGE_LIBRARY'"))
        .isZero();
    assertThat(assetIdOf(fixture.openInterval())).isEqualTo(fixture.libraryId());
    assertThat(assetIdOf(fixture.closedInterval())).isEqualTo(fixture.libraryId());
    assertThat(
            count(
                "SELECT count(*) FROM asset_grant_history WHERE id = '"
                    + fixture.openInterval()
                    + "' AND valid_to IS NULL"))
        .isEqualTo(1);
  }

  /** The "at most one open interval" rule now holds per asset type, not across all of them. */
  @Test
  void twoAssetTypesSharingAnIdEachKeepTheirOwnOpenInterval() throws Exception {
    Fixture fixture = seedHistory();
    applyChangelog(connection, CHANGELOG_PATH);

    execute(
        "INSERT INTO asset_grant_history (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_user_id, role, cause, valid_from) VALUES (?, 'TEST_ASSET', ?, ?, 'USER',"
            + " ?, 'VIEWER', 'GRANTED', now())",
        UUID.randomUUID(),
        fixture.libraryId(),
        fixture.organizationId(),
        fixture.userId());

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO asset_grant_history (id, asset_type, asset_id, organization_id,"
                        + " subject_type, subject_user_id, role, cause, valid_from) VALUES (?,"
                        + " 'KNOWLEDGE_LIBRARY', ?, ?, 'USER', ?, 'VIEWER', 'GRANTED', now())",
                    UUID.randomUUID(),
                    fixture.libraryId(),
                    fixture.organizationId(),
                    fixture.userId()))
        .hasMessageContaining("uk_asset_grant_history_open_user");
  }

  /**
   * ADR-0016: an interval outlives the object it reports on - the changeset must not introduce a
   * foreign key that would take it down with a deleted library.
   */
  @Test
  void anIntervalStillOutlivesItsLibrary() throws Exception {
    Fixture fixture = seedHistory();
    applyChangelog(connection, CHANGELOG_PATH);

    execute("DELETE FROM knowledge_libraries WHERE id = ?", fixture.libraryId());

    assertThat(
            count(
                "SELECT count(*) FROM asset_grant_history WHERE asset_id = '"
                    + fixture.libraryId()
                    + "'"))
        .isEqualTo(2);
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    String master =
        new String(
            requireNonNull(
                    getClass()
                        .getClassLoader()
                        .getResourceAsStream("db/changelog/db.changelog-master.yaml"))
                .readAllBytes(),
            StandardCharsets.UTF_8);

    assertThat(master).contains(CHANGELOG_PATH);
  }

  // -----------------------------------------------------------------------------------------
  // Fixture
  // -----------------------------------------------------------------------------------------

  private record Fixture(
      UUID organizationId, UUID userId, UUID libraryId, UUID openInterval, UUID closedInterval) {}

  private Fixture seedHistory() throws SQLException {
    UUID organization = UUID.randomUUID();
    execute(
        "INSERT INTO organizations (id, name) VALUES (?, ?)",
        organization,
        "Organisation " + organization);
    UUID user = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, ?, ?)",
        user,
        "history-" + user,
        "https://issuer.example",
        organization);
    UUID library = UUID.randomUUID();
    execute(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, source_type) VALUES (?, ?, ?, 'USER', ?, 'PRIVATE', 'UPLOAD')",
        library,
        organization,
        "Bibliothek " + library,
        user);
    UUID closed = UUID.randomUUID();
    execute(
        "INSERT INTO asset_grant_history (id, library_id, organization_id, subject_type,"
            + " subject_user_id, role, cause, actor_user_id, valid_from, valid_to) VALUES (?, ?,"
            + " ?, 'USER', ?, 'VIEWER', 'GRANTED', ?, now() - interval '2 days', now() -"
            + " interval '1 day')",
        closed,
        library,
        organization,
        user,
        user);
    UUID open = UUID.randomUUID();
    execute(
        "INSERT INTO asset_grant_history (id, library_id, organization_id, subject_type,"
            + " subject_user_id, role, cause, actor_user_id, valid_from) VALUES (?, ?, ?, 'USER',"
            + " ?, 'MANAGER', 'ROLE_CHANGED', ?, now() - interval '1 day')",
        open,
        library,
        organization,
        user,
        user);
    return new Fixture(organization, user, library, open, closed);
  }

  private UUID assetIdOf(UUID intervalId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT asset_id FROM asset_grant_history WHERE id = ?")) {
      statement.setObject(1, intervalId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getObject(1, UUID.class);
      }
    }
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

  private boolean isNotNull(String table, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT is_nullable FROM information_schema.columns WHERE table_schema ="
                + " current_schema() AND table_name = ? AND column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return "NO".equals(rows.getString(1));
      }
    }
  }

  private String indexDefinition(String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema()"
                + " AND indexname = ?")) {
      statement.setString(1, name);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }
}
