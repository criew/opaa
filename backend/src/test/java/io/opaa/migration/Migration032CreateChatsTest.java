package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Applies Liquibase changelog 032 in isolation against a database built from the real, versioned
 * changelog through changeSet 016 - the same {@code test-master-through-016.yaml} fixture {@code
 * Migration027LibrarySourceTypeAndConfigurationTest} uses, and for the same reason: 032 touches
 * none of the audit-log infrastructure changelogs 017-023 introduce (in particular the {@code
 * opaa_audit_owner} role dance 022 requires), and spaces, users, organizations and
 * knowledge_libraries - the tables 032 references - all already exist by changeSet 012, well within
 * the 016 fixture. {@code connection.setAutoCommit(true)} is called after every {@code
 * liquibase.update(...)} call, per the package Javadoc's mandatory teardown pattern.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration032CreateChatsTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private Connection connection;
  private Database database;

  @BeforeEach
  void setUp() throws Exception {
    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    database =
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(new JdbcConnection(connection));

    Liquibase liquibase =
        new Liquibase(
            "db/changelog/test-master-through-016.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
    connection.setAutoCommit(true);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.setAutoCommit(true);
    try (Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA public CASCADE");
      statement.execute("CREATE SCHEMA public");
    }
    connection.close();
  }

  @Test
  void chatBelongsToExactlyOneSpaceAndAuthor() throws Exception {
    applyChangelog032();

    UUID author = insertUser();
    UUID space = insertSpace(author);
    UUID chat = UUID.randomUUID();
    insertChat(chat, space, author);

    assertThat(chatExists(chat)).isTrue();
    assertThat(columnValue("chats", chat, "space_id")).isEqualTo(space.toString());
    assertThat(columnValue("chats", chat, "author_id")).isEqualTo(author.toString());
    assertThat(columnValue("chats", chat, "status")).isEqualTo("PRIVATE");
    assertThat(columnValue("chats", chat, "use_knowledge")).isEqualTo("t");
  }

  @Test
  void deletingTheSpaceCascadesToItsChats() throws Exception {
    applyChangelog032();

    UUID author = insertUser();
    UUID space = insertSpace(author);
    UUID chat = UUID.randomUUID();
    insertChat(chat, space, author);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM spaces WHERE id = '" + space + "'");
    }

    assertThat(chatExists(chat)).isFalse();
  }

  @Test
  void deletingTheChatCascadesToItsMessagesAndLibraryReferences() throws Exception {
    applyChangelog032();

    UUID author = insertUser();
    UUID space = insertSpace(author);
    UUID library = insertLibrary(author);
    UUID chat = UUID.randomUUID();
    insertChat(chat, space, author);
    insertLibraryReference(chat, library);
    UUID message = UUID.randomUUID();
    insertMessage(message, chat, "USER", "Wie hoch ist die Rueckstellung?", null);

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM chats WHERE id = '" + chat + "'");
    }

    assertThat(countRows("chat_messages", "chat_id", chat)).isZero();
    assertThat(countRows("chat_library_references", "chat_id", chat)).isZero();
  }

  @Test
  void chatMessageRoleIsRestrictedToUserAndAssistant() throws Exception {
    applyChangelog032();

    UUID author = insertUser();
    UUID space = insertSpace(author);
    UUID chat = UUID.randomUUID();
    insertChat(chat, space, author);

    assertThatThrownBy(() -> insertMessage(UUID.randomUUID(), chat, "SYSTEM", "not allowed", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_chat_messages_role");
  }

  @Test
  void chatMessageSourcesSurviveAsJson() throws Exception {
    applyChangelog032();

    UUID author = insertUser();
    UUID space = insertSpace(author);
    UUID chat = UUID.randomUUID();
    insertChat(chat, space, author);
    UUID message = UUID.randomUUID();
    String sources =
        "[{\"fileName\": \"readme.md\", \"relevanceScore\": 0.9, \"matchCount\": 1, \"cited\":"
            + " true}]";
    insertMessage(message, chat, "ASSISTANT", "Die Antwort lautet 42.", sources);

    assertThat(columnValue("chat_messages", message, "content"))
        .isEqualTo("Die Antwort lautet 42.");
    assertThat(messageSourcesFileName(message)).isEqualTo("readme.md");
  }

  @Test
  void aChatCannotReferenceANonExistentSpace() throws Exception {
    applyChangelog032();

    UUID author = insertUser();
    assertThatThrownBy(() -> insertChat(UUID.randomUUID(), UUID.randomUUID(), author))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_chats_space");
  }

  private void applyChangelog032() throws Exception {
    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/032-create-chats.yaml",
            new ClassLoaderResourceAccessor(),
            database);
    liquibase.update(new Contexts());
    connection.setAutoCommit(true);
  }

  private UUID insertUser() throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO users (id, subject, issuer, system_role, organization_id, created_at) "
              + "VALUES ('"
              + id
              + "', '"
              + id
              + "', 'test-issuer', 'USER', '"
              + SEEDED_ORGANIZATION_ID
              + "', now())");
    }
    return id;
  }

  private UUID insertSpace(UUID ownerId) throws SQLException {
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
              + SEEDED_ORGANIZATION_ID
              + "', now(), now())");
    }
    return id;
  }

  private UUID insertLibrary(UUID ownerId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
              + " owner_user_id, visibility, listed, personal, created_at, updated_at) "
              + "VALUES ('"
              + id
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'Bibliothek', 'USER', '"
              + ownerId
              + "', 'PRIVATE', false, false, now(), now())");
    }
    return id;
  }

  private void insertChat(UUID id, UUID spaceId, UUID authorId) throws SQLException {
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
              + SEEDED_ORGANIZATION_ID
              + "', true, 'PRIVATE', now(), now())");
    }
  }

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

  private void insertMessage(UUID id, UUID chatId, String role, String content, String sources)
      throws SQLException {
    String sourcesLiteral = sources == null ? "NULL" : "'" + sources + "'::json";
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO chat_messages (id, chat_id, role, content, sources, created_at) "
              + "VALUES ('"
              + id
              + "', '"
              + chatId
              + "', '"
              + role
              + "', '"
              + content
              + "', "
              + sourcesLiteral
              + ", now())");
    }
  }

  private boolean chatExists(UUID id) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT 1 FROM chats WHERE id = '" + id + "'")) {
      return rs.next();
    }
  }

  private String columnValue(String table, UUID id, String column) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT " + column + " FROM " + table + " WHERE id = '" + id + "'")) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private String messageSourcesFileName(UUID messageId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT sources->0->>'fileName' FROM chat_messages WHERE id = '"
                    + messageId
                    + "'")) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private long countRows(String table, String column, UUID value) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = '" + value + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
