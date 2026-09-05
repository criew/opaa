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
 * Delta test for {@code changes/028-model-metadata-extraction.yaml} (#1073): the two switches, the
 * keyword table, the Zählwerk and the rejection log. Asserts what the specification demands of the
 * database rather than of a service - both switches are off for an existing library, a keyword
 * vanishes with its document, and a rejection reason outside the two known ones is unreachable.
 */
class Migration028ModelMetadataExtractionTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/028-model-metadata-extraction.yaml";
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
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void bothSwitchesAreOffForALibraryThatExistedBeforeTheMigration() throws Exception {
    UUID libraryId = insertLibrary();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(booleanColumn(libraryId, "model_extraction_enabled")).isFalse();
    assertThat(booleanColumn(libraryId, "keywords_enabled")).isFalse();
  }

  @Test
  void aDocumentTakesItsKeywordsWithIt() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID libraryId = insertLibrary();
    UUID documentId = insertDocument(libraryId);
    insertKeyword(documentId, libraryId, "abfallentsorgung");

    assertThatThrownBy(() -> insertKeyword(documentId, libraryId, "abfallentsorgung"))
        .as("the same keyword is never stored twice for one document")
        .hasMessageContaining("uq_document_keywords_document_keyword");

    deleteDocument(documentId);

    assertThat(countKeywords(documentId)).isZero();
  }

  @Test
  void onlyTheTwoKnownRejectionReasonsAreStorable() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID libraryId = insertLibrary();
    UUID documentId = insertDocument(libraryId);

    assertThatCode(() -> insertRejection(libraryId, documentId, "BELOW_THRESHOLD"))
        .doesNotThrowAnyException();
    assertThatCode(() -> insertRejection(libraryId, documentId, "OUTSIDE_VOCABULARY"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> insertRejection(libraryId, documentId, "ACCEPTED"))
        .hasMessageContaining("chk_metadata_model_rejections_reason");

    // The measurement survives the document it was taken on - the distribution is what calibrates
    // the threshold, and it must not thin out whenever a document is removed.
    deleteDocument(documentId);
    assertThat(countRejections(libraryId)).isEqualTo(2);
  }

  @Test
  void theCounterRowIsOnePerLibraryAndStartsAtZero() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID libraryId = insertLibrary();

    insertStats(libraryId);

    assertThatThrownBy(() -> insertStats(libraryId))
        .hasMessageContaining("metadata_model_extraction_stats_pkey");
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT calls, accepted_values, rejected_below_threshold,"
                + " rejected_outside_vocabulary, failures, keywords_assigned, last_call_at FROM"
                + " metadata_model_extraction_stats WHERE library_id = ?")) {
      statement.setObject(1, libraryId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong("calls")).isZero();
        assertThat(rs.getLong("accepted_values")).isZero();
        assertThat(rs.getLong("rejected_below_threshold")).isZero();
        assertThat(rs.getLong("rejected_outside_vocabulary")).isZero();
        assertThat(rs.getLong("failures")).isZero();
        assertThat(rs.getLong("keywords_assigned")).isZero();
        assertThat(rs.getTimestamp("last_call_at")).isNull();
      }
    }
  }

  @Test
  void bothDrainMarksStartEmptyForAnExistingDocument() throws Exception {
    UUID libraryId = insertLibrary();
    UUID documentId = insertDocument(libraryId);

    applyChangelog(connection, CHANGELOG_PATH);

    // One mark per capability: a library that ran with only one of the two switches must still
    // hand its Altbestand to the Bestandslauf when the other one is switched on later.
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT model_extraction_version, keyword_extraction_version FROM documents WHERE"
                + " id = ?")) {
      statement.setObject(1, documentId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        rs.getInt("model_extraction_version");
        assertThat(rs.wasNull())
            .as("an Altbestand document has never been through the model step")
            .isTrue();
        rs.getInt("keyword_extraction_version");
        assertThat(rs.wasNull()).as("nor through the keyword step").isTrue();
      }
    }
  }

  private boolean booleanColumn(UUID libraryId, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT " + column + " FROM knowledge_libraries WHERE id = ?")) {
      statement.setObject(1, libraryId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getBoolean(1);
      }
    }
  }

  private void insertKeyword(UUID documentId, UUID libraryId, String keyword) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO document_keywords (id, document_id, library_id, keyword, model_id,"
                + " extraction_version) VALUES (?, ?, ?, ?, 'test-model', 1)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, documentId);
      statement.setObject(3, libraryId);
      statement.setString(4, keyword);
      statement.executeUpdate();
    }
  }

  private void insertRejection(UUID libraryId, UUID documentId, String reason) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO metadata_model_rejections (id, library_id, document_id, field_key,"
                + " proposed_value, confidence, reason) VALUES (?, ?, ?, 'document_type',"
                + " 'SATZUNG_ORDNUNG', 0.4, ?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, libraryId);
      statement.setObject(3, documentId);
      statement.setString(4, reason);
      statement.executeUpdate();
    }
  }

  private void insertStats(UUID libraryId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO metadata_model_extraction_stats (library_id) VALUES (?)")) {
      statement.setObject(1, libraryId);
      statement.executeUpdate();
    }
  }

  private int countKeywords(UUID documentId) throws SQLException {
    return countBy("SELECT count(*) FROM document_keywords WHERE document_id = ?", documentId);
  }

  private int countRejections(UUID libraryId) throws SQLException {
    return countBy(
        "SELECT count(*) FROM metadata_model_rejections WHERE library_id = ?", libraryId);
  }

  private int countBy(String sql, UUID id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getInt(1);
      }
    }
  }

  private void deleteDocument(UUID documentId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM documents WHERE id = ?")) {
      statement.setObject(1, documentId);
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
