package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
 * Delta tests for {@code changes/031-library-metadata-schema-changes.yaml} (#1361): the table that
 * makes a value mapping and a field deletion resumable does not exist in the baseline and is
 * created here. What the assertions are about is the state machine the table encodes - one running
 * mapping per list value, one running deletion per field, a target that cannot vanish underneath a
 * running mapping, and a change that is meaningless without its subject.
 */
class Migration031LibraryMetadataSchemaChangesTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/031-library-metadata-schema-changes.yaml";

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
  void theBaselineDoesNotYetKnowTheTable() throws Exception {
    assertThat(tableExists("library_metadata_schema_changes")).isFalse();
  }

  @Test
  void theChangesetCreatesTheTableWithItsConstraints() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(tableExists("library_metadata_schema_changes")).isTrue();
    assertThat(constraintExists("library_metadata_schema_changes_pkey")).isTrue();
    assertThat(constraintExists("uk_library_metadata_schema_changes_subject")).isTrue();
    assertThat(constraintExists("chk_library_metadata_schema_changes_kind")).isTrue();
    assertThat(constraintExists("chk_library_metadata_schema_changes_subject")).isTrue();
    assertThat(constraintExists("chk_library_metadata_schema_changes_target")).isTrue();
    assertThat(constraintExists("fk_library_metadata_schema_changes_field")).isTrue();
  }

  /** One running mapping per list value, one running deletion per field - nothing may run twice. */
  @Test
  void aSecondChangeForTheSameSubjectIsRefused() throws Exception {
    Fixture fixture = seedField();
    applyChangelog(connection, CHANGELOG_PATH);
    insertRemap(fixture, fixture.valueA(), fixture.valueB());

    assertThatThrownBy(() -> insertRemap(fixture, fixture.valueA(), null))
        .hasMessageContaining("uk_library_metadata_schema_changes_subject");
    assertThatCode(() -> insertRemap(fixture, fixture.valueB(), null)).doesNotThrowAnyException();

    insertDeletion(fixture);
    assertThatThrownBy(() -> insertDeletion(fixture))
        .as("NULLS NOT DISTINCT: the deletion row is the one per field, not one per attempt")
        .hasMessageContaining("uk_library_metadata_schema_changes_subject");
  }

  /** A mapping names a value, a deletion does not, and nothing maps a value onto itself. */
  @Test
  void theKindDecidesWhichSubjectAndTargetAreRepresentable() throws Exception {
    Fixture fixture = seedField();
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () -> insert(fixture, null, null, "VALUE_REMAP", "metadata-remap-" + UUID.randomUUID()))
        .hasMessageContaining("chk_library_metadata_schema_changes_subject");
    assertThatThrownBy(
            () ->
                insert(
                    fixture,
                    fixture.valueA(),
                    null,
                    "FIELD_DELETION",
                    "metadata-field-delete-" + UUID.randomUUID()))
        .hasMessageContaining("chk_library_metadata_schema_changes_subject");
    assertThatThrownBy(
            () ->
                insert(
                    fixture,
                    fixture.valueA(),
                    fixture.valueA(),
                    "VALUE_REMAP",
                    "metadata-remap-" + UUID.randomUUID()))
        .hasMessageContaining("chk_library_metadata_schema_changes_target");
    assertThatThrownBy(
            () ->
                insert(
                    fixture,
                    fixture.valueA(),
                    null,
                    "UMBENENNEN",
                    "metadata-remap-" + UUID.randomUUID()))
        .hasMessageContaining("chk_library_metadata_schema_changes_kind");
  }

  /**
   * The two foreign keys that carry the state machine: a mapping dies with the value it retires,
   * and the value a mapping writes onto cannot be deleted while that mapping runs - the second
   * guard beneath the application's refusal to retire a running target.
   */
  @Test
  void theSubjectCascadesWhileTheTargetRestricts() throws Exception {
    Fixture fixture = seedField();
    applyChangelog(connection, CHANGELOG_PATH);
    UUID changeId = insertRemap(fixture, fixture.valueA(), fixture.valueB());

    assertThatThrownBy(() -> deleteValue(fixture.valueB()))
        .hasMessageContaining("fk_library_metadata_schema_changes_target");

    deleteValue(fixture.valueA());
    assertThat(changeExists(changeId)).isFalse();
  }

  /** Deleting the field takes every change of it - including a mapping of one of its values. */
  @Test
  void deletingTheFieldTakesItsChangesWithIt() throws Exception {
    Fixture fixture = seedField();
    applyChangelog(connection, CHANGELOG_PATH);
    UUID changeId = insertDeletion(fixture);

    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM library_metadata_fields WHERE id = ?")) {
      statement.setObject(1, fixture.fieldId());
      statement.executeUpdate();
    }

    assertThat(changeExists(changeId)).isFalse();
  }

  /**
   * Standing guard, not a property of the changeset: every test here applies {@link
   * #CHANGELOG_PATH} itself, so a changelog file missing from {@code db.changelog-master.yaml}
   * would still pass every assertion above while no installation ever ran it.
   */
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

  private record Fixture(UUID fieldId, UUID valueA, UUID valueB) {}

  private Fixture seedField() throws SQLException {
    UUID organization = insertOrganization();
    UUID owner = insertUser(organization);
    UUID library = insertLibrary(organization, owner);
    UUID fieldId = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO library_metadata_fields (id, library_id, field_key, label, field_type,"
                + " filter_enabled, sort_order, created_at, updated_at)"
                + " VALUES (?, ?, 'fassung', 'Fassung', 'SELECT', true, 10, now(), now())")) {
      statement.setObject(1, fieldId);
      statement.setObject(2, library);
      statement.executeUpdate();
    }
    return new Fixture(fieldId, insertValue(fieldId, "A", 10), insertValue(fieldId, "B", 20));
  }

  private UUID insertValue(UUID fieldId, String code, int sortOrder) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO library_metadata_field_values (id, field_id, code, label, sort_order)"
                + " VALUES (?, ?, ?, ?, ?)")) {
      statement.setObject(1, id);
      statement.setObject(2, fieldId);
      statement.setString(3, code);
      statement.setString(4, "Wert " + code);
      statement.setInt(5, sortOrder);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertOrganization() throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement("INSERT INTO organizations (id, name) VALUES (?, ?)")) {
      statement.setObject(1, id);
      statement.setString(2, "Organisation " + id);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertUser(UUID organizationId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, ?, ?)")) {
      statement.setObject(1, id);
      statement.setString(2, "schema-change-" + id);
      statement.setString(3, "https://issuer.example");
      statement.setObject(4, organizationId);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertLibrary(UUID organizationId, UUID ownerId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
                + " owner_user_id, visibility, source_type, source_path)"
                + " VALUES (?, ?, ?, 'USER', ?, 'PRIVATE', 'FILESYSTEM', '/tmp/schema-change')")) {
      statement.setObject(1, id);
      statement.setObject(2, organizationId);
      statement.setString(3, "Bibliothek " + id);
      statement.setObject(4, ownerId);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertRemap(Fixture fixture, UUID valueId, UUID targetId) throws SQLException {
    return insert(fixture, valueId, targetId, "VALUE_REMAP", "metadata-remap-" + UUID.randomUUID());
  }

  private UUID insertDeletion(Fixture fixture) throws SQLException {
    return insert(
        fixture, null, null, "FIELD_DELETION", "metadata-field-delete-" + UUID.randomUUID());
  }

  private UUID insert(
      Fixture fixture, UUID valueId, UUID targetId, String kind, String correlationRef)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO library_metadata_schema_changes (id, field_id, value_id,"
                + " target_value_id, change_kind, correlation_ref) VALUES (?, ?, ?, ?, ?, ?)")) {
      statement.setObject(1, id);
      statement.setObject(2, fixture.fieldId());
      statement.setObject(3, valueId);
      statement.setObject(4, targetId);
      statement.setString(5, kind);
      statement.setString(6, correlationRef);
      statement.executeUpdate();
    }
    return id;
  }

  private void deleteValue(UUID valueId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM library_metadata_field_values WHERE id = ?")) {
      statement.setObject(1, valueId);
      statement.executeUpdate();
    }
  }

  private boolean changeExists(UUID changeId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM library_metadata_schema_changes WHERE id = ?")) {
      statement.setObject(1, changeId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private boolean tableExists(String table) throws SQLException {
    return scalarExists(
        "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
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
}
