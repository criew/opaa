package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/002-documents-parent-composite-foreign-key.yaml} (#1500): {@code
 * fk_documents_parent} changes from the single-column self-reference the baseline carries into the
 * composite {@code (parent_document_id, organization_id) -> (id, organization_id)} the
 * organization-boundary rule (#390) demands, against the state {@code
 * db/changelog/changes/001-baseline.yaml} leaves behind.
 *
 * <p>The delete rule is part of the contract: the key keeps the {@code NO ACTION} it had, which is
 * what ADR-0022, Entscheidung 4 requires - no database-side cascade, because deleting a parent
 * document stays application code.
 */
class Migration002DocumentsParentForeignKeyTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/002-documents-parent-composite-foreign-key.yaml";

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

  /**
   * The defect itself, as it exists before the migration: the single-column key binds only the id,
   * so a document can name a parent belonging to a different organization.
   */
  @Test
  void beforeTheMigrationAParentOfAnotherOrganizationIsAccepted() throws Exception {
    assertThat(foreignKeyColumns("documents", "fk_documents_parent", "conkey", "conrelid"))
        .containsExactly("parent_document_id");
    assertThat(foreignKeyColumns("documents", "fk_documents_parent", "confkey", "confrelid"))
        .containsExactly("id");

    Fixture fixture = seedTwoOrganizations();

    assertThatCode(() -> insertDocument(fixture.foreignChild(), fixture.parent()))
        .doesNotThrowAnyException();
  }

  @Test
  void theForeignKeyBecomesCompositeAgainstAUniqueTarget() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(foreignKeyColumns("documents", "fk_documents_parent", "conkey", "conrelid"))
        .containsExactly("parent_document_id", "organization_id");
    assertThat(foreignKeyColumns("documents", "fk_documents_parent", "confkey", "confrelid"))
        .containsExactly("id", "organization_id");
    assertThat(uniqueConstraintColumns("documents", "uk_documents_id_organization"))
        .containsExactly("id", "organization_id");
  }

  /**
   * {@code confdeltype = 'a'} is {@code NO ACTION}, the rule the single-column key already had: no
   * cascade, which would leave the document's pgvector chunks orphaned (ADR-0022).
   */
  @Test
  void theForeignKeyKeepsItsNoActionDeleteRule() throws Exception {
    assertThat(deleteRule("fk_documents_parent")).isEqualTo("a");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(deleteRule("fk_documents_parent")).isEqualTo("a");
  }

  @Test
  void aParentOfAnotherOrganizationIsRejectedAfterTheMigration() throws Exception {
    Fixture fixture = seedTwoOrganizations();
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(() -> insertDocument(fixture.foreignChild(), fixture.parent()))
        .hasMessageContaining("fk_documents_parent");
  }

  @Test
  void aParentOfTheSameOrganizationStaysAccepted() throws Exception {
    Fixture fixture = seedTwoOrganizations();
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatCode(() -> insertDocument(fixture.ownChild(), fixture.parent()))
        .doesNotThrowAnyException();
    assertThatCode(() -> insertDocument(fixture.parentlessChild(), null))
        .as("parent_document_id stays nullable and a NULL column skips the composite check")
        .doesNotThrowAnyException();
  }

  @Test
  void deletingAParentWhileAnAttachmentStillReferencesItStaysRejected() throws Exception {
    Fixture fixture = seedTwoOrganizations();
    applyChangelog(connection, CHANGELOG_PATH);
    insertDocument(fixture.ownChild(), fixture.parent());

    assertThatThrownBy(() -> deleteDocument(fixture.parent().id()))
        .hasMessageContaining("fk_documents_parent");
    assertThat(documentExists(fixture.ownChild().id()))
        .as("no cascade: deleting a parent document stays application code (ADR-0022)")
        .isTrue();
  }

  /**
   * The composite key must not break the bulk delete path: {@code
   * DocumentRepository#deleteByLibraryId} (behind {@code KnowledgeLibraryService#deleteLibrary})
   * removes a parent and its attachment rows in a single statement.
   */
  @Test
  void deletingParentAndAttachmentInOneStatementStaysAccepted() throws Exception {
    Fixture fixture = seedTwoOrganizations();
    applyChangelog(connection, CHANGELOG_PATH);
    insertDocument(fixture.ownChild(), fixture.parent());

    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM documents WHERE library_id = ?")) {
      statement.setObject(1, fixture.parent().libraryId());
      assertThat(statement.executeUpdate()).isEqualTo(2);
    }
    assertThat(documentExists(fixture.parent().id())).isFalse();
  }

  // -----------------------------------------------------------------------------------------
  // Fixture
  // -----------------------------------------------------------------------------------------

  /** One document row; {@code organizationId} is the one its library belongs to. */
  private record DocumentRef(UUID id, UUID libraryId, UUID organizationId) {}

  private record Fixture(
      DocumentRef parent,
      DocumentRef ownChild,
      DocumentRef parentlessChild,
      DocumentRef foreignChild) {}

  /**
   * Two organizations, each with a user and a library: {@code parent} and its two same-organization
   * children live in the first, {@code foreignChild} in the second. Only {@code parent} is inserted
   * here; the children are inserted by the test that needs them, since inserting them is what the
   * constraint under test judges.
   */
  private Fixture seedTwoOrganizations() throws SQLException {
    UUID organizationA = insertOrganization("Organisation A");
    UUID organizationB = insertOrganization("Organisation B");
    UUID libraryA = insertLibrary(organizationA, "Bibliothek A");
    UUID libraryB = insertLibrary(organizationB, "Bibliothek B");

    DocumentRef parent = new DocumentRef(UUID.randomUUID(), libraryA, organizationA);
    insertDocument(parent, null);
    return new Fixture(
        parent,
        new DocumentRef(UUID.randomUUID(), libraryA, organizationA),
        new DocumentRef(UUID.randomUUID(), libraryA, organizationA),
        new DocumentRef(UUID.randomUUID(), libraryB, organizationB));
  }

  private UUID insertOrganization(String name) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement("INSERT INTO organizations (id, name) VALUES (?, ?)")) {
      statement.setObject(1, id);
      statement.setString(2, name);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertLibrary(UUID organizationId, String name) throws SQLException {
    UUID ownerId = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, ?, ?)")) {
      statement.setObject(1, ownerId);
      statement.setString(2, "subject-" + ownerId);
      statement.setString(3, "https://issuer.example");
      statement.setObject(4, organizationId);
      statement.executeUpdate();
    }
    UUID libraryId = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
                + " owner_user_id, visibility, source_type) VALUES (?, ?, ?, 'USER', ?, 'PRIVATE',"
                + " 'UPLOAD')")) {
      statement.setObject(1, libraryId);
      statement.setObject(2, organizationId);
      statement.setString(3, name);
      statement.setObject(4, ownerId);
      statement.executeUpdate();
    }
    return libraryId;
  }

  private void insertDocument(DocumentRef document, DocumentRef parent) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO documents (id, file_name, file_path, library_id, organization_id,"
                + " parent_document_id) VALUES (?, ?, ?, ?, ?, ?)")) {
      statement.setObject(1, document.id());
      statement.setString(2, document.id() + ".pdf");
      statement.setString(3, "/bestand/" + document.id() + ".pdf");
      statement.setObject(4, document.libraryId());
      statement.setObject(5, document.organizationId());
      statement.setObject(6, parent == null ? null : parent.id());
      statement.executeUpdate();
    }
  }

  private void deleteDocument(UUID id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM documents WHERE id = ?")) {
      statement.setObject(1, id);
      statement.executeUpdate();
    }
  }

  private boolean documentExists(UUID id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM documents WHERE id = ?")) {
      statement.setObject(1, id);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next();
      }
    }
  }

  /** {@code confdeltype} of the named foreign key - {@code a} = NO ACTION, {@code r} = RESTRICT. */
  private String deleteRule(String constraintName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT confdeltype FROM pg_constraint WHERE conname = ? AND contype = 'f'")) {
      statement.setString(1, constraintName);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).as("foreign key %s must exist", constraintName).isTrue();
        return rs.getString(1);
      }
    }
  }

  /**
   * The column names behind one side of a foreign key, in key order: {@code conkey}/{@code
   * conrelid} for the referencing side, {@code confkey}/{@code confrelid} for the referenced one.
   */
  private List<String> foreignKeyColumns(
      String table, String constraintName, String keyColumn, String relationColumn)
      throws SQLException {
    return constraintColumns(table, constraintName, 'f', keyColumn, relationColumn);
  }

  private List<String> uniqueConstraintColumns(String table, String constraintName)
      throws SQLException {
    return constraintColumns(table, constraintName, 'u', "conkey", "conrelid");
  }

  private List<String> constraintColumns(
      String table,
      String constraintName,
      char constraintType,
      String keyColumn,
      String relationColumn)
      throws SQLException {
    List<String> columns = new ArrayList<>();
    String sql =
        "SELECT a.attname FROM pg_constraint c"
            + " JOIN unnest(c."
            + keyColumn
            + ") WITH ORDINALITY AS k(attnum, ord) ON true"
            + " JOIN pg_attribute a ON a.attrelid = c."
            + relationColumn
            + " AND a.attnum = k.attnum"
            + " WHERE c.conrelid = ?::regclass AND c.conname = ? AND c.contype = ?::\"char\""
            + " ORDER BY k.ord";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, table);
      statement.setString(2, constraintName);
      statement.setString(3, String.valueOf(constraintType));
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          columns.add(rs.getString(1));
        }
      }
    }
    assertThat(columns).as("constraint %s on %s must exist", constraintName, table).isNotEmpty();
    return columns;
  }
}
