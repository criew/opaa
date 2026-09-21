package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

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
 * Delta tests for {@code changes/075-create-succession-reviews.yaml} (#1819, ADR-0036 Entscheidung
 * 6): the Sichtungsvermerk - a part of its record, and no hold on the account that wrote it.
 */
class Migration075SuccessionReviewsTest extends AbstractMigrationTest {

  private static final String CASES_PATH = "db/changelog/changes/074-create-succession-cases.yaml";
  private static final String CHANGELOG_PATH =
      "db/changelog/changes/075-create-succession-reviews.yaml";

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
    applyChangelog(connection, CASES_PATH);

    assertThat(tableCount()).isZero();
  }

  /** The Vermerk is part of its record, not a record of its own. */
  @Test
  void aReviewGoesWithItsCase() throws Exception {
    applyAll();
    UUID caseId = seedCase();
    seedReview(caseId, seedUser());

    execute("DELETE FROM succession_cases WHERE id = ?", caseId);

    assertThat(count("SELECT count(*) FROM succession_reviews")).isZero();
  }

  /** Protocol, not rights history: who reviewed must never make an account undeletable. */
  @Test
  void theReviewingAccountStaysDeletable() throws Exception {
    applyAll();
    UUID caseId = seedCase();
    UUID user = seedUser();
    seedReview(caseId, user);

    execute("DELETE FROM users WHERE id = ?", user);

    assertThat(count("SELECT count(*) FROM succession_reviews WHERE reviewed_by_user_id IS NULL"))
        .isEqualTo(1);
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

  private void applyAll() throws Exception {
    applyChangelog(connection, CASES_PATH);
    applyChangelog(connection, CHANGELOG_PATH);
  }

  private UUID seedCase() throws SQLException {
    UUID caseId = UUID.randomUUID();
    execute(
        "INSERT INTO succession_cases (id, organization_id, kind, object_type, object_id,"
            + " first_seen_at, last_seen_at) VALUES (?, ?, 'OPEN_SUCCESSION', 'GROUP', ?, now(),"
            + " now())",
        caseId,
        DEFAULT_ORGANIZATION,
        UUID.randomUUID());
    return caseId;
  }

  private void seedReview(UUID caseId, UUID userId) throws SQLException {
    execute(
        "INSERT INTO succession_reviews (id, case_id, organization_id, reviewed_at,"
            + " reviewed_by_user_id, reason) VALUES (?, ?, ?, now(), ?, 'weiterhin offen')",
        UUID.randomUUID(),
        caseId,
        DEFAULT_ORGANIZATION,
        userId);
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
            + " AND table_name = 'succession_reviews'");
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
