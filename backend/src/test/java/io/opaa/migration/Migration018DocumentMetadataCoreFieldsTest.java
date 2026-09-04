package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Delta test for {@code changes/018-document-metadata-core-fields.yaml} (#1066, ADR-0024): the
 * Dokumentart vocabulary is seeded with stable codes, a value outside it is not storable, one row
 * per (document, field), a value's confidence only with origin DERIVED, each core field pinned to
 * its value column, the rows die with their document, and {@code
 * documents.metadata_extraction_version} starts NULL.
 */
class Migration018DocumentMetadataCoreFieldsTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/018-document-metadata-core-fields.yaml";
  private static final UUID ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private Connection connection;
  private UUID libraryId;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    libraryId = insertLibrary(insertUser("owner"));
    applyChangelog(connection, CHANGELOG_PATH);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void seedsTheDeliveredVocabularyWithStableCodesLabelsAndSynonyms() throws Exception {
    List<String> codes = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT code, label FROM document_type_vocabulary ORDER BY sort_order")) {
      while (rs.next()) {
        codes.add(rs.getString("code") + "=" + rs.getString("label"));
      }
    }
    assertThat(codes)
        .containsExactly(
            "SATZUNG_ORDNUNG=Satzung/Ordnung",
            "DIENSTANWEISUNG=Dienstanweisung",
            "VERMERK=Vermerk",
            "PROTOKOLL=Protokoll",
            "BESCHEID_VORLAGE=Bescheid-Vorlage",
            "FORMULAR=Formular",
            "GEBUEHRENVERZEICHNIS=Gebührenverzeichnis",
            "PRAESENTATION=Präsentation",
            "SONSTIGES=Sonstiges");
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT code FROM document_type_synonyms WHERE synonym = 'satzung'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("code")).isEqualTo("SATZUNG_ORDNUNG");
    }
    // No synonym shorter than four letters: a lower-cased abbreviation collides with everyday
    // words ("da") and would empty an otherwise unambiguous Dokumentart.
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT count(*) FROM document_type_synonyms WHERE length(synonym) < 4")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getLong(1)).isZero();
    }
  }

  @Test
  void metadataExtractionVersionStartsNullOnEveryDocument() throws Exception {
    UUID documentId = insertDocument(libraryId, "/uploads/a.pdf");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT metadata_extraction_version FROM documents WHERE id = ?")) {
      statement.setObject(1, documentId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getObject("metadata_extraction_version")).isNull();
      }
    }
  }

  @Test
  void aDocumentTypeOutsideTheVocabularyIsNotStorable() throws Exception {
    UUID documentId = insertDocument(libraryId, "/uploads/a.pdf");

    insertVocabularyValue(documentId, "DIENSTANWEISUNG");
    assertThatThrownBy(
            () -> insertVocabularyValue(insertDocument(libraryId, "/uploads/b.pdf"), "DA"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_document_metadata_values_vocabulary");
  }

  @Test
  void oneRowPerDocumentAndField() throws Exception {
    UUID documentId = insertDocument(libraryId, "/uploads/a.pdf");
    insertTextValue(documentId, "title", "Erster Titel", "DETERMINISTIC", 1, null);

    assertThatThrownBy(
            () -> insertTextValue(documentId, "title", "Zweiter Titel", "DETERMINISTIC", 1, null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_document_metadata_values_document_field");
  }

  @Test
  void confidenceIsOnlyStorableWithOriginDerived() throws Exception {
    UUID documentId = insertDocument(libraryId, "/uploads/a.pdf");

    assertThatThrownBy(() -> insertTextValue(documentId, "title", "Titel", "DETERMINISTIC", 1, 0.9))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_document_metadata_values_confidence_only_derived");
    insertTextValue(documentId, "title", "Titel", "DERIVED", 1, 0.9);
  }

  @Test
  void aCoreFieldIsPinnedToItsValueColumn() throws Exception {
    UUID documentId = insertDocument(libraryId, "/uploads/a.pdf");

    assertThatThrownBy(
            () -> insertTextValue(documentId, "document_type", "Vermerk", "DETERMINISTIC", 1, null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_document_metadata_values_core_field_type");
  }

  @Test
  void anAutomaticValueMustCarryItsExtractionVersion() throws Exception {
    UUID documentId = insertDocument(libraryId, "/uploads/a.pdf");

    assertThatThrownBy(
            () -> insertTextValue(documentId, "title", "Titel", "DETERMINISTIC", null, null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_document_metadata_values_extraction_version");
    insertTextValue(documentId, "title", "Titel", "MANUAL", null, null);
  }

  /** The third state (#1069) is a row without a value that only a person may write. */
  @Test
  void notDeterminableIsOnlyStorableManuallyAndWithoutAValue() throws Exception {
    UUID documentId = insertDocument(libraryId, "/uploads/a.pdf");

    assertThatThrownBy(
            () ->
                insertRow(documentId, "title", "NOT_DETERMINABLE", null, null, "DETERMINISTIC", 1))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_document_metadata_values_not_determinable_is_manual");
    assertThatThrownBy(
            () -> insertRow(documentId, "title", "NOT_DETERMINABLE", "Titel", null, "MANUAL", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_document_metadata_values_one_value");
    insertRow(documentId, "title", "NOT_DETERMINABLE", null, null, "MANUAL", null);
  }

  @Test
  void aSetRowCarriesExactlyOneValue() throws Exception {
    UUID documentId = insertDocument(libraryId, "/uploads/a.pdf");

    assertThatThrownBy(() -> insertRow(documentId, "title", "SET", null, null, "MANUAL", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_document_metadata_values_one_value");
    assertThatThrownBy(() -> insertTextAndVocabularyValue(documentId, "custom", "Text", "VERMERK"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_document_metadata_values_one_value");
  }

  private void insertTextAndVocabularyValue(
      UUID documentId, String fieldKey, String text, String code) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO document_metadata_values (id, document_id, field_key, text_value,"
                + " vocabulary_code, origin, created_at, updated_at) VALUES (?, ?, ?, ?, ?,"
                + " 'MANUAL', now(), now())")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, documentId);
      statement.setString(3, fieldKey);
      statement.setString(4, text);
      statement.setString(5, code);
      statement.executeUpdate();
    }
  }

  @Test
  void aDateAlwaysCarriesItsPrecision() throws Exception {
    UUID documentId = insertDocument(libraryId, "/uploads/a.pdf");

    assertThatThrownBy(
            () -> insertRow(documentId, "document_date", "SET", null, "2026-03-12", "MANUAL", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_document_metadata_values_date_has_precision");
  }

  @Test
  void createsTheDocumentIdExpressionIndexOnVectorStoreOnceItExists() throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS public.vector_store (id uuid PRIMARY KEY, content text,"
              + " metadata json, embedding vector(3))");
    }
    applyChangelog(connection, CHANGELOG_PATH);

    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname ="
                    + " 'idx_vector_store_document_id'")) {
      assertThat(rs.next()).as("index must exist").isTrue();
      assertThat(rs.getString("indexdef")).contains("(((metadata ->> 'document_id'::text))");
    }
  }

  @Test
  void valuesDieWithTheirDocument() throws Exception {
    UUID documentId = insertDocument(libraryId, "/uploads/a.pdf");
    insertTextValue(documentId, "title", "Titel", "DETERMINISTIC", 1, null);

    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM documents WHERE id = ?")) {
      statement.setObject(1, documentId);
      statement.executeUpdate();
    }

    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT count(*) FROM document_metadata_values WHERE document_id = ?")) {
      statement.setObject(1, documentId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong(1)).isZero();
      }
    }
  }

  private void insertVocabularyValue(UUID documentId, String code) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO document_metadata_values (id, document_id, field_key, vocabulary_code,"
                + " origin, extraction_version, created_at, updated_at) VALUES (?, ?,"
                + " 'document_type', ?, 'DETERMINISTIC', 1, now(), now())")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, documentId);
      statement.setString(3, code);
      statement.executeUpdate();
    }
  }

  private void insertTextValue(
      UUID documentId,
      String fieldKey,
      String text,
      String origin,
      Integer extractionVersion,
      Double confidence)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO document_metadata_values (id, document_id, field_key, text_value, origin,"
                + " extraction_version, confidence, created_at, updated_at) VALUES (?, ?, ?, ?, ?,"
                + " ?, ?, now(), now())")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, documentId);
      statement.setString(3, fieldKey);
      statement.setString(4, text);
      statement.setString(5, origin);
      statement.setObject(6, extractionVersion);
      statement.setObject(7, confidence);
      statement.executeUpdate();
    }
  }

  private void insertRow(
      UUID documentId,
      String fieldKey,
      String state,
      String text,
      String isoDate,
      String origin,
      Integer extractionVersion)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO document_metadata_values (id, document_id, field_key, value_state,"
                + " text_value, date_value, origin, extraction_version, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?::date, ?, ?, now(), now())")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, documentId);
      statement.setString(3, fieldKey);
      statement.setString(4, state);
      statement.setString(5, text);
      statement.setString(6, isoDate);
      statement.setString(7, origin);
      statement.setObject(8, extractionVersion);
      statement.executeUpdate();
    }
  }

  private UUID insertDocument(UUID libraryId, String filePath) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
                + " organization_id) VALUES (?, 'datei.pdf', ?, 'INDEXED', 'UPLOAD', ?, ?)")) {
      statement.setObject(1, id);
      statement.setString(2, filePath);
      statement.setObject(3, libraryId);
      statement.setObject(4, ORGANIZATION_ID);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertUser(String subject) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, 'test', ?)")) {
      statement.setObject(1, id);
      statement.setString(2, subject + "-" + id);
      statement.setObject(3, ORGANIZATION_ID);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertLibrary(UUID ownerUserId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
                + " owner_user_id, visibility, source_type)"
                + " VALUES (?, ?, ?, 'USER', ?, 'PRIVATE', 'UPLOAD')")) {
      statement.setObject(1, id);
      statement.setObject(2, ORGANIZATION_ID);
      statement.setString(3, "Bibliothek " + id);
      statement.setObject(4, ownerUserId);
      statement.executeUpdate();
    }
    return id;
  }
}
