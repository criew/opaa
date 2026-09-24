package io.opaa.migration;

import static io.opaa.migration.AssetShellMigrationFixtures.DEFAULT_ORGANIZATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/087-capability-create-prompt-library.yaml}: the fifth capability
 * is delivered to all accounts of every existing organization with an open {@code DELIVERED}
 * interval starting at the migration - the right did not exist before - and to every organization
 * created afterwards through the trigger of 051.
 */
class Migration087CapabilityCreatePromptLibraryTest extends AbstractMigrationTest {

  static final String CAPABILITY = "db/changelog/changes/087-capability-create-prompt-library.yaml";

  private Connection connection;
  private AssetShellMigrationFixtures fixtures;

  @Override
  protected String baseFixtureChangelogPath() {
    return Migration085PromptLibrariesTest.FIXTURE_CHAIN;
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    fixtures = new AssetShellMigrationFixtures(connection);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void everyExistingOrganizationGetsTheRightForAllAccountsFromTheMigrationOn() throws Exception {
    UUID older = organization(Instant.parse("2025-01-01T00:00:00Z"));
    fixtures.execute(
        "DELETE FROM capability_grants WHERE organization_id = ? AND capability = 'CREATE_SPACE'",
        older);
    Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);

    applyChangelog(connection, CAPABILITY);

    for (UUID organization : new UUID[] {DEFAULT_ORGANIZATION, older}) {
      assertThat(
              fixtures.count(
                  "SELECT count(*) FROM capability_grants WHERE organization_id = ?"
                      + " AND capability = 'CREATE_PROMPT_LIBRARY'"
                      + " AND subject_type = 'ALL_ACCOUNTS'",
                  organization))
          .isEqualTo(1);
      assertThat(
              fixtures.count(
                  "SELECT count(*) FROM capability_grant_history WHERE organization_id = ?"
                      + " AND capability = 'CREATE_PROMPT_LIBRARY' AND cause = 'DELIVERED'"
                      + " AND valid_to IS NULL AND valid_from >= ?",
                  organization,
                  Timestamp.from(before)))
          .as("the interval starts at the migration, not with the organization")
          .isEqualTo(1);
    }
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM capability_grants WHERE organization_id = ?"
                    + " AND capability = 'CREATE_SPACE'",
                older))
        .as("a narrowed existing capability stays narrowed")
        .isZero();
  }

  @Test
  void anOrganizationCreatedLaterStartsWithTheFourDeliveredCapabilities() throws Exception {
    applyChangelog(connection, CAPABILITY);

    UUID later = organization(Instant.now());

    assertThat(
            fixtures.string(
                "SELECT string_agg(capability, ',' ORDER BY capability) FROM capability_grants"
                    + " WHERE organization_id = ? AND subject_type = 'ALL_ACCOUNTS'",
                later))
        .isEqualTo("CREATE_CONNECTOR_LIBRARY,CREATE_LIBRARY,CREATE_PROMPT_LIBRARY,CREATE_SPACE");
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM capability_grant_history WHERE organization_id = ?"
                    + " AND capability = 'CREATE_PROMPT_LIBRARY' AND cause = 'DELIVERED'"
                    + " AND valid_to IS NULL",
                later))
        .isEqualTo(1);
  }

  @Test
  void bothTablesAcceptTheNewCapabilityAndStillRefuseAnUnknownOne() throws Exception {
    applyChangelog(connection, CAPABILITY);
    UUID user = fixtures.user();

    assertThatCode(() -> grantToUser("CREATE_PROMPT_LIBRARY", user)).doesNotThrowAnyException();
    assertThatThrownBy(() -> grantToUser("CREATE_SKILL", user))
        .hasMessageContaining("chk_capability_grants_capability");
    assertThatThrownBy(
            () ->
                fixtures.execute(
                    "INSERT INTO capability_grant_history (id, organization_id, capability,"
                        + " subject_type, cause, valid_from) VALUES (?, ?, 'CREATE_SKILL',"
                        + " 'ALL_ACCOUNTS', 'GRANTED', now())",
                    UUID.randomUUID(),
                    DEFAULT_ORGANIZATION))
        .hasMessageContaining("chk_capability_grant_history_capability");
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(AssetShellMigrationFixtures.masterChangelog()).contains(CAPABILITY);
  }

  private UUID organization(Instant createdAt) throws SQLException {
    UUID organization = UUID.randomUUID();
    fixtures.execute(
        "INSERT INTO organizations (id, name, created_at) VALUES (?, ?, ?)",
        organization,
        "Organisation " + organization,
        Timestamp.from(createdAt));
    return organization;
  }

  private void grantToUser(String capability, UUID user) throws SQLException {
    fixtures.execute(
        "INSERT INTO capability_grants (id, organization_id, capability, subject_type,"
            + " subject_user_id) VALUES (?, ?, ?, 'USER', ?)",
        UUID.randomUUID(),
        DEFAULT_ORGANIZATION,
        capability,
        user);
  }
}
