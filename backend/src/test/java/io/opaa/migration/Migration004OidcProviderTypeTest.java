package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies changelog 004 in isolation (#1532, ADR-0033 Entscheidung 4): {@code
 * oidc_providers.provider_type} with its three CHECKs and the partial unique index that allows at
 * most one {@code LOCAL} row. An OIDC row inserted <em>before</em> the changeset proves the
 * backfill: existing providers become {@code OIDC} and keep their {@code client_id}.
 */
class Migration004OidcProviderTypeTest extends AbstractMigrationTest {

  private static final String CHANGELOG =
      "db/changelog/changes/004-add-provider-type-to-oidc-providers.yaml";

  private Connection connection;
  private UUID preExistingProviderId;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    preExistingProviderId = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO oidc_providers (id, display_name, issuer_uri, client_id, is_default)"
                + " VALUES (?, 'Bestand', 'https://idp.example/realms/alt', 'opaa-frontend', true)")) {
      statement.setObject(1, preExistingProviderId);
      statement.executeUpdate();
    }
    applyChangelog(connection, CHANGELOG);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void backfillsExistingRowsAsOidcAndKeepsTheirClientId() throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT provider_type, client_id FROM oidc_providers WHERE id = ?")) {
      statement.setObject(1, preExistingProviderId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString("provider_type")).isEqualTo("OIDC");
        assertThat(rows.getString("client_id")).isEqualTo("opaa-frontend");
      }
    }
    assertThat(
            LocalAccountSchemaSupport.columnIsNullable(connection, "oidc_providers", "client_id"))
        .as("client_id is nullable because the LOCAL row has none")
        .isTrue();
  }

  @Test
  void defaultsNewRowsToOidc() throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO oidc_providers (id, display_name, issuer_uri, client_id)"
                + " VALUES (?, 'Neu', 'https://idp.example/realms/neu', 'opaa')")) {
      statement.setObject(1, id);
      statement.executeUpdate();
    }
    assertThat(
            LocalAccountSchemaSupport.count(
                connection, "oidc_providers", "id = '" + id + "' AND provider_type = 'OIDC'"))
        .isEqualTo(1);
  }

  @Test
  void rejectsAnUnknownProviderType() {
    assertThatThrownBy(() -> insertProvider("SAML", "urn:x", null, false))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_oidc_providers_provider_type");
  }

  @Test
  void allowsExactlyOneLocalRow() throws SQLException {
    insertProvider("LOCAL", LocalAccountSchemaSupport.LOCAL_ISSUER, null, false);

    // Both indexes forbid the second row: the partial one on provider_type and the baseline's
    // issuer index, because chk_oidc_providers_local_row pins every LOCAL row to the same issuer.
    // Which of the two Postgres reports first is not part of the contract.
    assertThatThrownBy(
            () -> insertProvider("LOCAL", LocalAccountSchemaSupport.LOCAL_ISSUER, null, false))
        .isInstanceOf(SQLException.class)
        .satisfies(
            error ->
                assertThat(error.getMessage())
                    .containsAnyOf(
                        "ux_oidc_providers_single_local",
                        "ux_oidc_providers_issuer_uri_normalized"));
    assertThat(
            LocalAccountSchemaSupport.indexDefinition(connection, "ux_oidc_providers_single_local"))
        .isNotNull()
        .contains("UNIQUE")
        .contains("WHERE ((provider_type)::text = 'LOCAL'::text)");
  }

  @Test
  void rejectsALocalRowMarkedAsDefault() throws SQLException {
    // the pre-existing OIDC row is the default; clear it so only the LOCAL check can fire
    connection.createStatement().execute("UPDATE oidc_providers SET is_default = false");

    assertThatThrownBy(
            () -> insertProvider("LOCAL", LocalAccountSchemaSupport.LOCAL_ISSUER, null, true))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_oidc_providers_local_row");
  }

  @Test
  void rejectsALocalRowWithAnyOtherIssuer() {
    assertThatThrownBy(() -> insertProvider("LOCAL", "https://idp.example/local", null, false))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_oidc_providers_local_row");
  }

  @Test
  void clientIdIsPresentExactlyForOidcRows() {
    assertThatThrownBy(() -> insertProvider("OIDC", "https://idp.example/ohne-client", null, false))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_oidc_providers_client_id_by_type");
    assertThatThrownBy(
            () -> insertProvider("LOCAL", LocalAccountSchemaSupport.LOCAL_ISSUER, "opaa", false))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_oidc_providers_client_id_by_type");
    assertThatCode(
            () -> insertProvider("LOCAL", LocalAccountSchemaSupport.LOCAL_ISSUER, null, false))
        .doesNotThrowAnyException();
  }

  @Test
  void keepsTheSingleDefaultRuleForOidcRows() {
    assertThatThrownBy(() -> insertProvider("OIDC", "https://idp.example/zweiter", "x", true))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ux_oidc_providers_single_default");
  }

  private void insertProvider(String type, String issuerUri, String clientId, boolean isDefault)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO oidc_providers (id, display_name, issuer_uri, client_id, provider_type,"
                + " is_default) VALUES (?, ?, ?, ?, ?, ?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setString(2, type + " " + issuerUri);
      statement.setString(3, issuerUri);
      statement.setString(4, clientId);
      statement.setString(5, type);
      statement.setBoolean(6, isDefault);
      statement.executeUpdate();
    }
  }
}
