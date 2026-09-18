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
 * Delta tests for {@code changes/035-create-chat-personal-marks.yaml} (#1768): one row of personal
 * marks per person and chat, bound to the chat's organization and removed together with the chat
 * and with the account.
 */
class Migration035ChatPersonalMarksTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/035-create-chat-personal-marks.yaml";

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
  void theBaselineDoesNotYetKnowTheTable() throws Exception {
    assertThat(tableExists("chat_personal_marks")).isFalse();
  }

  @Test
  void theChangesetCreatesTheTableWithItsConstraints() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(tableExists("chat_personal_marks")).isTrue();
    assertThat(constraintExists("chat_personal_marks_pkey")).isTrue();
    assertThat(indexExists("idx_chat_personal_marks_user_id")).isTrue();
    assertThat(foreignKeyDefinition("fk_chat_personal_marks_chat_organization"))
        .contains("(chat_id, organization_id)")
        .contains("chats(id, organization_id)")
        .contains("ON DELETE CASCADE");
    assertThat(foreignKeyDefinition("fk_chat_personal_marks_user_organization"))
        .contains("(user_id, organization_id)")
        .contains("users(id, organization_id)")
        .contains("ON DELETE CASCADE");
  }

  /** A mark is not a column of the chat: the chat table stays as the baseline left it. */
  @Test
  void theChatsTableGainsNoColumn() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnExists("chats", "pinned_at")).isFalse();
  }

  @Test
  void pinnedAtIsOptionalSoARowCanExistWithoutAPin() throws Exception {
    Fixture fixture = seedChat();
    applyChangelog(connection, CHANGELOG_PATH);

    insertMark(fixture.chatId(), fixture.author(), null);

    assertThat(pinnedAt(fixture.chatId(), fixture.author())).isNull();
  }

  @Test
  void onePersonHasAtMostOneRowPerChat() throws Exception {
    Fixture fixture = seedChat();
    applyChangelog(connection, CHANGELOG_PATH);
    insertMark(fixture.chatId(), fixture.author(), Instant.now());

    assertThatThrownBy(() -> insertMark(fixture.chatId(), fixture.author(), Instant.now()))
        .hasMessageContaining("chat_personal_marks_pkey");
  }

  @Test
  void theTriggerDerivesTheOrganizationFromTheChat() throws Exception {
    Fixture fixture = seedChat();
    applyChangelog(connection, CHANGELOG_PATH);

    insertMark(fixture.chatId(), fixture.author(), Instant.now());

    assertThat(organizationOf(fixture.chatId(), fixture.author()))
        .isEqualTo(fixture.organization());
  }

  /** A person of another organization can never hold a mark on this organization's chat. */
  @Test
  void aPersonOfAnotherOrganizationCannotMarkTheChat() throws Exception {
    Fixture fixture = seedChat();
    UUID foreignOrganization = insertOrganization();
    UUID foreignUser = insertUser(foreignOrganization);
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(() -> insertMark(fixture.chatId(), foreignUser, Instant.now()))
        .hasMessageContaining("fk_chat_personal_marks_user_organization");
  }

  @Test
  void deletingTheChatTakesItsMarksWithIt() throws Exception {
    Fixture fixture = seedChat();
    applyChangelog(connection, CHANGELOG_PATH);
    insertMark(fixture.chatId(), fixture.author(), Instant.now());

    execute("DELETE FROM chats WHERE id = ?", fixture.chatId());

    assertThat(markExists(fixture.chatId(), fixture.author())).isFalse();
  }

  /**
   * The mark goes with the account too. The author of a chat cannot be deleted while the chat
   * exists ({@code fk_chats_author_organization} is RESTRICT), so a second person of the same
   * organization carries the mark here - the shape a shared chat will have.
   */
  @Test
  void deletingTheAccountTakesItsMarksWithIt() throws Exception {
    Fixture fixture = seedChat();
    UUID otherPerson = insertUser(fixture.organization());
    applyChangelog(connection, CHANGELOG_PATH);
    insertMark(fixture.chatId(), otherPerson, Instant.now());
    insertMark(fixture.chatId(), fixture.author(), Instant.now());

    execute("DELETE FROM users WHERE id = ?", otherPerson);

    assertThat(markExists(fixture.chatId(), otherPerson)).isFalse();
    assertThat(markExists(fixture.chatId(), fixture.author())).isTrue();
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

  private record Fixture(UUID organization, UUID author, UUID chatId) {}

  private Fixture seedChat() throws SQLException {
    UUID organization = insertOrganization();
    UUID author = insertUser(organization);
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
    return new Fixture(organization, author, chatId);
  }

  private UUID insertOrganization() throws SQLException {
    UUID id = UUID.randomUUID();
    execute("INSERT INTO organizations (id, name) VALUES (?, ?)", id, "Organisation " + id);
    return id;
  }

  private UUID insertUser(UUID organizationId) throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, ?, ?)",
        id,
        "mark-" + id,
        "https://issuer.example",
        organizationId);
    return id;
  }

  private void insertMark(UUID chatId, UUID userId, Instant pinnedAt) throws SQLException {
    execute(
        "INSERT INTO chat_personal_marks (chat_id, user_id, pinned_at) VALUES (?, ?, ?)",
        chatId,
        userId,
        pinnedAt == null ? null : Timestamp.from(pinnedAt));
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  private Timestamp pinnedAt(UUID chatId, UUID userId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT pinned_at FROM chat_personal_marks WHERE chat_id = ? AND user_id = ?")) {
      statement.setObject(1, chatId);
      statement.setObject(2, userId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getTimestamp(1);
      }
    }
  }

  private UUID organizationOf(UUID chatId, UUID userId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT organization_id FROM chat_personal_marks WHERE chat_id = ? AND user_id = ?")) {
      statement.setObject(1, chatId);
      statement.setObject(2, userId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getObject(1, UUID.class);
      }
    }
  }

  private boolean markExists(UUID chatId, UUID userId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM chat_personal_marks WHERE chat_id = ? AND user_id = ?")) {
      statement.setObject(1, chatId);
      statement.setObject(2, userId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private boolean tableExists(String table) throws SQLException {
    return scalarExists(
        "SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema()"
            + " AND table_name = ?",
        table);
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
    return scalarExists("SELECT 1 FROM pg_constraint WHERE conname = ?", name);
  }

  private boolean indexExists(String name) throws SQLException {
    return scalarExists(
        "SELECT 1 FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?", name);
  }

  private String foreignKeyDefinition(String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?")) {
      statement.setString(1, name);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }

  private boolean scalarExists(String sql, String parameter) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, parameter);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }
}
