package io.opaa.migration;

import static io.opaa.migration.AssetShellMigrationFixtures.DEFAULT_ORGANIZATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/086-audit-log-prompt-object-types.yaml}: the audit log accepts a
 * prompt library and a prompt as its object, still refuses any object outside the closed list, and
 * the privilege model of ADR-0015 is left as it was - the table stays with {@code
 * opaa_audit_owner}, and the migration account holds no membership in it afterwards.
 */
class Migration086AuditLogPromptObjectTypesTest extends AbstractMigrationTest {

  static final String AUDIT_OBJECT_TYPES =
      "db/changelog/changes/086-audit-log-prompt-object-types.yaml";

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
  void thePromptObjectsAreAcceptedAfterTheChangesetAndNotBefore() throws Exception {
    assertThatThrownBy(() -> insertEntry("PROMPT_LIBRARY"))
        .hasMessageContaining("chk_audit_log_object_type");

    applyChangelog(connection, AUDIT_OBJECT_TYPES);

    assertThatCode(() -> insertEntry("PROMPT_LIBRARY")).doesNotThrowAnyException();
    assertThatCode(() -> insertEntry("PROMPT")).doesNotThrowAnyException();
    assertThatCode(() -> insertEntry("KNOWLEDGE_LIBRARY")).doesNotThrowAnyException();
    assertThatThrownBy(() -> insertEntry("SKILL"))
        .hasMessageContaining("chk_audit_log_object_type");
  }

  @Test
  void theAuditLogStaysWithItsOwnerRoleAndTheMembershipIsGivenBack() throws Exception {
    applyChangelog(connection, AUDIT_OBJECT_TYPES);

    assertThat(
            fixtures.string(
                "SELECT tableowner FROM pg_tables WHERE schemaname = current_schema()"
                    + " AND tablename = 'audit_log'"))
        .isEqualTo("opaa_audit_owner");
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM pg_auth_members m"
                    + " JOIN pg_roles r ON r.oid = m.roleid"
                    + " JOIN pg_roles u ON u.oid = m.member"
                    + " WHERE r.rolname = 'opaa_audit_owner' AND u.rolname = current_user"))
        .isZero();
    assertThat(fixtures.string("SELECT current_user"))
        .as("the role taken for the changeset is reset")
        .isEqualTo(fixtures.string("SELECT session_user"));
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(AssetShellMigrationFixtures.masterChangelog()).contains(AUDIT_OBJECT_TYPES);
  }

  private void insertEntry(String objectType) throws SQLException {
    fixtures.execute(
        "INSERT INTO audit_log (event_id, recorded_at, organization_id, actor_kind, actor_ref,"
            + " event_type, object_type, object_id, outcome) VALUES (?, ?, ?, 'USER',"
            + " 'pseud-actor-1', 'PROMPT_LIBRARY_CREATED', ?, ?, 'SUCCESS')",
        UUID.randomUUID(),
        java.sql.Timestamp.from(Instant.now()),
        DEFAULT_ORGANIZATION,
        objectType,
        UUID.randomUUID().toString());
  }
}
