package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta test for {@code changes/025-library-metadata-fields.yaml} (#1071): the per-library schema
 * fields and their controlled value lists. Asserts the four invariants the specification demands of
 * the database rather than of a service - a field without a retrieval effect, a second field on the
 * same citation position, a document value outside a field's value list and the removal of a value
 * a document still carries are all unreachable.
 */
class Migration025LibraryMetadataFieldsTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/025-library-metadata-fields.yaml";
  private static final String CORE_FIELDS_CHANGELOG_PATH =
      "db/changelog/changes/018-document-metadata-core-fields.yaml";
  private static final UUID ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    // 025 extends document_metadata_values, which 018 creates and the baseline fixture stops short
    // of - the same chaining Migration020DocumentTypeSuffixesTest uses.
    applyChangelog(connection, CORE_FIELDS_CHANGELOG_PATH);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void createsTheSchemaTablesAndEnforcesTheAufnahmeregel() throws Exception {
    assertThat(tableExists("library_metadata_fields")).isFalse();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(tableExists("library_metadata_fields")).isTrue();
    assertThat(tableExists("library_metadata_field_values")).isTrue();

    UUID libraryId = insertLibrary();
    assertThatThrownBy(() -> insertField(libraryId, "ohne_wirkung", false, false, null))
        .hasMessageContaining("chk_library_metadata_fields_retrieval_effect");
    assertThatCode(() -> insertField(libraryId, "fassung", true, false, null))
        .doesNotThrowAnyException();
    assertThatCode(() -> insertField(libraryId, "gremium", false, true, null))
        .doesNotThrowAnyException();
  }

  @Test
  void atMostTwoFieldsOfALibraryCarryACitationPosition() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID libraryId = insertLibrary();
    insertField(libraryId, "fassung", true, false, 1);
    insertField(libraryId, "gremium", true, false, 2);

    assertThatThrownBy(() -> insertField(libraryId, "projekt", true, false, 1))
        .hasMessageContaining("uk_library_metadata_fields_citation_position");
    assertThatThrownBy(() -> insertField(libraryId, "phase", true, false, 3))
        .hasMessageContaining("chk_library_metadata_fields_citation_position");
    // Another library starts over with both positions - the limit is per library, not global.
    assertThatCode(() -> insertField(insertLibrary(), "fassung", true, false, 1))
        .doesNotThrowAnyException();
  }

  @Test
  void aDocumentValueOutsideTheListIsNotStorableAndAUsedValueIsNotRemovable() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID libraryId = insertLibrary();
    UUID fieldId = insertField(libraryId, "fassung", true, false, null);
    UUID valueId = insertValue(fieldId, "FASSUNG_2026");
    UUID documentId = insertDocument(libraryId);

    assertThatThrownBy(
            () -> insertDocumentValue(documentId, "lib:fassung", fieldId, UUID.randomUUID()))
        .hasMessageContaining("fk_document_metadata_values_library_value");
    // The namespace and the field reference are pinned to each other in both directions.
    assertThatThrownBy(() -> insertDocumentValue(documentId, "fassung", fieldId, valueId))
        .hasMessageContaining("chk_document_metadata_values_library_field");

    // A list entry of another field is not storable either - the foreign key is composite.
    UUID otherFieldId = insertField(libraryId, "gremium", true, false, null);
    UUID otherValueId = insertValue(otherFieldId, "HAUPTAUSSCHUSS");
    assertThatThrownBy(() -> insertDocumentValue(documentId, "lib:fassung", fieldId, otherValueId))
        .hasMessageContaining("fk_document_metadata_values_library_value");

    insertDocumentValue(documentId, "lib:fassung", fieldId, valueId);
    assertThatThrownBy(() -> deleteValue(valueId))
        .as("a value a document still carries is only removable together with a mapping")
        .hasMessageContaining("fk_document_metadata_values_library_value");
  }

  private boolean tableExists(String table) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.tables WHERE table_name = ?")) {
      statement.setString(1, table);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next();
      }
    }
  }

  private UUID insertField(
      UUID libraryId, String key, boolean filter, boolean contextPrefix, Integer citationPosition)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO library_metadata_fields (id, library_id, field_key, label, field_type,"
                + " filter_enabled, context_prefix_enabled, citation_enabled, citation_position,"
                + " sort_order, created_at, updated_at) VALUES (?, ?, ?, ?, 'SELECT', ?, ?, ?, ?,"
                + " 10, now(), now())")) {
      statement.setObject(1, id);
      statement.setObject(2, libraryId);
      statement.setString(3, key);
      statement.setString(4, key);
      statement.setBoolean(5, filter);
      statement.setBoolean(6, contextPrefix);
      statement.setBoolean(7, citationPosition != null);
      if (citationPosition == null) {
        statement.setNull(8, java.sql.Types.INTEGER);
      } else {
        statement.setInt(8, citationPosition);
      }
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertValue(UUID fieldId, String code) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO library_metadata_field_values (id, field_id, code, label, sort_order)"
                + " VALUES (?, ?, ?, ?, 10)")) {
      statement.setObject(1, id);
      statement.setObject(2, fieldId);
      statement.setString(3, code);
      statement.setString(4, code);
      statement.executeUpdate();
    }
    return id;
  }

  private void insertDocumentValue(UUID documentId, String fieldKey, UUID fieldId, UUID valueId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO document_metadata_values (id, document_id, field_key, value_state,"
                + " text_value, origin, actor_user_id, library_field_id, library_value_id,"
                + " created_at, updated_at) VALUES (?, ?, ?, 'SET', 'FASSUNG_2026', 'MANUAL', NULL,"
                + " ?, ?, now(), now())")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, documentId);
      statement.setString(3, fieldKey);
      statement.setObject(4, fieldId);
      statement.setObject(5, valueId);
      statement.executeUpdate();
    }
  }

  private void deleteValue(UUID valueId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM library_metadata_field_values WHERE id = ?")) {
      statement.setObject(1, valueId);
      statement.executeUpdate();
    }
  }

  private UUID insertDocument(UUID libraryId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO documents (id, organization_id, library_id, file_name, file_path,"
                + " status, source_type) VALUES (?, ?, ?, 'a.pdf', ?, 'INDEXED', 'UPLOAD')")) {
      statement.setObject(1, id);
      statement.setObject(2, ORGANIZATION_ID);
      statement.setObject(3, libraryId);
      statement.setString(4, "/tmp/" + id + ".pdf");
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertLibrary() throws SQLException {
    UUID ownerId = insertUser();
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
                + " owner_user_id, visibility, listed, source_type) VALUES (?, ?, ?, 'USER', ?,"
                + " 'PRIVATE', false, 'UPLOAD')")) {
      statement.setObject(1, id);
      statement.setObject(2, ORGANIZATION_ID);
      statement.setString(3, "Bibliothek " + id);
      statement.setObject(4, ownerId);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertUser() throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, 'test', ?)")) {
      statement.setObject(1, id);
      statement.setString(2, "owner-" + id);
      statement.setObject(3, ORGANIZATION_ID);
      statement.executeUpdate();
    }
    return id;
  }
}
