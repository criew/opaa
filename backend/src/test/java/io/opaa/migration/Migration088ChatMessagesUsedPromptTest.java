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
 * Delta tests for {@code changes/088-chat-messages-used-prompt.yaml} (#1903): a question's message
 * keeps the prompt it was built from as an id and a title snapshot - both or neither, on a user
 * message only, with no foreign key to the prompt and no index that would make the column a query
 * axis across conversations.
 */
class Migration088ChatMessagesUsedPromptTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/088-chat-messages-used-prompt.yaml";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-087.yaml";
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
  void theFixtureChainDoesNotYetKnowTheColumns() throws Exception {
    assertThat(columnExists("used_prompt_id")).isFalse();
    assertThat(columnExists("used_prompt_title")).isFalse();
  }

  /** Rows written before the changeset stay as they are: no prompt. */
  @Test
  void anExistingMessageKeepsNoPrompt() throws Exception {
    UUID chat = seedChat();
    UUID message = insertMessage(chat, 0, "USER", null, null);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(string("SELECT used_prompt_id::text FROM chat_messages WHERE id = ?", message))
        .isNull();
    assertThat(string("SELECT used_prompt_title FROM chat_messages WHERE id = ?", message))
        .isNull();
  }

  @Test
  void aUserMessageCarriesIdAndTitleTogether() throws Exception {
    UUID chat = seedChat();
    applyChangelog(connection, CHANGELOG_PATH);
    UUID prompt = UUID.randomUUID();

    UUID message = insertMessage(chat, 0, "USER", prompt, "Zusammenfassung");

    assertThat(string("SELECT used_prompt_id::text FROM chat_messages WHERE id = ?", message))
        .isEqualTo(prompt.toString());
    assertThat(string("SELECT used_prompt_title FROM chat_messages WHERE id = ?", message))
        .isEqualTo("Zusammenfassung");
    assertThatThrownBy(() -> insertMessage(chat, 1, "USER", UUID.randomUUID(), null))
        .hasMessageContaining("chk_chat_messages_used_prompt");
    assertThatThrownBy(() -> insertMessage(chat, 2, "USER", null, "Zusammenfassung"))
        .hasMessageContaining("chk_chat_messages_used_prompt");
  }

  @Test
  void anAnswerNeverCarriesAPrompt() throws Exception {
    UUID chat = seedChat();
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(() -> insertMessage(chat, 1, "ASSISTANT", UUID.randomUUID(), "Titel"))
        .hasMessageContaining("chk_chat_messages_used_prompt");
    assertThatCode(() -> insertMessage(chat, 2, "ASSISTANT", null, null))
        .doesNotThrowAnyException();
  }

  /**
   * The id is a snapshot, not a reference: a prompt that no longer exists is accepted, so deleting
   * a prompt never touches the history - and nothing indexes the column.
   */
  @Test
  void theIdReferencesNoPromptAndIsNotIndexed() throws Exception {
    UUID chat = seedChat();
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatCode(() -> insertMessage(chat, 0, "USER", UUID.randomUUID(), "Gelöscht"))
        .doesNotThrowAnyException();
    assertThat(
            count(
                "SELECT count(*) FROM information_schema.key_column_usage"
                    + " WHERE table_schema = current_schema() AND table_name = 'chat_messages'"
                    + " AND column_name = 'used_prompt_id'"))
        .as("no key of any kind covers the column")
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema()"
                    + " AND tablename = 'chat_messages' AND indexdef LIKE '%used_prompt%'"))
        .as("no index makes the prompt a query axis across conversations")
        .isZero();
  }

  /**
   * Standing guard: every test here applies {@link #CHANGELOG_PATH} itself, so a changelog missing
   * from {@code db.changelog-master.yaml} would still pass every assertion above.
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

  private UUID seedChat() throws Exception {
    UUID organization = UUID.randomUUID();
    execute(
        "INSERT INTO organizations (id, name) VALUES (?, ?)",
        organization,
        "Organisation " + organization);
    UUID author = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, ?, ?)",
        author,
        "prompt-" + author,
        "https://issuer.example",
        organization);
    UUID space = UUID.randomUUID();
    execute(
        "INSERT INTO spaces (id, name, owner_id, organization_id) VALUES (?, ?, ?, ?)",
        space,
        "Raum " + space,
        author,
        organization);
    UUID chat = UUID.randomUUID();
    execute(
        "INSERT INTO chats (id, space_id, author_id, organization_id) VALUES (?, ?, ?, ?)",
        chat,
        space,
        author,
        organization);
    return chat;
  }

  private UUID insertMessage(UUID chat, int sequence, String role, UUID prompt, String title)
      throws SQLException {
    UUID message = UUID.randomUUID();
    execute(
        "INSERT INTO chat_messages (id, chat_id, sequence, role, content"
            + (prompt == null && title == null ? "" : ", used_prompt_id, used_prompt_title")
            + ") VALUES (?, ?, ?, ?, 'Text'"
            + (prompt == null && title == null ? "" : ", ?, ?")
            + ")",
        prompt == null && title == null
            ? new Object[] {message, chat, sequence, role}
            : new Object[] {message, chat, sequence, role, prompt, title});
    return message;
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  private String string(String sql, Object parameter) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }

  private long count(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private boolean columnExists(String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema()"
                + " AND table_name = 'chat_messages' AND column_name = ?")) {
      statement.setString(1, column);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }
}
