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
 * Delta tests for {@code changes/048-directory-sync-status-per-provider.yaml} (#1816, ADR-0036
 * Entscheidung 3): the status line of the directory run belongs to the provider, not to the
 * organization.
 */
class Migration048DirectorySyncStatusPerProviderTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/048-directory-sync-status-per-provider.yaml";

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
  void beforeTheChangesetTheStatusLineHasNoProvider() throws Exception {
    assertThat(columnExists("directory_sync_status", "provider_id")).isFalse();
    assertThat(constraintExists("uk_directory_sync_status_organization")).isTrue();
  }

  /**
   * The existing rows describe the last run of a binding that no longer exists, and there is no
   * provider they could be attributed to - a run without a default provider wrote one too. They go
   * rather than being guessed at; the effected changes themselves live in the audit log and the
   * permission history and are untouched.
   */
  @Test
  void theOrganizationWideRowsAreRemovedRatherThanGuessedAProviderFor() throws Exception {
    seedStatus();
    assertThat(statusRowCount()).isEqualTo(1);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(statusRowCount()).isZero();
    assertThat(isNotNull("directory_sync_status", "provider_id")).isTrue();
    assertThat(constraintExists("uk_directory_sync_status_organization")).isFalse();
    assertThat(constraintExists("uk_directory_sync_status_organization_provider")).isTrue();
  }

  /** Two providers of one organization each keep their own line - the whole point of the change. */
  @Test
  void twoProvidersOfOneOrganizationEachCarryTheirOwnStatusLine() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID first = seedProvider("Haus A", "https://a.example");
    UUID second = seedProvider("Haus B", "https://b.example");

    assertThatCode(() -> seedStatusFor(first)).doesNotThrowAnyException();
    assertThatCode(() -> seedStatusFor(second)).doesNotThrowAnyException();
    assertThat(statusRowCount()).isEqualTo(2);

    assertThatThrownBy(() -> seedStatusFor(first))
        .hasMessageContaining("uk_directory_sync_status_organization_provider");
  }

  /** The status line is no rights record; it goes with the provider instead of blocking it. */
  @Test
  void deletingAProviderTakesItsStatusLineWithIt() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID provider = seedProvider("Haus A", "https://a.example");
    seedStatusFor(provider);

    execute("DELETE FROM oidc_providers WHERE id = ?", provider);

    assertThat(statusRowCount()).isZero();
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

  private UUID seedProvider(String displayName, String issuer) throws SQLException {
    UUID provider = UUID.randomUUID();
    execute(
        "INSERT INTO oidc_providers (id, display_name, issuer_uri, client_id)"
            + " VALUES (?, ?, ?, ?)",
        provider,
        displayName,
        issuer,
        "opaa-frontend");
    return provider;
  }

  private void seedStatus() throws SQLException {
    execute(
        "INSERT INTO directory_sync_status (id, organization_id, last_run_at, last_outcome)"
            + " VALUES (?, ?, now(), 'APPLIED')",
        UUID.randomUUID(),
        DEFAULT_ORGANIZATION);
  }

  private void seedStatusFor(UUID providerId) throws SQLException {
    execute(
        "INSERT INTO directory_sync_status (id, organization_id, provider_id, last_run_at,"
            + " last_outcome) VALUES (?, ?, ?, now(), 'APPLIED')",
        UUID.randomUUID(),
        DEFAULT_ORGANIZATION,
        providerId);
  }

  private int statusRowCount() throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement("SELECT count(*) FROM directory_sync_status");
        ResultSet rows = statement.executeQuery()) {
      assertThat(rows.next()).isTrue();
      return rows.getInt(1);
    }
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
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
        connection.prepareStatement(
            "SELECT 1 FROM pg_constraint WHERE conname = ? AND connamespace ="
                + " current_schema()::regnamespace")) {
      statement.setString(1, name);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private boolean isNotNull(String table, String column) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT is_nullable FROM information_schema.columns WHERE table_schema ="
                + " current_schema() AND table_name = ? AND column_name = ?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return "NO".equals(rows.getString(1));
      }
    }
  }
}
