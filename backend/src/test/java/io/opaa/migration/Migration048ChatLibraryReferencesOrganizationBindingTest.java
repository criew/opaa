package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies Liquibase changelog 048 in isolation against a database built from the real, versioned
 * changelog through changeSet 046 - {@code test-master-through-046.yaml} (see that file's own
 * comment for why it stands in for the usual {@code test-master-through-047.yaml}: migration 047
 * was still being developed in a parallel issue when this test was written).
 *
 * <p><b>#677: reproduces the bug at the schema level before proving the fix.</b> {@link
 * #beforeTheMigrationAChatCanReferenceALibraryFromAnotherOrganization} runs against the
 * pre-migration ({@code through-046}) fixture alone, without applying 048 - the exact defect the
 * issue describes: {@code fk_chat_library_references_chat} and {@code
 * fk_chat_library_references_library} (migration 032) only referenced {@code chats(id)} and {@code
 * knowledge_libraries(id)}, not their composite {@code (id, organization_id)} counterparts, so
 * nothing on the database side stopped a chat from referencing a library in a different
 * organization. That test succeeds where it should fail, which is the bug. Every other test in this
 * class applies 048 and proves the fixed behavior.
 *
 * <p>048 also installs a BEFORE INSERT trigger that derives organization_id from the row's own
 * chat, so every {@code insertLibraryReference} call in this class stays a plain two-column insert
 * (chat_id, library_id) both before and after 048 - exactly like {@code Chat#referencedLibraryIds}
 * (a plain {@code @ElementCollection<UUID>}) never needs to know the column exists either.
 */
class Migration048ChatLibraryReferencesOrganizationBindingTest extends AbstractMigrationTest {

  private static final String ORGANIZATION_A = "00000000-0000-0000-0000-000000000001";
  private static final String ORGANIZATION_B = "00000000-0000-0000-0000-000000000002";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-046.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    insertOrganization(ORGANIZATION_A);
    insertOrganization(ORGANIZATION_B);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheMigrationAChatCanReferenceALibraryFromAnotherOrganization() throws Exception {
    // Deliberately does *not* call applyChangelog048() - this test proves the bug #677 describes
    // exists in the schema exactly as migration 032 left it, before this issue's fix is applied.
    UUID authorInOrganizationA = insertUser(ORGANIZATION_A);
    UUID spaceInOrganizationA = insertSpace(ORGANIZATION_A, authorInOrganizationA);
    UUID chatInOrganizationA =
        insertChat(ORGANIZATION_A, spaceInOrganizationA, authorInOrganizationA);
    UUID libraryInOrganizationB = insertLibrary(ORGANIZATION_B, authorInOrganizationA);

    // fk_chat_library_references_chat and fk_chat_library_references_library only reference
    // chats(id) and knowledge_libraries(id) - neither carries an organization dimension, so this
    // insert succeeds today even though the chat and the library belong to different
    // organizations. That is the defect #677 exists to close.
    insertLibraryReference(chatInOrganizationA, libraryInOrganizationB);

    assertThat(countLibraryReferences(chatInOrganizationA, libraryInOrganizationB)).isEqualTo(1);
  }

  @Test
  void afterTheMigrationAChatCannotReferenceALibraryFromAnotherOrganization() throws Exception {
    applyChangelog048();
    UUID authorInOrganizationA = insertUser(ORGANIZATION_A);
    UUID spaceInOrganizationA = insertSpace(ORGANIZATION_A, authorInOrganizationA);
    UUID chatInOrganizationA =
        insertChat(ORGANIZATION_A, spaceInOrganizationA, authorInOrganizationA);
    UUID libraryInOrganizationB = insertLibrary(ORGANIZATION_B, authorInOrganizationA);

    // The BEFORE INSERT trigger fills organization_id from the chat (organization A) regardless
    // of what is supplied, so this hits the composite foreign keys reference chats(id,
    // organization_id) and knowledge_libraries(id, organization_id) - a reference naming a
    // library in organization B while the chat itself belongs to organization A must violate the
    // library-side constraint, because no such (id, organization_id) pair matches on both sides
    // at once.
    assertThatThrownBy(() -> insertLibraryReference(chatInOrganizationA, libraryInOrganizationB))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_chat_library_references_library_organization");
  }

  @Test
  void afterTheMigrationAChatCanStillReferenceALibraryFromTheSameOrganization() throws Exception {
    applyChangelog048();
    UUID author = insertUser(ORGANIZATION_A);
    UUID space = insertSpace(ORGANIZATION_A, author);
    UUID chat = insertChat(ORGANIZATION_A, space, author);
    UUID library = insertLibrary(ORGANIZATION_A, author);

    insertLibraryReference(chat, library);

    assertThat(countLibraryReferences(chat, library)).isEqualTo(1);
    assertThat(columnValue("chat_library_references", "organization_id", chat, library))
        .isEqualTo(ORGANIZATION_A);
  }

  @Test
  void clearsAPreExistingCrossOrganizationLibraryReferenceInsteadOfFailingTheMigration()
      throws Exception {
    // Deliberately built against the pre-migration schema (single-column foreign keys only), the
    // exact bug #677 describes - so this row can exist at all before 048 runs.
    UUID author = insertUser(ORGANIZATION_A);
    UUID space = insertSpace(ORGANIZATION_A, author);
    UUID chat = insertChat(ORGANIZATION_A, space, author);
    UUID library = insertLibrary(ORGANIZATION_B, author);
    insertLibraryReference(chat, library);

    applyChangelog048();

    assertThat(countLibraryReferences(chat, library)).isZero();
  }

  @Test
  void leavesASameOrganizationLibraryReferenceUntouchedByTheCleanupStepAndBackfillsItsOrganization()
      throws Exception {
    UUID author = insertUser(ORGANIZATION_A);
    UUID space = insertSpace(ORGANIZATION_A, author);
    UUID chat = insertChat(ORGANIZATION_A, space, author);
    UUID library = insertLibrary(ORGANIZATION_A, author);
    insertLibraryReference(chat, library);

    applyChangelog048();

    assertThat(countLibraryReferences(chat, library)).isEqualTo(1);
    assertThat(columnValue("chat_library_references", "organization_id", chat, library))
        .isEqualTo(ORGANIZATION_A);
  }

  @Test
  void deletingTheChatStillCascadesToItsLibraryReferences() throws Exception {
    applyChangelog048();
    UUID author = insertUser(ORGANIZATION_A);
    UUID space = insertSpace(ORGANIZATION_A, author);
    UUID chat = insertChat(ORGANIZATION_A, space, author);
    UUID library = insertLibrary(ORGANIZATION_A, author);
    insertLibraryReference(chat, library);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM chats WHERE id = '" + chat + "'");
    }

    assertThat(countLibraryReferences(chat, library)).isZero();
  }

  @Test
  void deletingTheLibraryStillCascadesToItsChatReferences() throws Exception {
    applyChangelog048();
    UUID author = insertUser(ORGANIZATION_A);
    UUID space = insertSpace(ORGANIZATION_A, author);
    UUID chat = insertChat(ORGANIZATION_A, space, author);
    UUID library = insertLibrary(ORGANIZATION_A, author);
    insertLibraryReference(chat, library);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM knowledge_libraries WHERE id = '" + library + "'");
    }

    assertThat(countLibraryReferences(chat, library)).isZero();
  }

  @Test
  void rollbackRestoresTheSingleColumnForeignKeysAndDropsTheOrganizationColumn() throws Exception {
    applyChangelog048();

    rollbackChangelog048();

    // The composite condition is gone - a cross-organization reference succeeds again, exactly
    // the pre-#677 defect.
    UUID author = insertUser(ORGANIZATION_A);
    UUID space = insertSpace(ORGANIZATION_A, author);
    UUID chat = insertChat(ORGANIZATION_A, space, author);
    UUID library = insertLibrary(ORGANIZATION_B, author);
    insertLibraryReference(chat, library);
    assertThat(countLibraryReferences(chat, library)).isEqualTo(1);

    // But the single-column foreign keys (migration 032) are restored, not merely absent
    // alongside the composite ones: a chat_id or library_id naming a row that does not exist at
    // all must still be rejected by *some* foreign key.
    UUID nonExistentChatId = UUID.randomUUID();
    assertThatThrownBy(() -> insertLibraryReference(nonExistentChatId, library))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_chat_library_references_chat");
  }

  private void applyChangelog048() throws Exception {
    applyChangelog(
        connection, "db/changelog/changes/048-bind-chat-library-references-to-organization.yaml");
  }

  private void rollbackChangelog048() throws Exception {
    // The "6" below is the current changeSet count of 048 (PR #680 review, finding 5) - update it
    // if 048 ever grows another changeSet, or this silently rolls back too few.
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/048-bind-chat-library-references-to-organization.yaml",
            new ClassLoaderResourceAccessor(),
            liquibaseDatabase(connection));
    liquibase.rollback(6, (String) null);
    connection.setAutoCommit(true);
  }

  private void insertOrganization(String id) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO organizations (id, name, created_at) VALUES ('"
              + id
              + "', 'Org "
              + id
              + "', now()) ON CONFLICT (id) DO NOTHING");
    }
  }

  private UUID insertUser(String organizationId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO users (id, subject, issuer, system_role, organization_id, created_at) "
              + "VALUES ('"
              + id
              + "', '"
              + id
              + "', 'test-issuer', 'USER', '"
              + organizationId
              + "', now())");
    }
    return id;
  }

  private UUID insertSpace(String organizationId, UUID ownerId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO spaces "
              + "(id, name, is_default, visibility, owner_id, organization_id, created_at,"
              + " updated_at) "
              + "VALUES ('"
              + id
              + "', 'Fachbereich', false, 'PRIVATE', '"
              + ownerId
              + "', '"
              + organizationId
              + "', now(), now())");
    }
    return id;
  }

  private UUID insertLibrary(String organizationId, UUID ownerId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
              + " owner_user_id, visibility, listed, source_type, created_at, updated_at) "
              + "VALUES ('"
              + id
              + "', '"
              + organizationId
              + "', 'Bibliothek', 'USER', '"
              + ownerId
              + "', 'PRIVATE', false, 'UPLOAD', now(), now())");
    }
    return id;
  }

  private UUID insertChat(String organizationId, UUID spaceId, UUID authorId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO chats (id, space_id, author_id, organization_id, use_knowledge, status,"
              + " created_at, updated_at) "
              + "VALUES ('"
              + id
              + "', '"
              + spaceId
              + "', '"
              + authorId
              + "', '"
              + organizationId
              + "', true, 'PRIVATE', now(), now())");
    }
    return id;
  }

  /**
   * Names only chat_id and library_id, exactly as ChatService inserts a reference today ({@code
   * Chat#referencedLibraryIds} is a plain {@code @ElementCollection<UUID>}, see Chat.java). Before
   * 048, that is the table's entire column set. After 048's trigger
   * (048-add-chat-library-references-organization-trigger) is installed, organization_id is filled
   * in automatically from the referenced chat - this method never needs to name it itself, matching
   * exactly how the application never will either.
   */
  private void insertLibraryReference(UUID chatId, UUID libraryId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO chat_library_references (chat_id, library_id) VALUES ('"
              + chatId
              + "', '"
              + libraryId
              + "')");
    }
  }

  private int countLibraryReferences(UUID chatId, UUID libraryId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM chat_library_references WHERE chat_id = '"
                    + chatId
                    + "' AND library_id = '"
                    + libraryId
                    + "'")) {
      result.next();
      return result.getInt(1);
    }
  }

  private String columnValue(String table, String column, UUID chatId, UUID libraryId)
      throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT "
                    + column
                    + " FROM "
                    + table
                    + " WHERE chat_id = '"
                    + chatId
                    + "' AND library_id = '"
                    + libraryId
                    + "'")) {
      result.next();
      return result.getString(1);
    }
  }
}
