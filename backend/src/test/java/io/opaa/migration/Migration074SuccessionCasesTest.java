package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/074-create-succession-cases.yaml} (#1819, ADR-0036 Entscheidung
 * 6): the record behind an entry of the operational list - when the state was first seen, when it
 * ended, and at most one open record per object and tab.
 */
class Migration074SuccessionCasesTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/074-create-succession-cases.yaml";

  private static final UUID DEFAULT_ORGANIZATION =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
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
  void beforeTheChangesetThereIsNoTable() throws Exception {
    assertThat(tableCount()).isZero();
  }

  @Test
  void oneObjectHasAtMostOneOpenRecordPerTab() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID object = UUID.randomUUID();
    insertCase(object, "OPEN_SUCCESSION", null);

    assertThatThrownBy(() -> insertCase(object, "OPEN_SUCCESSION", null))
        .hasMessageContaining("uk_succession_cases_open");
    // A closed record of the same object is no obstacle - the state may return.
    insertCase(object, "GRANTS_WITHOUT_RECIPIENT", Instant.now());
    assertThat(count("SELECT count(*) FROM succession_cases")).isEqualTo(2);
  }

  /**
   * The state may end more than once over a lifetime, so a closed record never blocks a new one.
   */
  @Test
  void aClosedRecordLeavesRoomForTheStateToReturn() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID object = UUID.randomUUID();
    insertCase(object, "OPEN_SUCCESSION", Instant.now());

    insertCase(object, "OPEN_SUCCESSION", null);

    assertThat(count("SELECT count(*) FROM succession_cases WHERE closed_at IS NULL")).isEqualTo(1);
  }

  /** Who ended it is only meaningful once it ended - the check holds that pair together. */
  @Test
  void anOpenRecordNamesNobodyAsHavingEndedIt() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID user = seedUser();

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO succession_cases (id, organization_id, kind, object_type,"
                        + " object_id, first_seen_at, last_seen_at, closed_by_user_id) VALUES (?,"
                        + " ?, 'OPEN_SUCCESSION', 'GROUP', ?, now(), now(), ?)",
                    UUID.randomUUID(),
                    DEFAULT_ORGANIZATION,
                    UUID.randomUUID(),
                    user))
        .hasMessageContaining("chk_succession_cases_closed");
  }

  /** Protocol, not rights history: the record must never make an account undeletable. */
  @Test
  void theAccountThatEndedAStateStaysDeletable() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID user = seedUser();
    UUID object = UUID.randomUUID();
    execute(
        "INSERT INTO succession_cases (id, organization_id, kind, object_type, object_id,"
            + " first_seen_at, last_seen_at, closed_at, closed_by_user_id) VALUES (?, ?,"
            + " 'OPEN_SUCCESSION', 'SPACE', ?, now(), now(), now(), ?)",
        UUID.randomUUID(),
        DEFAULT_ORGANIZATION,
        object,
        user);

    execute("DELETE FROM users WHERE id = ?", user);

    assertThat(
            count(
                "SELECT count(*) FROM succession_cases WHERE object_id = '"
                    + object
                    + "' AND closed_by_user_id IS NULL"))
        .isEqualTo(1);
  }

  /** The object column carries no foreign key (ADR-0016) - the record outlives the object. */
  @Test
  void anObjectIdNeedsNoExistingObject() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    insertCase(UUID.randomUUID(), "GROUP_WITHOUT_EFFECT", null);

    assertThat(count("SELECT count(*) FROM succession_cases")).isEqualTo(1);
  }

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

  private void insertCase(UUID objectId, String kind, Instant closedAt) throws SQLException {
    execute(
        "INSERT INTO succession_cases (id, organization_id, kind, object_type, object_id,"
            + " first_seen_at, last_seen_at, closed_at) VALUES (?, ?, ?, 'GROUP', ?, now(), now(),"
            + " ?)",
        UUID.randomUUID(),
        DEFAULT_ORGANIZATION,
        kind,
        objectId,
        closedAt == null ? null : java.sql.Timestamp.from(closedAt));
  }

  private UUID seedUser() throws SQLException {
    UUID user = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, email, organization_id) VALUES (?, ?,"
            + " 'https://issuer.example.org', ?, ?)",
        user,
        user.toString(),
        user + "@example.org",
        DEFAULT_ORGANIZATION);
    return user;
  }

  private long tableCount() throws SQLException {
    return count(
        "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema()"
            + " AND table_name = 'succession_cases'");
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      statement.executeUpdate();
    }
  }

  private long count(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }
}
