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
 * Delta tests for {@code changes/047-oidc-providers-directory-sync.yaml} (#1816, ADR-0036
 * Entscheidungen 2 und 3): the directory run becomes a setting of the provider row. The fixture
 * chain runs {@code 004} first because {@code provider_type} - which the check constraint reads -
 * only exists from there on.
 */
class Migration047OidcProvidersDirectorySyncTest extends AbstractMigrationTest {

  private static final String PROVIDER_TYPE_PATH =
      "db/changelog/changes/004-add-provider-type-to-oidc-providers.yaml";

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/047-oidc-providers-directory-sync.yaml";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    applyChangelog(connection, PROVIDER_TYPE_PATH);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheChangesetAProviderHasNoDirectoryRunSetting() throws Exception {
    assertThat(columnExists("oidc_providers", "directory_sync_enabled")).isFalse();
  }

  /** No installation gets a run switched on by the update itself. */
  @Test
  void everyExistingProviderStartsWithTheRunSwitchedOff() throws Exception {
    UUID provider = seedProvider("Haus A", "https://a.example", "OIDC", null);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(isSyncEnabled(provider)).isFalse();
    assertThat(intervalOf(provider)).isNull();
    assertThat(isNotNull("oidc_providers", "directory_sync_enabled")).isTrue();
  }

  /** ADR-0036, Entscheidung 2: one group mechanism per provider, held by the schema. */
  @Test
  void theRunCannotBeSwitchedOnWhileAGroupsClaimIsSet() throws Exception {
    UUID provider = seedProvider("Haus A", "https://a.example", "OIDC", "groups");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(() -> switchOn(provider, 360))
        .hasMessageContaining("chk_oidc_providers_directory_sync");
  }

  @Test
  void theRunCanBeSwitchedOnWithoutAGroupsClaim() throws Exception {
    UUID provider = seedProvider("Haus A", "https://a.example", "OIDC", null);

    applyChangelog(connection, CHANGELOG_PATH);
    switchOn(provider, 360);

    assertThat(isSyncEnabled(provider)).isTrue();
    assertThat(intervalOf(provider)).isEqualTo(360);
  }

  /** The local account management has no directory (ADR-0033). */
  @Test
  void theLocalRowCanNeverCarryADirectoryRun() throws Exception {
    UUID localRow = seedProvider("Lokale Konten", "urn:opaa:local", "LOCAL", null);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(() -> switchOn(localRow, 360))
        .hasMessageContaining("chk_oidc_providers_directory_sync");
  }

  /**
   * An interval without a run would be a value nobody reads, a run without one would have no due
   * time, and the bounds keep a typo from turning the run into a load test of the directory.
   */
  @Test
  void aSwitchedOffRunCarriesNoIntervalAndASwitchedOnOneStaysWithinItsBounds() throws Exception {
    UUID provider = seedProvider("Haus A", "https://a.example", "OIDC", null);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () ->
                execute(
                    "UPDATE oidc_providers SET directory_sync_interval_minutes = 360 WHERE id = ?",
                    provider))
        .hasMessageContaining("chk_oidc_providers_directory_sync");
    assertThatThrownBy(() -> switchOn(provider, 4))
        .hasMessageContaining("chk_oidc_providers_directory_sync");
    assertThatThrownBy(() -> switchOn(provider, 10081))
        .hasMessageContaining("chk_oidc_providers_directory_sync");
    assertThatCode(() -> switchOn(provider, 5)).doesNotThrowAnyException();
    assertThatCode(() -> switchOn(provider, 10080)).doesNotThrowAnyException();
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

  private UUID seedProvider(
      String displayName, String issuer, String providerType, String groupsClaim)
      throws SQLException {
    UUID provider = UUID.randomUUID();
    // chk_oidc_providers_client_id_by_type: an OIDC row has a client id, the LOCAL row has none
    execute(
        "INSERT INTO oidc_providers (id, display_name, issuer_uri, client_id, provider_type,"
            + " groups_claim) VALUES (?, ?, ?, ?, ?, ?)",
        provider,
        displayName,
        issuer,
        "LOCAL".equals(providerType) ? null : "opaa-frontend",
        providerType,
        groupsClaim);
    return provider;
  }

  private void switchOn(UUID providerId, int intervalMinutes) throws SQLException {
    execute(
        "UPDATE oidc_providers SET directory_sync_enabled = true,"
            + " directory_sync_interval_minutes = ? WHERE id = ?",
        intervalMinutes,
        providerId);
  }

  private boolean isSyncEnabled(UUID providerId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT directory_sync_enabled FROM oidc_providers WHERE id = ?")) {
      statement.setObject(1, providerId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getBoolean(1);
      }
    }
  }

  private Integer intervalOf(UUID providerId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT directory_sync_interval_minutes FROM oidc_providers WHERE id = ?")) {
      statement.setObject(1, providerId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        int value = rows.getInt(1);
        return rows.wasNull() ? null : value;
      }
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
