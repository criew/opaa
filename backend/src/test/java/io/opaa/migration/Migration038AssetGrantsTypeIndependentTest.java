package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/038-asset-grants-type-independent.yaml} (#1811): the grant names
 * its object by asset type plus id, the bestand is migrated rather than rebuilt, and the guarantee
 * the dropped foreign key carried - "no grant outlives its asset" - is held by the trigger that
 * replaces it.
 */
class Migration038AssetGrantsTypeIndependentTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/038-asset-grants-type-independent.yaml";

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
  void beforeTheChangesetTheGrantIsBoundToOneAssetTable() throws Exception {
    assertThat(columnExists("asset_grants", "library_id")).isTrue();
    assertThat(columnExists("asset_grants", "asset_type")).isFalse();
    assertThat(constraintExists("fk_asset_grants_library_organization")).isTrue();
  }

  @Test
  void theChangesetReplacesTheLibraryColumnByAssetTypeAndAssetId() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnExists("asset_grants", "library_id")).isFalse();
    assertThat(columnExists("asset_grants", "asset_id")).isTrue();
    assertThat(columnExists("asset_grants", "asset_type")).isTrue();
    assertThat(isNotNull("asset_grants", "asset_type")).isTrue();
    assertThat(constraintExists("fk_asset_grants_library_organization")).isFalse();
    assertThat(indexDefinition("uk_asset_grants_user_subject"))
        .contains("(asset_type, asset_id, subject_user_id)");
    assertThat(indexDefinition("uk_asset_grants_group_subject"))
        .contains("(asset_type, asset_id, subject_group_id)");
  }

  /**
   * Datenerhalt: the existing bestand is migrated, not rebuilt - same row count, same ids, same
   * object references, plus the asset type every one of them implicitly had.
   */
  @Test
  void everyExistingGrantSurvivesWithItsValuesAndBecomesAKnowledgeLibraryGrant() throws Exception {
    Fixture fixture = seedLibraryWithGrants();
    long before = count("SELECT count(*) FROM asset_grants");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(count("SELECT count(*) FROM asset_grants")).isEqualTo(before);
    assertThat(count("SELECT count(*) FROM asset_grants WHERE asset_type <> 'KNOWLEDGE_LIBRARY'"))
        .isZero();
    assertThat(assetIdsOfGrants(fixture.userGrant(), fixture.groupGrant()))
        .containsExactly(fixture.libraryId(), fixture.libraryId());
    assertThat(roleOf(fixture.userGrant())).isEqualTo("OWNER");
    assertThat(roleOf(fixture.groupGrant())).isEqualTo("MANAGER");
  }

  /**
   * The replacement for the dropped {@code ON DELETE CASCADE}: the trigger takes the grants with
   * the library, so no grant survives the asset it refers to.
   */
  @Test
  void deletingTheLibraryStillTakesItsGrantsWithIt() throws Exception {
    Fixture fixture = seedLibraryWithGrants();
    applyChangelog(connection, CHANGELOG_PATH);

    execute("DELETE FROM knowledge_libraries WHERE id = ?", fixture.libraryId());

    assertThat(
            count(
                "SELECT count(*) FROM asset_grants WHERE asset_id = '" + fixture.libraryId() + "'"))
        .isZero();
  }

  /**
   * The trigger only sweeps its own asset type - a grant on a second, unrelated type is untouched
   * by a library deletion, which is the whole point of the type column.
   */
  @Test
  void aGrantOfASecondAssetTypeIsNeitherBlockedNorSweptByALibraryDeletion() throws Exception {
    Fixture fixture = seedLibraryWithGrants();
    applyChangelog(connection, CHANGELOG_PATH);

    UUID foreignAssetGrant = UUID.randomUUID();
    UUID foreignAssetId = UUID.randomUUID();
    execute(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_user_id, role) VALUES (?, 'TEST_ASSET', ?, ?, 'USER', ?, 'VIEWER')",
        foreignAssetGrant,
        foreignAssetId,
        fixture.organizationId(),
        fixture.userId());

    execute("DELETE FROM knowledge_libraries WHERE id = ?", fixture.libraryId());

    assertThat(count("SELECT count(*) FROM asset_grants WHERE id = '" + foreignAssetGrant + "'"))
        .isEqualTo(1);
  }

  /** Two asset types sharing an id must not collide in the per-subject uniqueness. */
  @Test
  void theSameIdUnderTwoAssetTypesCanCarryAGrantForTheSameSubject() throws Exception {
    Fixture fixture = seedLibraryWithGrants();
    applyChangelog(connection, CHANGELOG_PATH);

    execute(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_user_id, role) VALUES (?, 'TEST_ASSET', ?, ?, 'USER', ?, 'VIEWER')",
        UUID.randomUUID(),
        fixture.libraryId(),
        fixture.organizationId(),
        fixture.userId());

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id,"
                        + " subject_type, subject_user_id, role) VALUES (?, 'KNOWLEDGE_LIBRARY',"
                        + " ?, ?, 'USER', ?, 'VIEWER')",
                    UUID.randomUUID(),
                    fixture.libraryId(),
                    fixture.organizationId(),
                    fixture.userId()))
        .hasMessageContaining("uk_asset_grants_user_subject");
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
      UUID organizationId,
      UUID userId,
      UUID groupId,
      UUID libraryId,
      UUID userGrant,
      UUID groupGrant) {}

  private Fixture seedLibraryWithGrants() throws SQLException {
    UUID organization = UUID.randomUUID();
    execute(
        "INSERT INTO organizations (id, name) VALUES (?, ?)",
        organization,
        "Organisation " + organization);
    UUID user = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, ?, ?)",
        user,
        "grant-" + user,
        "https://issuer.example",
        organization);
    UUID group = UUID.randomUUID();
    execute(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'AD_HOC', ?)",
        group,
        organization,
        "Gruppe " + group);
    UUID library = UUID.randomUUID();
    execute(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, source_type) VALUES (?, ?, ?, 'USER', ?, 'PRIVATE', 'UPLOAD')",
        library,
        organization,
        "Bibliothek " + library,
        user);
    UUID userGrant = UUID.randomUUID();
    execute(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type, subject_user_id,"
            + " role, granted_by_user_id) VALUES (?, ?, ?, 'USER', ?, 'OWNER', ?)",
        userGrant,
        library,
        organization,
        user,
        user);
    UUID groupGrant = UUID.randomUUID();
    execute(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
            + " subject_group_id, role, granted_by_user_id) VALUES (?, ?, ?, 'GROUP', ?,"
            + " 'MANAGER', ?)",
        groupGrant,
        library,
        organization,
        group,
        user);
    return new Fixture(organization, user, group, library, userGrant, groupGrant);
  }

  private List<UUID> assetIdsOfGrants(UUID... grantIds) throws SQLException {
    List<UUID> assetIds = new java.util.ArrayList<>();
    for (UUID grantId : grantIds) {
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT asset_id FROM asset_grants WHERE id = ?")) {
        statement.setObject(1, grantId);
        try (ResultSet rows = statement.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assetIds.add(rows.getObject(1, UUID.class));
        }
      }
    }
    return assetIds;
  }

  private String roleOf(UUID grantId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT role FROM asset_grants WHERE id = ?")) {
      statement.setObject(1, grantId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
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

  private boolean constraintExists(String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM pg_constraint WHERE conname = ?")) {
      statement.setString(1, name);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
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
