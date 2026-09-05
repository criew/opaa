package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta test for {@code changes/027-identity-provider-groups.yaml} (#1331, ADR-0025 Entscheidung
 * 4): the {@code IDENTITY_PROVIDER} group kind, the per-provider uniqueness of its namespaced
 * external id, and the two token-derived membership history causes.
 */
class Migration027IdentityProviderGroupsTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/027-identity-provider-groups.yaml";
  private static final String ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

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
  void beforeTheMigrationTheKindIsRejected() {
    assertThatThrownBy(() -> insertGroup("IDENTITY_PROVIDER", "Fachbereich", "oidc:p:Fachbereich"))
        .hasMessageContaining("chk_groups_kind");
  }

  @Test
  void widensTheKindAndKeepsSameNamedGroupsOfTwoProvidersApart() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatCode(
            () -> {
              insertGroup("IDENTITY_PROVIDER", "Fachbereich", "oidc:provider-a:Fachbereich");
              insertGroup("IDENTITY_PROVIDER", "Fachbereich", "oidc:provider-b:Fachbereich");
            })
        .doesNotThrowAnyException();
    // the namespaced id is what the baseline's uniqueness already binds
    assertThatThrownBy(
            () -> insertGroup("IDENTITY_PROVIDER", "Fachbereich", "oidc:provider-a:Fachbereich"))
        .hasMessageContaining("uk_groups_organization_external_id");
    assertThatThrownBy(() -> insertGroup("TOKEN", "x", "x"))
        .hasMessageContaining("chk_groups_kind");
  }

  @Test
  void widensTheMembershipHistoryCauses() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);
    UUID groupId = insertGroup("IDENTITY_PROVIDER", "Fachbereich", "oidc:p:Fachbereich");
    UUID userId = insertUser();

    assertThatCode(
            () -> {
              insertHistory(groupId, userId, "IDENTITY_PROVIDER_ADDED", true);
              insertHistory(groupId, userId, "IDENTITY_PROVIDER_REMOVED", false);
            })
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> insertHistory(groupId, insertUser(), "TOKEN_ADDED", false))
        .hasMessageContaining("chk_group_membership_history_cause");
  }

  private UUID insertGroup(String kind, String name, String externalId) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO groups (id, organization_id, kind, name, external_id) VALUES ('"
              + id
              + "', '"
              + ORGANIZATION_ID
              + "', '"
              + kind
              + "', '"
              + name
              + "', '"
              + externalId
              + "')");
    }
    return id;
  }

  private UUID insertUser() throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO users (id, subject, issuer, organization_id) VALUES ('"
              + id
              + "', 'sub-"
              + id
              + "', 'https://idp.example/realms/a', '"
              + ORGANIZATION_ID
              + "')");
    }
    return id;
  }

  /** {@code closed} closes the interval - only one open interval per group and user exists. */
  private void insertHistory(UUID groupId, UUID userId, String cause, boolean closed)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO group_membership_history (id, group_id, organization_id, user_id, cause,"
              + " valid_from, valid_to) VALUES (gen_random_uuid(), '"
              + groupId
              + "', '"
              + ORGANIZATION_ID
              + "', '"
              + userId
              + "', '"
              + cause
              + "', now(), "
              + (closed ? "now()" : "NULL")
              + ")");
    }
  }
}
