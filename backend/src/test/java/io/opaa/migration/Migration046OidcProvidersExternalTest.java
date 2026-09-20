package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
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
 * Delta tests for {@code changes/046-oidc-providers-external.yaml} (#1812, ADR-0036 Entscheidung
 * 2): the provider row carries whether it belongs to another organisation. The fixture chain runs
 * {@code 004} first because the LOCAL row - the one the check constraint is about - only exists
 * from there on.
 */
class Migration046OidcProvidersExternalTest extends AbstractMigrationTest {

  private static final String PROVIDER_TYPE_PATH =
      "db/changelog/changes/004-add-provider-type-to-oidc-providers.yaml";

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/046-oidc-providers-external.yaml";

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
  void beforeTheChangesetAProviderCarriesNoExternalMark() throws Exception {
    assertThat(columnExists("oidc_providers", "is_external")).isFalse();
  }

  /**
   * The rule of ADR-0036, Entscheidung 2: every provider but the default one starts external, and
   * the local account management never is.
   */
  @Test
  void theExistingProvidersGetTheDefaultMarkTheAdrPrescribes() throws Exception {
    UUID defaultProvider = seedProvider("Haus A", "https://a.example", true, "OIDC");
    UUID foreignProvider = seedProvider("Haus B", "https://b.example", false, "OIDC");
    UUID localRow = seedProvider("Lokale Konten", "urn:opaa:local", false, "LOCAL");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(isExternal(defaultProvider)).isFalse();
    assertThat(isExternal(foreignProvider)).isTrue();
    assertThat(isExternal(localRow)).isFalse();
    assertThat(isNotNull("oidc_providers", "is_external")).isTrue();
  }

  /** A row inserted past the application is the foreign one in case of doubt. */
  @Test
  void aRowWrittenWithoutTheColumnIsExternal() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    UUID provider = seedProvider("Haus C", "https://c.example", false, "OIDC");

    assertThat(isExternal(provider)).isTrue();
  }

  @Test
  void theLocalRowCanNeverBeMarkedExternal() throws Exception {
    UUID localRow = seedProvider("Lokale Konten", "urn:opaa:local", false, "LOCAL");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () -> execute("UPDATE oidc_providers SET is_external = true WHERE id = ?", localRow))
        .hasMessageContaining("chk_oidc_providers_local_not_external");
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
      String displayName, String issuer, boolean isDefault, String providerType)
      throws SQLException {
    UUID provider = UUID.randomUUID();
    // chk_oidc_providers_client_id_by_type: an OIDC row has a client id, the LOCAL row has none
    execute(
        "INSERT INTO oidc_providers (id, display_name, issuer_uri, client_id, is_default,"
            + " provider_type) VALUES (?, ?, ?, ?, ?, ?)",
        provider,
        displayName,
        issuer,
        "LOCAL".equals(providerType) ? null : "opaa-frontend",
        isDefault,
        providerType);
    return provider;
  }

  private boolean isExternal(UUID providerId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT is_external FROM oidc_providers WHERE id = ?")) {
      statement.setObject(1, providerId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getBoolean(1);
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
