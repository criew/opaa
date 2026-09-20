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
 * Delta tests for {@code changes/053-create-directory-connectors.yaml} (#1817, ADR-0036
 * Entscheidung 3): the directory access of one provider - at most one per provider, gone with it,
 * and never holding a cleartext secret.
 */
class Migration053DirectoryConnectorsTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/053-create-directory-connectors.yaml";

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
  void beforeTheChangesetThereIsNoPlaceForADirectoryAccess() throws Exception {
    assertThat(tableExists("directory_connectors")).isFalse();
  }

  /**
   * One directory per provider - the realm is the provider's own, so a second row has no meaning.
   */
  @Test
  void aProviderCanHaveAtMostOneDirectoryAccess() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID provider = seedProvider("Haus A", "https://a.example/realms/a");

    assertThatCode(() -> seedConnector(provider, "enc:v1:AAAA")).doesNotThrowAnyException();
    assertThatThrownBy(() -> seedConnector(provider, "enc:v1:BBBB"))
        .hasMessageContaining("uk_directory_connectors_provider");
  }

  @Test
  void twoProvidersEachCarryTheirOwnDirectoryAccess() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID first = seedProvider("Haus A", "https://a.example/realms/a");
    UUID second = seedProvider("Haus B", "https://b.example/realms/b");

    seedConnector(first, "enc:v1:AAAA");
    seedConnector(second, "enc:v1:BBBB");

    assertThat(connectorCount()).isEqualTo(2);
  }

  /** An access without its provider has no meaning left - and its secret no reason to stay. */
  @Test
  void deletingAProviderTakesItsDirectoryAccessWithIt() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID provider = seedProvider("Haus A", "https://a.example/realms/a");
    seedConnector(provider, "enc:v1:AAAA");

    execute("DELETE FROM oidc_providers WHERE id = ?", provider);

    assertThat(connectorCount()).isZero();
  }

  /**
   * Liquibase runs before the application and holds no encryption key, so a cleartext secret can
   * only ever get here through a write past the application - which the CHECK refuses.
   */
  @Test
  void aCleartextSecretIsRefused() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID provider = seedProvider("Haus A", "https://a.example/realms/a");

    assertThatThrownBy(() -> seedConnector(provider, "geheim"))
        .hasMessageContaining("chk_directory_connectors_secret_encrypted");
  }

  @Test
  void anUnknownConnectorTypeIsRefused() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID provider = seedProvider("Haus A", "https://a.example/realms/a");

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO directory_connectors (id, organization_id, provider_id,"
                        + " connector_type, client_id, client_secret, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'LDAP', 'opaa-directory', 'enc:v1:AAAA', now(),"
                        + " now())",
                    UUID.randomUUID(),
                    DEFAULT_ORGANIZATION,
                    provider))
        .hasMessageContaining("chk_directory_connectors_type");
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
        "INSERT INTO oidc_providers (id, display_name, issuer_uri, client_id) VALUES (?, ?, ?, ?)",
        provider,
        displayName,
        issuer,
        "opaa-frontend");
    return provider;
  }

  private void seedConnector(UUID providerId, String secret) throws SQLException {
    execute(
        "INSERT INTO directory_connectors (id, organization_id, provider_id, connector_type,"
            + " base_url, client_id, client_secret, created_at, updated_at)"
            + " VALUES (?, ?, ?, 'KEYCLOAK', NULL, 'opaa-directory', ?, now(), now())",
        UUID.randomUUID(),
        DEFAULT_ORGANIZATION,
        providerId,
        secret);
  }

  private int connectorCount() throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement("SELECT count(*) FROM directory_connectors");
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

  private boolean tableExists(String table) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema()"
                + " AND table_name = ?")) {
      statement.setString(1, table);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }
}
