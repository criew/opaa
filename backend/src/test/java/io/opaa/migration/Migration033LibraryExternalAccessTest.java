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
 * Delta tests for {@code changes/033-library-external-access.yaml} (#1731): the release of a
 * library for Fremdzugaenge as a third reach field, at the library and in the same interval
 * historisation {@code visibility}/{@code listed} already live in. What the schema has to carry,
 * and what these tests pin: a shipped default of "never released", a release that cannot exist
 * without its Befristung, and the two new history causes.
 */
class Migration033LibraryExternalAccessTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/033-library-external-access.yaml";

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
  void theBaselineDoesNotYetKnowTheColumns() throws Exception {
    assertThat(columnExists("knowledge_libraries", "external_access_state")).isFalse();
    assertThat(columnExists("library_visibility_history", "external_access_state")).isFalse();
  }

  @Test
  void theChangesetAddsTheColumnsConstraintsAndIndex() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnExists("knowledge_libraries", "external_access_state")).isTrue();
    assertThat(columnExists("knowledge_libraries", "external_access_expires_at")).isTrue();
    assertThat(columnExists("knowledge_libraries", "external_access_set_at")).isTrue();
    assertThat(columnExists("knowledge_libraries", "external_access_set_by_user_id")).isTrue();
    assertThat(columnExists("knowledge_libraries", "external_access_reminder_sent_at")).isTrue();
    assertThat(columnExists("library_visibility_history", "external_access_state")).isTrue();
    assertThat(columnExists("library_visibility_history", "external_access_expires_at")).isTrue();
    assertThat(constraintExists("chk_knowledge_libraries_external_access_state")).isTrue();
    assertThat(constraintExists("chk_knowledge_libraries_external_access")).isTrue();
    assertThat(constraintExists("chk_library_visibility_history_external_access_state")).isTrue();
    assertThat(indexExists("idx_knowledge_libraries_external_access_expiry")).isTrue();
  }

  /**
   * The acceptance criterion "eine neu angelegte Wissensbibliothek ist nicht freigegeben" held at
   * the schema level, where no application path can get around it - and, in the same assertion, for
   * every library that already existed when the changeset ran.
   */
  @Test
  void everyLibraryIsUnreleasedAfterwardsIncludingThePreExistingOnes() throws Exception {
    UUID organization = insertOrganization();
    UUID owner = insertUser(organization);
    UUID existing = insertLibrary(organization, owner);

    applyChangelog(connection, CHANGELOG_PATH);

    UUID fresh = insertLibrary(organization, owner);
    assertThat(releaseStateOf(existing)).isEqualTo("NEVER_SET");
    assertThat(releaseStateOf(fresh)).isEqualTo("NEVER_SET");
  }

  /**
   * The Befristung is not merely an application rule: an ACTIVE release without an expiry, and a
   * NEVER_SET row carrying a setter, are both unrepresentable.
   */
  @Test
  void aReleaseWithoutItsBefristungIsUnrepresentable() throws Exception {
    UUID organization = insertOrganization();
    UUID owner = insertUser(organization);
    UUID library = insertLibrary(organization, owner);
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(() -> setRelease(library, "ACTIVE", false, true, owner))
        .hasMessageContaining("chk_knowledge_libraries_external_access");
    assertThatThrownBy(() -> setRelease(library, "NEVER_SET", false, true, owner))
        .hasMessageContaining("chk_knowledge_libraries_external_access");
    assertThatThrownBy(() -> setRelease(library, "FREIGEGEBEN", true, true, owner))
        .hasMessageContaining("chk_knowledge_libraries_external_access");

    assertThatCode(() -> setRelease(library, "ACTIVE", true, true, owner))
        .doesNotThrowAnyException();
    assertThatCode(() -> setRelease(library, "EXPIRED", true, true, owner))
        .doesNotThrowAnyException();
  }

  /**
   * The two new causes join the closed list rather than replacing it - a history written before
   * this changeset stays valid, which is what keeps the reconstruction usable across the change.
   */
  @Test
  void theHistoryAcceptsTheNewCausesAndStillAcceptsTheOldOnes() throws Exception {
    UUID organization = insertOrganization();
    UUID owner = insertUser(organization);
    UUID library = insertLibrary(organization, owner);
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatCode(() -> insertHistory(library, organization, "CREATED", "NEVER_SET"))
        .doesNotThrowAnyException();
    assertThatCode(() -> insertHistory(library, organization, "VISIBILITY_CHANGED", "NEVER_SET"))
        .doesNotThrowAnyException();
    assertThatCode(() -> insertHistory(library, organization, "EXTERNAL_ACCESS_CHANGED", "ACTIVE"))
        .doesNotThrowAnyException();
    assertThatCode(() -> insertHistory(library, organization, "EXTERNAL_ACCESS_EXPIRED", "EXPIRED"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> insertHistory(library, organization, "FREIGEGEBEN", "ACTIVE"))
        .hasMessageContaining("chk_library_visibility_history_cause");
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
      statement.setString(2, "external-access-" + id);
      statement.setString(3, "https://issuer.example");
      statement.setObject(4, organizationId);
      statement.executeUpdate();
    }
    return id;
  }

  private UUID insertLibrary(UUID organizationId, UUID ownerId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
                + " owner_user_id, visibility, source_type, source_path)"
                + " VALUES (?, ?, ?, 'USER', ?, 'PRIVATE', 'FILESYSTEM', '/tmp/external-access')")) {
      statement.setObject(1, id);
      statement.setObject(2, organizationId);
      statement.setString(3, "Bibliothek " + id);
      statement.setObject(4, ownerId);
      statement.executeUpdate();
    }
    return id;
  }

  private void setRelease(
      UUID libraryId, String state, boolean withExpiry, boolean withSetAt, UUID setBy)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE knowledge_libraries SET external_access_state = ?,"
                + " external_access_expires_at = ?, external_access_set_at = ?,"
                + " external_access_set_by_user_id = ? WHERE id = ?")) {
      statement.setString(1, state);
      statement.setObject(2, withExpiry ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
      statement.setObject(3, withSetAt ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
      statement.setObject(4, setBy);
      statement.setObject(5, libraryId);
      statement.executeUpdate();
    }
  }

  private void insertHistory(UUID libraryId, UUID organizationId, String cause, String state)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO library_visibility_history (id, library_id, organization_id, visibility,"
                + " listed, cause, valid_from, valid_to, created_at, external_access_state)"
                + " VALUES (?, ?, ?, 'PRIVATE', true, ?, now(), now(), now(), ?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, libraryId);
      statement.setObject(3, organizationId);
      statement.setString(4, cause);
      statement.setString(5, state);
      statement.executeUpdate();
    }
  }

  private String releaseStateOf(UUID libraryId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT external_access_state FROM knowledge_libraries WHERE id = ?")) {
      statement.setObject(1, libraryId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
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
    return scalarExists("SELECT 1 FROM pg_constraint WHERE conname = ?", name);
  }

  private boolean indexExists(String name) throws SQLException {
    return scalarExists(
        "SELECT 1 FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?", name);
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
