package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies Liquibase changelog 045 in isolation against a database built from the real, versioned
 * changelog through changeSet 044 - the same pattern as {@code Migration019IndexingJobLibraryTest},
 * with {@code test-master-through-044.yaml} as the pre-migration fixture.
 *
 * <p><b>#646: reproduces the bug at the schema level before proving the fix.</b> {@link
 * #beforeTheMigrationTwoLibrariesCanNeverBothHoldFeedStateForTheSameUrl} runs against the
 * pre-migration ({@code through-044}) fixture alone, without applying 045 - the exact defect the
 * issue describes: {@code rss_feed_state} was keyed by {@code feed_url} alone ({@code
 * uk_rss_feed_state_feed_url}, migration 025), so a second library configured with an already-used
 * feed address could never get its own row, and {@code KnowledgeLibraryService#deleteLibrary} left
 * a deleted library's row behind for a later library to collide with. That test fails without 045
 * applied (a real {@link SQLException} on the unique constraint) and is not run again after 045 -
 * the schema itself no longer permits reproducing the old defect once the fix is in place, which is
 * the point. Every other test in this class applies 045 and proves the fixed behavior: every
 * pre-existing row is cleared rather than guess-assigned to a library (PR #665 review, blocking
 * finding 1 - guessing from feed_url would have reproduced #646 one migration later), and a
 * library's own deletion cascades to its own row only.
 */
class Migration045KeyRssFeedStateByLibraryTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";
  private static final String FEED_URL = "https://example.com/feed.xml";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-044.yaml";
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
  void beforeTheMigrationTwoLibrariesCanNeverBothHoldFeedStateForTheSameUrl() throws Exception {
    // Deliberately does *not* call applyChangelog045() - this test proves the bug #646 describes
    // exists in the schema exactly as migration 025 left it, before this issue's fix is applied.
    UUID libraryA = insertRssFeedLibrary(FEED_URL);
    UUID libraryB = insertRssFeedLibrary(FEED_URL);
    insertLegacyFeedState(FEED_URL, "\"etag-a\"");

    // A second library configured with the same feed address can never get its own row - the
    // unique constraint on feed_url alone (uk_rss_feed_state_feed_url) has no library dimension to
    // distinguish libraryA's state from libraryB's. In production this surfaced as libraryB's very
    // first run finding libraryA's stale ETag via RssFeedStateRepository#findByFeedUrl, sending a
    // conditional GET, and ending in a false "unchanged" (304) with zero documents.
    assertThatThrownBy(() -> insertLegacyFeedState(FEED_URL, "\"etag-b\""))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_rss_feed_state_feed_url");

    assertThat(libraryA).isNotNull();
    assertThat(libraryB).isNotNull();
  }

  @Test
  void clearsAPreExistingRowEvenWhenExactlyOneLibraryMatchesItsFeedUrl() throws Exception {
    // PR #665 review, blocking finding 1: an earlier version of this migration backfilled
    // library_id by matching feed_url against knowledge_libraries.source_url - even in this
    // single-match case, that is unsafe in general (a second library could always have used the
    // same address in the past) and this migration deliberately never distinguishes "exactly one
    // match" from "several" - every pre-existing row is cleared unconditionally instead.
    insertRssFeedLibrary(FEED_URL);
    insertLegacyFeedState(FEED_URL, "\"etag-a\"");

    applyChangelog045();

    assertThat(feedStateRowCount(FEED_URL)).isZero();
  }

  @Test
  void clearsAPreExistingRowWhenNoLiveLibraryClaimsIt() throws Exception {
    // No knowledge_libraries row references this feed_url at all - e.g. the library that once did
    // was deleted (pre-fix, deleteLibrary left the row behind) or moved to a different sourceUrl.
    insertLegacyFeedState(FEED_URL, "\"etag-orphaned\"");

    applyChangelog045();

    assertThat(feedStateRowCount(FEED_URL)).isZero();
  }

  @Test
  void clearsAPreExistingRowWhenTwoDifferentLibrariesOnceUsedTheSameFeedUrl() throws Exception {
    // The exact scenario blocking finding 1 warned about: library A was deleted (pre-fix, leaving
    // its row behind) and library B was later configured with the same feed_url. A backfill that
    // matches by feed_url would non-deterministically attach the leftover row - carrying A's stale
    // ETag - to B, reproducing #646's false-304 defect one migration later. Clearing the table
    // outright never has this ambiguity.
    UUID libraryB = insertRssFeedLibrary(FEED_URL);
    insertLegacyFeedState(FEED_URL, "\"etag-from-deleted-library-a\"");

    applyChangelog045();

    assertThat(feedStateRowCount(FEED_URL)).isZero();
    assertThat(libraryB).isNotNull();
  }

  @Test
  void afterTheMigrationTwoLibrariesCanEachHoldTheirOwnStateForTheSameFeedUrl() throws Exception {
    applyChangelog045();
    UUID libraryA = insertRssFeedLibrary(FEED_URL);
    UUID libraryB = insertRssFeedLibrary(FEED_URL);

    insertFeedState(libraryA, FEED_URL, "\"etag-a\"");
    insertFeedState(libraryB, FEED_URL, "\"etag-b\"");

    assertThat(feedStateRowCount(FEED_URL)).isEqualTo(2);
  }

  @Test
  void deletingALibraryCascadesToItsOwnRssFeedStateRowOnly() throws Exception {
    applyChangelog045();
    UUID libraryA = insertRssFeedLibrary(FEED_URL);
    UUID libraryB = insertRssFeedLibrary(FEED_URL);
    insertFeedState(libraryA, FEED_URL, "\"etag-a\"");
    insertFeedState(libraryB, FEED_URL, "\"etag-b\"");

    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM knowledge_libraries WHERE id = '" + libraryA + "'");
    }

    // The exact fix #646 asks for: deleting a library now takes its own rss_feed_state row with it
    // (fk_rss_feed_state_library, ON DELETE CASCADE) - a later library reusing libraryA's former
    // feed address can never again find its state. libraryB's own row is untouched.
    assertThat(feedStateExists(libraryA, FEED_URL)).isFalse();
    assertThat(feedStateExists(libraryB, FEED_URL)).isTrue();
  }

  @Test
  void rejectsASecondRowForTheSameLibraryAndFeedUrl() throws Exception {
    applyChangelog045();
    UUID libraryId = insertRssFeedLibrary(FEED_URL);
    insertFeedState(libraryId, FEED_URL, "\"etag-a\"");

    assertThatThrownBy(() -> insertFeedState(libraryId, FEED_URL, "\"etag-a-again\""))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_rss_feed_state_library_feed_url");
  }

  @Test
  void rollbackDropsTheLibraryIdColumnAndRestoresTheFeedUrlOnlyUniqueConstraint() throws Exception {
    applyChangelog045();
    assertThat(hasLibraryIdColumn()).isTrue();

    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/045-key-rss-feed-state-by-library.yaml",
            new ClassLoaderResourceAccessor(),
            liquibaseDatabase(connection));
    liquibase.rollback(3, (String) null);
    connection.setAutoCommit(true);

    assertThat(hasLibraryIdColumn()).isFalse();
    insertLegacyFeedState(FEED_URL, "\"etag-after-rollback\"");
    assertThatThrownBy(() -> insertLegacyFeedState(FEED_URL, "\"etag-again\""))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_rss_feed_state_feed_url");
  }

  private void applyChangelog045() throws Exception {
    applyChangelog(connection, "db/changelog/changes/045-key-rss-feed-state-by-library.yaml");
  }

  private UUID insertRssFeedLibrary(String feedUrl) throws SQLException {
    UUID ownerId = insertUser();
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries "
              + "(id, organization_id, name, owner_type, owner_user_id, owner_group_id,"
              + " visibility, listed, source_type, source_url, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'Feed-Bibliothek "
              + id
              + "', 'USER', '"
              + ownerId
              + "', NULL, 'PRIVATE', false, 'RSS_FEED', '"
              + feedUrl
              + "', now(), now())");
    }
    return id;
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

  /** Inserts a row the way pre-#646 application code did: no library_id column exists yet. */
  private void insertLegacyFeedState(String feedUrl, String etag) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO rss_feed_state (id, feed_url, etag, updated_at) VALUES ('"
              + id
              + "', '"
              + feedUrl
              + "', '"
              + etag
              + "', '"
              + Instant.now()
              + "')");
    }
  }

  private void insertFeedState(UUID libraryId, String feedUrl, String etag) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO rss_feed_state (id, library_id, feed_url, etag, updated_at) VALUES ('"
              + id
              + "', '"
              + libraryId
              + "', '"
              + feedUrl
              + "', '"
              + etag
              + "', '"
              + Instant.now()
              + "')");
    }
  }

  private int feedStateRowCount(String feedUrl) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM rss_feed_state WHERE feed_url = '" + feedUrl + "'")) {
      result.next();
      return result.getInt(1);
    }
  }

  private boolean feedStateExists(UUID libraryId, String feedUrl) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM rss_feed_state WHERE library_id = '"
                    + libraryId
                    + "' AND feed_url = '"
                    + feedUrl
                    + "'")) {
      result.next();
      return result.getInt(1) > 0;
    }
  }

  private boolean hasLibraryIdColumn() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT count(*) FROM information_schema.columns WHERE table_name ="
                    + " 'rss_feed_state' AND column_name = 'library_id'")) {
      result.next();
      return result.getInt(1) == 1;
    }
  }
}
