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
 * Delta tests for {@code changes/037-chat-messages-full-text.yaml} (#1770): a generated German
 * full-text vector over every chat message with a GIN index, computed on insert for rows that
 * already exist too, and gone together with its chat.
 */
class Migration037ChatMessagesFullTextTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/037-chat-messages-full-text.yaml";

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
  void theBaselineDoesNotYetKnowTheColumn() throws Exception {
    assertThat(columnGeneration("chat_messages", "content_tsv")).isNull();
  }

  @Test
  void theChangesetAddsAGeneratedColumnWithAGinIndex() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnGeneration("chat_messages", "content_tsv")).isEqualTo("ALWAYS");
    assertThat(indexDefinition("idx_chat_messages_content_tsv"))
        .contains("USING gin")
        .contains("(content_tsv)");
  }

  /** German stemming, as in the knowledge search: the plural finds the singular and back. */
  @Test
  void aNewMessageIsIndexedWithGermanStemming() throws Exception {
    UUID chatId = seedChat();
    applyChangelog(connection, CHANGELOG_PATH);

    insertMessage(chatId, 0, "Wie viele Widersprüche liegen vor?");

    assertThat(matches(chatId, "Widerspruch")).isTrue();
    assertThat(matches(chatId, "Bescheid")).isFalse();
  }

  /** Rows written before the changeset get their vector when the column is added. */
  @Test
  void anExistingMessageIsIndexedByTheChangeset() throws Exception {
    UUID chatId = seedChat();
    insertMessage(chatId, 0, "Die Fristen des Widerspruchsverfahrens");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(matches(chatId, "Frist")).isTrue();
  }

  @Test
  void theColumnCannotBeWrittenDirectly() throws Exception {
    UUID chatId = seedChat();
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO chat_messages (id, chat_id, sequence, role, content, content_tsv)"
                        + " VALUES (?, ?, 0, 'USER', 'Text', to_tsvector('simple', 'anders'))",
                    UUID.randomUUID(),
                    chatId))
        .hasMessageContaining("content_tsv");
  }

  @Test
  void deletingTheChatTakesItsIndexedMessagesWithIt() throws Exception {
    UUID chatId = seedChat();
    applyChangelog(connection, CHANGELOG_PATH);
    insertMessage(chatId, 0, "Widerspruch gegen den Bescheid");

    execute("DELETE FROM chats WHERE id = ?", chatId);

    assertThat(matches(chatId, "Widerspruch")).isFalse();
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

  private UUID seedChat() throws SQLException {
    UUID organization = UUID.randomUUID();
    execute(
        "INSERT INTO organizations (id, name) VALUES (?, ?)",
        organization,
        "Organisation " + organization);
    UUID author = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, ?, ?)",
        author,
        "search-" + author,
        "https://issuer.example",
        organization);
    UUID space = UUID.randomUUID();
    execute(
        "INSERT INTO spaces (id, name, owner_id, organization_id) VALUES (?, ?, ?, ?)",
        space,
        "Raum " + space,
        author,
        organization);
    UUID chatId = UUID.randomUUID();
    execute(
        "INSERT INTO chats (id, space_id, author_id, organization_id) VALUES (?, ?, ?, ?)",
        chatId,
        space,
        author,
        organization);
    return chatId;
  }

  private void insertMessage(UUID chatId, int sequence, String content) throws SQLException {
    execute(
        "INSERT INTO chat_messages (id, chat_id, sequence, role, content) VALUES (?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        chatId,
        sequence,
        "USER",
        content);
  }

  private boolean matches(UUID chatId, String term) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM chat_messages WHERE chat_id = ?"
                + " AND content_tsv @@ plainto_tsquery('german', ?)")) {
      statement.setObject(1, chatId);
      statement.setString(2, term);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
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

  /** {@code is_generated} of the column, or {@code null} if the column does not exist. */
  private String columnGeneration(String table, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT is_generated FROM information_schema.columns"
                + " WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
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
