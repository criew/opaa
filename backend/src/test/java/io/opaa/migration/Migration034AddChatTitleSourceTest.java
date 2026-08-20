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
 * Applies Liquibase changelog 034 in isolation against a database built from the real, versioned
 * changelog through changeSet 033 - the same pattern {@code
 * Migration033DropKnowledgeLibrariesPersonalFlagTest} follows, with {@code
 * test-master-through-033.yaml} as the pre-migration fixture (see that fixture's own comment).
 *
 * <p>#557/#561: {@code chats.title_source} distinguishes an LLM-derived/prefix-fallback title
 * (GENERATED) from one the user set explicitly (CUSTOM) - see {@code TitleSource}'s Javadoc. This
 * class proves both changeSets in {@code 034-add-chat-title-source.yaml}: the column itself
 * (default GENERATED, {@code CHECK} constraint) and the #561 review-nit backfill that reclassifies
 * a messageless-but-titled chat as CUSTOM, since only an explicit title at creation could have
 * produced that combination before this column existed.
 */
class Migration034AddChatTitleSourceTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-033.yaml";
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
  void addsTheTitleSourceColumnDefaultingToGenerated() throws Exception {
    UUID author = insertUser();
    UUID space = insertSpace(author);
    UUID chat = insertChat(space, author, null);

    applyChangelog034();

    assertThat(hasTitleSourceColumn()).isTrue();
    assertThat(titleSource(chat)).isEqualTo("GENERATED");
  }

  @Test
  void titleSourceIsRestrictedToGeneratedAndCustom() throws Exception {
    applyChangelog034();
    UUID author = insertUser();
    UUID space = insertSpace(author);
    UUID chat = insertChat(space, author, null);

    assertThatThrownBy(
            () -> {
              try (Statement statement = connection.createStatement()) {
                statement.execute(
                    "UPDATE chats SET title_source = 'BOGUS' WHERE id = '" + chat + "'");
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_chats_title_source");
  }

  /**
   * #561 review nit: a messageless chat that already has a title cannot have gotten it from the
   * (message-triggered) prefix fallback - only an explicit title at creation explains it.
   */
  @Test
  void backfillReclassifiesATitledMessagelessChatAsCustom() throws Exception {
    UUID author = insertUser();
    UUID space = insertSpace(author);
    UUID chat = insertChat(space, author, "Vom Nutzer vergebener Titel");

    applyChangelog034();

    assertThat(titleSource(chat)).isEqualTo("CUSTOM");
  }

  /**
   * The ambiguous case this migration deliberately does not try to resolve (see the changelog's
   * top-level comment): a titled chat that already has messages could be either an explicit title
   * or the pre-#557 mechanical prefix fallback - left GENERATED.
   */
  @Test
  void backfillLeavesATitledChatWithMessagesAsGenerated() throws Exception {
    UUID author = insertUser();
    UUID space = insertSpace(author);
    UUID chat = insertChat(space, author, "Wie hoch ist die Rueckstellung fuer Altlasten?");
    insertMessage(chat, 0, "USER", "Wie hoch ist die Rueckstellung fuer Altlasten?");

    applyChangelog034();

    assertThat(titleSource(chat)).isEqualTo("GENERATED");
  }

  @Test
  void backfillLeavesAnUntitledChatAsGenerated() throws Exception {
    UUID author = insertUser();
    UUID space = insertSpace(author);
    UUID chat = insertChat(space, author, null);

    applyChangelog034();

    assertThat(titleSource(chat)).isEqualTo("GENERATED");
  }

  @Test
  void rollbackRestoresThePreMigrationSchema() throws Exception {
    applyChangelog034();
    assertThat(hasTitleSourceColumn()).isTrue();

    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/034-add-chat-title-source.yaml",
            new ClassLoaderResourceAccessor(),
            liquibaseDatabase(connection));
    liquibase.rollback(2, (String) null);
    connection.setAutoCommit(true);

    assertThat(hasTitleSourceColumn()).isFalse();
  }

  private void applyChangelog034() throws Exception {
    applyChangelog(connection, "db/changelog/changes/034-add-chat-title-source.yaml");
  }

  private boolean hasTitleSourceColumn() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM information_schema.columns"
                    + " WHERE table_name = 'chats' AND column_name = 'title_source'")) {
      result.next();
      return result.getInt(1) == 1;
    }
  }

  private String titleSource(UUID chatId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery("SELECT title_source FROM chats WHERE id = '" + chatId + "'")) {
      result.next();
      return result.getString(1);
    }
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

  private UUID insertChat(UUID spaceId, UUID authorId, String title) throws SQLException {
    UUID id = UUID.randomUUID();
    String titleLiteral = title == null ? "NULL" : "'" + title + "'";
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO chats (id, space_id, author_id, organization_id, title, use_knowledge,"
              + " status, created_at, updated_at) "
              + "VALUES ('"
              + id
              + "', '"
              + spaceId
              + "', '"
              + authorId
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', "
              + titleLiteral
              + ", true, 'PRIVATE', now(), now())");
    }
    return id;
  }

  private void insertMessage(UUID chatId, int sequence, String role, String content)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO chat_messages (id, chat_id, sequence, role, content, created_at) "
              + "VALUES ('"
              + UUID.randomUUID()
              + "', '"
              + chatId
              + "', "
              + sequence
              + ", '"
              + role
              + "', '"
              + content
              + "', now())");
    }
  }
}
