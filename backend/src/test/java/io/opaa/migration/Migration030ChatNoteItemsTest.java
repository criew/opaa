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
 * Delta tests for {@code changes/030-chat-note-items.yaml} (#1487): the Gesprächsnotiz table does
 * not exist in {@code db/changelog/changes/001-baseline.yaml} and is created here, with the
 * organization boundary rule (#390) implemented the way {@code chat_library_references} implements
 * it - a BEFORE INSERT trigger derives {@code organization_id} from the row's own {@code chat_id},
 * and a composite foreign key onto {@code chats(id, organization_id)} enforces it.
 */
class Migration030ChatNoteItemsTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH = "db/changelog/changes/030-chat-note-items.yaml";

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
    assertThat(tableExists("chat_note_items")).isFalse();
  }

  @Test
  void theChangesetCreatesTheTableWithItsConstraints() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(tableExists("chat_note_items")).isTrue();
    assertThat(constraintExists("chat_note_items_pkey")).isTrue();
    assertThat(constraintExists("uk_chat_note_items_chat_position")).isTrue();
    assertThat(constraintExists("chk_chat_note_items_kind")).isTrue();
    assertThat(constraintExists("chk_chat_note_items_position")).isTrue();
    assertThat(constraintExists("fk_chat_note_items_chat_organization")).isTrue();
    assertThat(indexExists("idx_chat_note_items_chat_id")).isTrue();
  }

  /**
   * The point of the composite key: it can only exist with a unique key on {@code (id,
   * organization_id)} of {@code chats}, and it is what makes a cross-organization note point
   * unrepresentable rather than merely unwritten by the application.
   */
  @Test
  void theChatKeyBindsTheOrganizationAndCascadesOnDelete() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(foreignKeyDefinition("fk_chat_note_items_chat_organization"))
        .contains("(chat_id, organization_id)")
        .contains("chats(id, organization_id)")
        .contains("ON DELETE CASCADE");
  }

  @Test
  void theTriggerDerivesTheOrganizationFromTheChatSoTheApplicationNeverNamesIt() throws Exception {
    Fixture fixture = seedChat();
    applyChangelog(connection, CHANGELOG_PATH);

    UUID itemId = insertNoteItem(fixture.chatId(), 0, "Bezugsjahr 2024", "RAHMEN");

    assertThat(organizationOf(itemId)).isEqualTo(fixture.organization());
  }

  /**
   * The organization boundary rule the trigger and the composite key implement together: even an
   * insert that deliberately names a foreign organization is corrected to the chat's own, so the
   * pair can never disagree.
   */
  @Test
  void anExplicitlyForeignOrganizationIsOverwrittenByTheChatsOwn() throws Exception {
    Fixture fixture = seedChat();
    UUID otherOrganization = insertOrganization();
    applyChangelog(connection, CHANGELOG_PATH);

    UUID itemId = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO chat_note_items (id, chat_id, organization_id, position, text, kind)"
                + " VALUES (?, ?, ?, 0, 'Bezugsjahr 2024', 'RAHMEN')")) {
      statement.setObject(1, itemId);
      statement.setObject(2, fixture.chatId());
      statement.setObject(3, otherOrganization);
      statement.executeUpdate();
    }

    assertThat(organizationOf(itemId)).isEqualTo(fixture.organization());
  }

  @Test
  void twoPointsOfOneChatCannotShareAPosition() throws Exception {
    Fixture fixture = seedChat();
    applyChangelog(CHANGELOG_PATH);
    insertNoteItem(fixture.chatId(), 0, "Bezugsjahr 2024", "RAHMEN");

    assertThatThrownBy(() -> insertNoteItem(fixture.chatId(), 0, "Andere Angabe", "RAHMEN"))
        .hasMessageContaining("uk_chat_note_items_chat_position");
  }

  /** Positions are ordinals, never negative - the cap drops "the oldest", not "the least". */
  @Test
  void aNegativePositionIsRejected() throws Exception {
    Fixture fixture = seedChat();
    applyChangelog(CHANGELOG_PATH);

    assertThatThrownBy(() -> insertNoteItem(fixture.chatId(), -1, "Bezugsjahr 2024", "RAHMEN"))
        .hasMessageContaining("chk_chat_note_items_position");
    assertThatCode(() -> insertNoteItem(fixture.chatId(), 0, "Bezugsjahr 2024", "RAHMEN"))
        .doesNotThrowAnyException();
  }

  @Test
  void anUnknownKindIsRejected() throws Exception {
    Fixture fixture = seedChat();
    applyChangelog(CHANGELOG_PATH);

    assertThatThrownBy(() -> insertNoteItem(fixture.chatId(), 0, "Bezugsjahr 2024", "THEMA"))
        .hasMessageContaining("chk_chat_note_items_kind");
  }

  /** The text column carries the specification's 200-character bound, not only the extraction. */
  @Test
  void aTextLongerThanTwoHundredCharactersIsRejected() throws Exception {
    Fixture fixture = seedChat();
    applyChangelog(CHANGELOG_PATH);

    assertThatCode(() -> insertNoteItem(fixture.chatId(), 0, "a".repeat(200), "RAHMEN"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> insertNoteItem(fixture.chatId(), 1, "a".repeat(201), "RAHMEN"))
        .isInstanceOf(SQLException.class);
  }

  /** The note has the chat's lifecycle: deleting the chat deletes its note. */
  @Test
  void deletingTheChatTakesItsNoteWithIt() throws Exception {
    Fixture fixture = seedChat();
    applyChangelog(CHANGELOG_PATH);
    UUID itemId = insertNoteItem(fixture.chatId(), 0, "Bezugsjahr 2024", "RAHMEN");

    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM chats WHERE id = ?")) {
      statement.setObject(1, fixture.chatId());
      statement.executeUpdate();
    }

    assertThat(noteItemExists(itemId)).isFalse();
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

  private record Fixture(UUID organization, UUID chatId) {}

  private void applyChangelog(String path) throws Exception {
    applyChangelog(connection, path);
  }

  private Fixture seedChat() throws SQLException {
    UUID organization = insertOrganization();
    UUID author = insertUser(organization);
    UUID space = insertSpace(organization, author);
    UUID chatId = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO chats (id, space_id, author_id, organization_id) VALUES (?, ?, ?, ?)")) {
      statement.setObject(1, chatId);
      statement.setObject(2, space);
      statement.setObject(3, author);
      statement.setObject(4, organization);
      statement.executeUpdate();
    }
    return new Fixture(organization, chatId);
  }

  private UUID insertOrganization() throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement("INSERT INTO organizations (id, name) VALUES (?, ?)")) {
      statement.setObject(1, id);
      statement.setString(2, "Organisation " + id);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertUser(UUID organizationId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, ?, ?)")) {
      statement.setObject(1, id);
      statement.setString(2, "note-" + id);
      statement.setString(3, "https://issuer.example");
      statement.setObject(4, organizationId);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertSpace(UUID organizationId, UUID ownerId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO spaces (id, name, owner_id, organization_id) VALUES (?, ?, ?, ?)")) {
      statement.setObject(1, id);
      statement.setString(2, "Raum " + id);
      statement.setObject(3, ownerId);
      statement.setObject(4, organizationId);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertNoteItem(UUID chatId, int position, String text, String kind)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO chat_note_items (id, chat_id, position, text, kind)"
                + " VALUES (?, ?, ?, ?, ?)")) {
      statement.setObject(1, id);
      statement.setObject(2, chatId);
      statement.setInt(3, position);
      statement.setString(4, text);
      statement.setString(5, kind);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID organizationOf(UUID itemId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT organization_id FROM chat_note_items WHERE id = ?")) {
      statement.setObject(1, itemId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getObject(1, UUID.class);
      }
    }
  }

  private boolean noteItemExists(UUID itemId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT 1 FROM chat_note_items WHERE id = ?")) {
      statement.setObject(1, itemId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private boolean tableExists(String table) throws SQLException {
    return scalarExists(
        "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
        table);
  }

  private boolean constraintExists(String name) throws SQLException {
    return scalarExists("SELECT 1 FROM pg_constraint WHERE conname = ?", name);
  }

  private boolean indexExists(String name) throws SQLException {
    return scalarExists(
        "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?", name);
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
