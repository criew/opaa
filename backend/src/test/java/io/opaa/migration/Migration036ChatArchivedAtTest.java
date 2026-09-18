package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/036-chat-personal-marks-archived-at.yaml} (#1769): the chat
 * archive as a second personal mark next to the pin, never both at once.
 */
class Migration036ChatArchivedAtTest extends AbstractMigrationTest {

  private static final String PREVIOUS_CHANGELOG_PATH =
      "db/changelog/changes/035-create-chat-personal-marks.yaml";
  private static final String CHANGELOG_PATH =
      "db/changelog/changes/036-chat-personal-marks-archived-at.yaml";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    applyChangelog(connection, PREVIOUS_CHANGELOG_PATH);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheChangesetTheTableHasNoArchiveColumn() throws Exception {
    assertThat(columnExists("chat_personal_marks", "archived_at")).isFalse();
  }

  @Test
  void theChangesetAddsTheColumnConstraintAndIndex() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnExists("chat_personal_marks", "archived_at")).isTrue();
    assertThat(constraintExists("chk_chat_personal_marks_not_pinned_and_archived")).isTrue();
    assertThat(indexDefinition("idx_chat_personal_marks_user_archived_at"))
        .contains("(user_id, archived_at DESC)")
        .contains("WHERE (archived_at IS NOT NULL)");
    assertThat(columnExists("chats", "archived_at")).isFalse();
  }

  /** A pin written before the changeset stays a pin: the new column starts empty. */
  @Test
  void anExistingPinSurvivesUnarchived() throws Exception {
    Fixture fixture = seedChat();
    Instant pinnedAt = Instant.parse("2026-09-18T08:00:00Z");
    execute(
        "INSERT INTO chat_personal_marks (chat_id, user_id, pinned_at) VALUES (?, ?, ?)",
        fixture.chatId(),
        fixture.author(),
        Timestamp.from(pinnedAt));

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(timestamp("pinned_at", fixture)).isEqualTo(Timestamp.from(pinnedAt));
    assertThat(timestamp("archived_at", fixture)).isNull();
  }

  @Test
  void anArchivedMarkCanBeStored() throws Exception {
    Fixture fixture = seedChat();
    applyChangelog(connection, CHANGELOG_PATH);
    Instant archivedAt = Instant.parse("2026-09-18T09:00:00Z");

    execute(
        "INSERT INTO chat_personal_marks (chat_id, user_id, archived_at) VALUES (?, ?, ?)",
        fixture.chatId(),
        fixture.author(),
        Timestamp.from(archivedAt));

    assertThat(timestamp("archived_at", fixture)).isEqualTo(Timestamp.from(archivedAt));
  }

  @Test
  void aChatCannotBePinnedAndArchivedAtOnce() throws Exception {
    Fixture fixture = seedChat();
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO chat_personal_marks (chat_id, user_id, pinned_at, archived_at)"
                        + " VALUES (?, ?, now(), now())",
                    fixture.chatId(),
                    fixture.author()))
        .hasMessageContaining("chk_chat_personal_marks_not_pinned_and_archived");
  }

  /** See {@code Migration035ChatPersonalMarksTest#theChangelogIsReferencedByTheMasterChangelog}. */
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

  private record Fixture(UUID author, UUID chatId) {}

  private Fixture seedChat() throws SQLException {
    UUID organization = UUID.randomUUID();
    execute(
        "INSERT INTO organizations (id, name) VALUES (?, ?)",
        organization,
        "Organisation " + organization);
    UUID author = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, ?, ?)",
        author,
        "archive-" + author,
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
    return new Fixture(author, chatId);
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  private Timestamp timestamp(String column, Fixture fixture) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT " + column + " FROM chat_personal_marks WHERE chat_id = ? AND user_id = ?")) {
      statement.setObject(1, fixture.chatId());
      statement.setObject(2, fixture.author());
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getTimestamp(1);
      }
    }
  }

  private boolean columnExists(String table, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema()"
                + " AND table_name = ? AND column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private boolean constraintExists(String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM pg_constraint WHERE conname = ?")) {
      statement.setString(1, name);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
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
