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
 * Delta test for {@code changes/006-library-diagnostics-lock.yaml} (#1052): the Diagnosesperre and
 * the one property that decides whether "standardmäßig gesperrt" actually holds - a library that
 * already existed before this changeset ran must come out locked, not unlocked. Postgres fills a
 * {@code NOT NULL DEFAULT true} column for existing rows itself, so no backfill is needed; what
 * {@link #locksEveryLibraryThatAlreadyExistedBeforeTheChangeset} catches by seeding its row
 * <em>before</em> applying the changelog is a future edit to {@code DEFAULT false} or to a nullable
 * column, either of which would leave exactly the Bestände the leitplanke protects (they are the
 * old ones) open.
 */
class Migration006LibraryDiagnosticsLockTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/006-library-diagnostics-lock.yaml";
  private static final UUID ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void locksEveryLibraryThatAlreadyExistedBeforeTheChangeset() throws Exception {
    UUID ownerId = insertUser();
    UUID libraryId = insertLibrary(ownerId);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(diagnosticsLockedOf(libraryId)).isTrue();
  }

  @Test
  void locksANewlyInsertedLibraryByDefault() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID ownerId = insertUser();
    UUID libraryId = insertLibrary(ownerId);

    assertThat(diagnosticsLockedOf(libraryId)).isTrue();
  }

  @Test
  void theColumnIsNotNullable() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND table_name = 'knowledge_libraries' AND column_name ="
                + " 'diagnostics_locked'")) {
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("is_nullable")).isEqualTo("NO");
      }
    }
  }

  private boolean diagnosticsLockedOf(UUID libraryId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT diagnostics_locked FROM knowledge_libraries WHERE id = ?")) {
      statement.setObject(1, libraryId);
      try (ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getBoolean("diagnostics_locked");
      }
    }
  }

  private UUID insertUser() throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, 'test', ?)")) {
      statement.setObject(1, id);
      statement.setString(2, "owner-" + id);
      statement.setObject(3, ORGANIZATION_ID);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertLibrary(UUID ownerId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
                + " owner_user_id, visibility, listed, source_type, created_at, updated_at)"
                + " VALUES (?, ?, 'Personalvorgänge', 'USER', ?, 'PRIVATE', true, 'UPLOAD',"
                + " now(), now())")) {
      statement.setObject(1, id);
      statement.setObject(2, ORGANIZATION_ID);
      statement.setObject(3, ownerId);
      statement.executeUpdate();
    }
    return id;
  }
}
