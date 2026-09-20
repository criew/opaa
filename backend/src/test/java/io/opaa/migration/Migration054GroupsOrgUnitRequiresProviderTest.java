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
 * Delta tests for {@code changes/054-groups-org-unit-requires-provider.yaml} (#1816, ADR-0036
 * Entscheidung 2): every provider group carries its provider. {@code 041} runs first - it brings
 * {@code provider_id} and the softer form of the check this changeset tightens.
 */
class Migration054GroupsOrgUnitRequiresProviderTest extends AbstractMigrationTest {

  private static final String PROVIDER_ORIGIN_PATH =
      "db/changelog/changes/041-groups-provider-origin.yaml";

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/054-groups-org-unit-requires-provider.yaml";

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
    applyChangelog(connection, PROVIDER_ORIGIN_PATH);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  /** The state 041 deliberately left open pending this issue's dev-mode decision. */
  @Test
  void beforeTheChangesetAnOrgUnitGroupMayHaveNoProvider() throws Exception {
    assertThatCode(() -> seedOrgUnit("Referat 50", "dir-guid-1", null, false))
        .doesNotThrowAnyException();
  }

  /**
   * A directory group without a provider can only have come from a run in the {@code dev} mode
   * between 041 and here. The run is bound to a provider row from now on, so such a group is no
   * longer maintained by anything - it becomes an internal group rather than blocking the update.
   */
  @Test
  void anOrgUnitGroupWithoutAProviderBecomesAnInternalGroup() throws Exception {
    UUID group = seedOrgUnit("Referat 50", "dir-guid-1", null, false);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(stringOf("SELECT kind FROM groups WHERE id = '" + group + "'")).isEqualTo("AD_HOC");
    assertThat(stringOf("SELECT external_id FROM groups WHERE id = '" + group + "'")).isNull();
    assertThat(stringOf("SELECT description FROM groups WHERE id = '" + group + "'"))
        .contains("ohne Anbieterzeile")
        .contains("Verantwortliche sind offen");
    assertThat(count("SELECT count(*) FROM groups WHERE id = '" + group + "'")).isEqualTo(1);
  }

  /** A dissolved one keeps that fact as a note - the flag only applies to ORG_UNIT. */
  @Test
  void aDissolvedOneKeepsTheFactAsANoteAndLosesTheFlag() throws Exception {
    UUID group = seedOrgUnit("Referat 51", "dir-guid-2", null, true);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(stringOf("SELECT description FROM groups WHERE id = '" + group + "'"))
        .contains("aufgelöst");
    assertThat(count("SELECT count(*) FROM groups WHERE id = '" + group + "' AND dissolved"))
        .isZero();
  }

  /**
   * The conversion is a change of kind, not of reach: the group keeps every membership and every
   * grant it held. Without this the update would be a silent revocation dressed up as a migration.
   */
  @Test
  void theConvertedGroupKeepsItsMembershipsAndItsGrants() throws Exception {
    UUID group = seedOrgUnit("Referat 55", "dir-guid-6", null, false);
    UUID member = seedUser();
    UUID membership = seedMembership(group, member);
    UUID grant = seedGroupGrant(group);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(
            count(
                "SELECT count(*) FROM group_memberships WHERE id = '"
                    + membership
                    + "' AND group_id = '"
                    + group
                    + "' AND user_id = '"
                    + member
                    + "'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM asset_grants WHERE id = '"
                    + grant
                    + "' AND subject_group_id = '"
                    + group
                    + "' AND role = 'MANAGER'"))
        .isEqualTo(1);
  }

  /** The conversion is traceable afterwards, under the system-process actor. */
  @Test
  void everyConvertedGroupLeavesAnAuditEntry() throws Exception {
    UUID group = seedOrgUnit("Referat 52", "dir-guid-3", null, false);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(
            count(
                "SELECT count(*) FROM audit_log WHERE object_id = '"
                    + group
                    + "' AND actor_ref = 'migration' AND event_type = 'GROUP_CHANGED'"))
        .isEqualTo(1);
  }

  /** A group with its provider is untouched - only the ones without one are converted. */
  @Test
  void anOrgUnitGroupWithAProviderIsLeftExactlyAsItIs() throws Exception {
    UUID provider = seedProvider("Haus A", "https://a.example");
    UUID group = seedOrgUnit("Referat 53", "dir-guid-4", provider, false);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(stringOf("SELECT kind FROM groups WHERE id = '" + group + "'"))
        .isEqualTo("ORG_UNIT");
    assertThat(stringOf("SELECT external_id FROM groups WHERE id = '" + group + "'"))
        .isEqualTo("dir-guid-4");
  }

  /** After the changeset the rule of ADR-0036, Entscheidung 2 holds in both directions. */
  @Test
  void afterTheChangesetAProviderGroupWithoutAProviderIsRefused() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(() -> seedOrgUnit("Referat 54", "dir-guid-5", null, false))
        .hasMessageContaining("chk_groups_provider_kind");
    assertThatThrownBy(
            () -> seedInternalGroupWithProvider(seedProvider("Haus B", "https://b.example")))
        .hasMessageContaining("chk_groups_provider_kind");
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

  private UUID seedOrgUnit(String name, String externalId, UUID providerId, boolean dissolved)
      throws SQLException {
    UUID group = UUID.randomUUID();
    execute(
        "INSERT INTO groups (id, organization_id, kind, name, provider_id, external_id, dissolved,"
            + " dissolved_at) VALUES (?, ?, 'ORG_UNIT', ?, ?, ?, ?, ?)",
        group,
        DEFAULT_ORGANIZATION,
        name,
        providerId,
        externalId,
        dissolved,
        dissolved
            ? java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z"))
            : null);
    return group;
  }

  private UUID seedUser() throws SQLException {
    UUID user = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, organization_id, subject, issuer, email, display_name)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        user,
        DEFAULT_ORGANIZATION,
        "subject-" + user,
        "https://idp.example/realms/a",
        user + "@example.com",
        "Test User");
    return user;
  }

  private UUID seedMembership(UUID groupId, UUID userId) throws SQLException {
    UUID membership = UUID.randomUUID();
    execute(
        "INSERT INTO group_memberships (id, user_id, group_id, organization_id)"
            + " VALUES (?, ?, ?, ?)",
        membership,
        userId,
        groupId,
        DEFAULT_ORGANIZATION);
    return membership;
  }

  private UUID seedGroupGrant(UUID groupId) throws SQLException {
    UUID owner = seedUser();
    UUID library = UUID.randomUUID();
    execute(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, source_type) VALUES (?, ?, ?, 'USER', ?, 'PRIVATE', 'UPLOAD')",
        library,
        DEFAULT_ORGANIZATION,
        "Bibliothek " + library,
        owner);
    UUID grant = UUID.randomUUID();
    // The fixture chain stops before 038/039, so a grant still names its library directly.
    execute(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
            + " subject_group_id, role, granted_by_user_id) VALUES (?, ?, ?, 'GROUP', ?,"
            + " 'MANAGER', ?)",
        grant,
        library,
        DEFAULT_ORGANIZATION,
        groupId,
        owner);
    return grant;
  }

  private void seedInternalGroupWithProvider(UUID providerId) throws SQLException {
    execute(
        "INSERT INTO groups (id, organization_id, kind, name, provider_id)"
            + " VALUES (?, ?, 'AD_HOC', 'Intern mit Anbieter', ?)",
        UUID.randomUUID(),
        DEFAULT_ORGANIZATION,
        providerId);
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  private String stringOf(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
    }
  }

  private int count(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      assertThat(rows.next()).isTrue();
      return rows.getInt(1);
    }
  }
}
