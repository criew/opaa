package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta test for {@code changes/021-chats-metadata-filter.yaml} (#1070): the nullable {@code jsonb}
 * column carrying a chat's sticky core-field filter, absent before the changeSet and holding either
 * {@code NULL} or a JSON object after it.
 */
class Migration021ChatsMetadataFilterTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/021-chats-metadata-filter.yaml";
  private static final UUID ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws SQLException {
    connection = connect();
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void addsANullableJsonbColumnThatExistingChatsReadAsNull() throws Exception {
    assertThat(columnType()).as("column absent before the changeSet").isNull();
    UUID userId = insertUser();
    UUID chatId = insertChat(insertSpace(userId), userId);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnType()).isEqualTo("jsonb");
    assertThat(metadataFilterOf(chatId)).isNull();
    setMetadataFilter(chatId, "{\"documentTypes\": [\"DIENSTANWEISUNG\"]}");
    assertThat(metadataFilterOf(chatId)).contains("DIENSTANWEISUNG");
  }

  private String columnType() throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT data_type FROM information_schema.columns WHERE table_name = 'chats' AND"
                + " column_name = 'metadata_filter'")) {
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  private String metadataFilterOf(UUID chatId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT metadata_filter::text FROM chats WHERE id = ?")) {
      statement.setObject(1, chatId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getString(1);
      }
    }
  }

  private void setMetadataFilter(UUID chatId, String json) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("UPDATE chats SET metadata_filter = ?::jsonb WHERE id = ?")) {
      statement.setString(1, json);
      statement.setObject(2, chatId);
      statement.executeUpdate();
    }
  }

  private UUID insertUser() throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, 'test', ?)")) {
      statement.setObject(1, id);
      statement.setString(2, "author-" + id);
      statement.setObject(3, ORGANIZATION_ID);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertSpace(UUID ownerId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO spaces (id, organization_id, name, owner_id) VALUES (?, ?, ?, ?)")) {
      statement.setObject(1, id);
      statement.setObject(2, ORGANIZATION_ID);
      statement.setString(3, "Space " + id);
      statement.setObject(4, ownerId);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertChat(UUID spaceId, UUID authorId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO chats (id, space_id, author_id, organization_id, use_knowledge, status,"
                + " title_source) VALUES (?, ?, ?, ?, true, 'PRIVATE', 'GENERATED')")) {
      statement.setObject(1, id);
      statement.setObject(2, spaceId);
      statement.setObject(3, authorId);
      statement.setObject(4, ORGANIZATION_ID);
      statement.executeUpdate();
    }
    return id;
  }
}
