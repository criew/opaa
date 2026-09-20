package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/041-groups-provider-origin.yaml} (#1812, ADR-0036 Entscheidungen 2
 * und 11): the origin of a group becomes a real foreign key, the prefix leaves {@code external_id},
 * and every row the {@code RESTRICT} key could not hold is converted rather than dropped - each of
 * the migration's known failure cases has its own fixture here.
 */
class Migration041GroupsProviderOriginTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/041-groups-provider-origin.yaml";

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
  void beforeTheChangesetTheOriginIsOnlyAPrefixInTheExternalId() throws Exception {
    assertThat(columnExists("groups", "provider_id")).isFalse();
    assertThat(columnExists("groups", "source_path")).isFalse();
    assertThat(constraintExists("uk_groups_organization_external_id")).isTrue();
    assertThat(constraintExists("fk_groups_provider")).isFalse();
  }

  @Test
  void theChangesetAddsTheOriginColumnsTheKeyAndTheRestrictForeignKey() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(columnExists("groups", "provider_id")).isTrue();
    assertThat(columnExists("groups", "source_path")).isTrue();
    assertThat(constraintExists("uk_groups_organization_external_id")).isFalse();
    assertThat(constraintExists("chk_groups_provider_kind")).isTrue();
    assertThat(deleteRuleOf("fk_groups_provider")).isEqualTo("RESTRICT");
    assertThat(indexDefinition("uk_groups_organization_provider_kind_external_id"))
        .contains("(organization_id, provider_id, kind, external_id)")
        .contains("external_id IS NOT NULL");
    assertThat(nullsNotDistinct("uk_groups_organization_provider_kind_external_id")).isTrue();
  }

  /**
   * Datenerhalt: the bestand is migrated, not rebuilt - same row count, every group in the origin
   * ADR-0036 assigns it, and the prefix gone from the external id.
   */
  @Test
  void everyGroupKeepsItsRowAndLandsInTheOriginTheAdrAssignsIt() throws Exception {
    Fixture fixture = seedInstallationWithDefaultProvider();
    long before = count("SELECT count(*) FROM groups");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(count("SELECT count(*) FROM groups")).isEqualTo(before);
    assertThat(groupRow(fixture.tokenGroupOfDefaultProvider()))
        .containsExactly(fixture.defaultProvider(), "IDENTITY_PROVIDER", "Fachbereich 3");
    assertThat(groupRow(fixture.tokenGroupOfSecondProvider()))
        .containsExactly(fixture.secondProvider(), "IDENTITY_PROVIDER", "Fachbereich 3");
    assertThat(groupRow(fixture.orgUnit()))
        .containsExactly(fixture.defaultProvider(), "ORG_UNIT", "dir-guid-1");
    assertThat(groupRow(fixture.internalGroup())).containsExactly(null, "AD_HOC", null);
  }

  /**
   * The first of the two orphan cases of ADR-0036, Entscheidung 11: a token group whose provider
   * row is gone. It becomes an internal group without owners, its grant untouched.
   */
  @Test
  void aTokenGroupOfADeletedProviderBecomesAnInternalGroupAndKeepsItsGrant() throws Exception {
    Fixture fixture = seedInstallationWithDefaultProvider();
    UUID grant = seedGroupGrant(fixture, fixture.orphanedTokenGroup());

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(groupRow(fixture.orphanedTokenGroup())).containsExactly(null, "AD_HOC", null);
    assertThat(
            stringOf(
                "SELECT description FROM groups WHERE id = '" + fixture.orphanedTokenGroup() + "'"))
        .contains("gelöschten Identitätsanbieter")
        .contains("Verantwortliche sind offen");
    assertThat(count("SELECT count(*) FROM asset_grants WHERE id = '" + grant + "'")).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM asset_grants WHERE id = '"
                    + grant
                    + "' AND subject_group_id = '"
                    + fixture.orphanedTokenGroup()
                    + "' AND role = 'MANAGER'"))
        .isEqualTo(1);
  }

  /**
   * The second orphan case: an installation without a default provider has nobody to assign its
   * directory groups to. {@code dissolved} survives as a note in the description, because the flag
   * only ever means something for an ORG_UNIT group.
   */
  @Test
  void anOrgUnitGroupWithoutADefaultProviderBecomesAnInternalGroupWithItsDissolutionNoted()
      throws Exception {
    UUID organization = seedOrganization();
    UUID dissolved = seedGroup(organization, "ORG_UNIT", "Referat 50", "dir-guid-9", null, true);
    UUID present = seedGroup(organization, "ORG_UNIT", "Referat 51", "dir-guid-10", null, false);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(groupRow(dissolved)).containsExactly(null, "AD_HOC", null);
    assertThat(groupRow(present)).containsExactly(null, "AD_HOC", null);
    assertThat(stringOf("SELECT description FROM groups WHERE id = '" + dissolved + "'"))
        .contains("ohne Standardanbieter")
        .contains("aufgelöst");
    assertThat(stringOf("SELECT description FROM groups WHERE id = '" + present + "'"))
        .contains("ohne Standardanbieter")
        .doesNotContain("aufgelöst");
    assertThat(count("SELECT count(*) FROM groups WHERE id = '" + dissolved + "' AND dissolved"))
        .isZero();
  }

  /** Every converted orphan is traceable afterwards, under the system-process actor. */
  @Test
  void everyConvertedOrphanLeavesOneAuditEntryUnderTheMigrationActor() throws Exception {
    Fixture fixture = seedInstallationWithDefaultProvider();
    UUID orphanedOrgUnit =
        seedGroup(fixture.organization(), "ORG_UNIT", "Referat 99", "dir-guid-99", null, false);
    // the ORG_UNIT above is not an orphan here - a default provider exists - so exactly one is
    long auditedBefore = count("SELECT count(*) FROM audit_log WHERE actor_ref = 'migration'");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(count("SELECT count(*) FROM audit_log WHERE actor_ref = 'migration'"))
        .isEqualTo(auditedBefore + 1);
    assertThat(
            count(
                "SELECT count(*) FROM audit_log WHERE actor_ref = 'migration'"
                    + " AND actor_kind = 'SYSTEM_PROCESS' AND event_type = 'GROUP_CHANGED'"
                    + " AND object_type = 'GROUP' AND outcome = 'SUCCESS'"
                    + " AND object_id = '"
                    + fixture.orphanedTokenGroup()
                    + "'"))
        .isEqualTo(1);
    assertThat(groupRow(orphanedOrgUnit))
        .containsExactly(fixture.defaultProvider(), "ORG_UNIT", "dir-guid-99");
  }

  /**
   * The reason the prefix may only be cut once the new key stands and the old one is gone: two
   * providers' same-named groups would otherwise collide on {@code (organization_id, external_id)}
   * mid-update. Afterwards they stand side by side and only a third row of the same provider is
   * refused.
   */
  @Test
  void sameNamedGroupsOfTwoProvidersSurviveTheCutAndOnlyCollideWithinOneProvider()
      throws Exception {
    Fixture fixture = seedInstallationWithDefaultProvider();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(count("SELECT count(*) FROM groups WHERE external_id = 'Fachbereich 3'"))
        .isEqualTo(2);
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO groups (id, organization_id, kind, name, provider_id, external_id)"
                        + " VALUES (?, ?, 'IDENTITY_PROVIDER', 'Fachbereich 3', ?, 'Fachbereich 3')",
                    UUID.randomUUID(),
                    fixture.organization(),
                    fixture.defaultProvider()))
        .hasMessageContaining("uk_groups_organization_provider_kind_external_id");
  }

  /**
   * Two directory groups of an installation without a provider row (the {@code dev} mode) must stay
   * distinguishable although both carry an empty {@code provider_id} - that is what NULLS NOT
   * DISTINCT is for.
   */
  @Test
  void twoDirectoryGroupsWithoutAProviderStillCollideOnTheSameExternalId() throws Exception {
    UUID organization = seedOrganization();

    applyChangelog(connection, CHANGELOG_PATH);

    execute(
        "INSERT INTO groups (id, organization_id, kind, name, external_id)"
            + " VALUES (?, ?, 'ORG_UNIT', 'Referat 50', 'dir-guid-1')",
        UUID.randomUUID(),
        organization);
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO groups (id, organization_id, kind, name, external_id)"
                        + " VALUES (?, ?, 'ORG_UNIT', 'Referat 50 (zweites)', 'dir-guid-1')",
                    UUID.randomUUID(),
                    organization))
        .hasMessageContaining("uk_groups_organization_provider_kind_external_id");
  }

  /** Two internal groups have no source identity at all and must never collide. */
  @Test
  void internalGroupsNeverCollideWithEachOther() throws Exception {
    UUID organization = seedOrganization();

    applyChangelog(connection, CHANGELOG_PATH);

    seedGroup(organization, "AD_HOC", "Team A", null, null, false);
    seedGroup(organization, "AD_HOC", "Team B", null, null, false);
    assertThat(count("SELECT count(*) FROM groups WHERE organization_id = '" + organization + "'"))
        .isEqualTo(2);
  }

  @Test
  void theCheckConstraintHoldsTheTwoEndsOfTheOriginRule() throws Exception {
    Fixture fixture = seedInstallationWithDefaultProvider();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO groups (id, organization_id, kind, name, provider_id)"
                        + " VALUES (?, ?, 'AD_HOC', 'Interne mit Anbieter', ?)",
                    UUID.randomUUID(),
                    fixture.organization(),
                    fixture.defaultProvider()))
        .hasMessageContaining("chk_groups_provider_kind");
    assertThatThrownBy(
            () ->
                execute(
                    "INSERT INTO groups (id, organization_id, kind, name, external_id)"
                        + " VALUES (?, ?, 'IDENTITY_PROVIDER', 'Ohne Anbieter', 'x')",
                    UUID.randomUUID(),
                    fixture.organization()))
        .hasMessageContaining("chk_groups_provider_kind");
    // ORG_UNIT stays open in both directions until #1816 decides how the dev mode syncs
    execute(
        "INSERT INTO groups (id, organization_id, kind, name, external_id)"
            + " VALUES (?, ?, 'ORG_UNIT', 'Dev-Verzeichnisgruppe', 'dev-guid-1')",
        UUID.randomUUID(),
        fixture.organization());
  }

  /** {@code ON DELETE RESTRICT} is what carries "no group without its provider" structurally. */
  @Test
  void aProviderWithGroupsCannotBeDeletedByTheDatabase() throws Exception {
    Fixture fixture = seedInstallationWithDefaultProvider();

    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () -> execute("DELETE FROM oidc_providers WHERE id = ?", fixture.defaultProvider()))
        .hasMessageContaining("fk_groups_provider");
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

  private record Fixture(
      UUID organization,
      UUID userId,
      UUID defaultProvider,
      UUID secondProvider,
      UUID tokenGroupOfDefaultProvider,
      UUID tokenGroupOfSecondProvider,
      UUID orphanedTokenGroup,
      UUID orgUnit,
      UUID internalGroup) {}

  private Fixture seedInstallationWithDefaultProvider() throws SQLException {
    UUID organization = seedOrganization();
    UUID user = seedUser(organization);
    UUID defaultProvider = seedProvider("Haus A", "https://a.example", true);
    UUID secondProvider = seedProvider("Haus B", "https://b.example", false);
    UUID deletedProvider = UUID.randomUUID();
    return new Fixture(
        organization,
        user,
        defaultProvider,
        secondProvider,
        seedGroup(
            organization,
            "IDENTITY_PROVIDER",
            "Fachbereich 3",
            "oidc:" + defaultProvider + ":Fachbereich 3",
            null,
            false),
        seedGroup(
            organization,
            "IDENTITY_PROVIDER",
            "Fachbereich 3",
            "oidc:" + secondProvider + ":Fachbereich 3",
            null,
            false),
        seedGroup(
            organization,
            "IDENTITY_PROVIDER",
            "Waisengruppe",
            "oidc:" + deletedProvider + ":Waisengruppe",
            "Aus dem Token übernommen",
            false),
        seedGroup(organization, "ORG_UNIT", "Referat 50", "dir-guid-1", null, false),
        seedGroup(organization, "AD_HOC", "Projektteam", null, null, false));
  }

  private UUID seedOrganization() throws SQLException {
    UUID organization = UUID.randomUUID();
    execute(
        "INSERT INTO organizations (id, name) VALUES (?, ?)",
        organization,
        "Organisation " + organization);
    return organization;
  }

  private UUID seedUser(UUID organization) throws SQLException {
    UUID user = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, ?, ?)",
        user,
        "subject-" + user,
        "https://a.example",
        organization);
    return user;
  }

  private UUID seedProvider(String displayName, String issuer, boolean isDefault)
      throws SQLException {
    UUID provider = UUID.randomUUID();
    execute(
        "INSERT INTO oidc_providers (id, display_name, issuer_uri, client_id, is_default)"
            + " VALUES (?, ?, ?, 'opaa-frontend', ?)",
        provider,
        displayName,
        issuer,
        isDefault);
    return provider;
  }

  private UUID seedGroup(
      UUID organization,
      String kind,
      String name,
      String externalId,
      String description,
      boolean dissolved)
      throws SQLException {
    UUID group = UUID.randomUUID();
    execute(
        "INSERT INTO groups (id, organization_id, kind, name, external_id, description, dissolved,"
            + " dissolved_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        group,
        organization,
        kind,
        name,
        externalId,
        description,
        dissolved,
        dissolved
            ? java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z"))
            : null);
    return group;
  }

  private UUID seedGroupGrant(Fixture fixture, UUID groupId) throws SQLException {
    UUID library = UUID.randomUUID();
    execute(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, source_type) VALUES (?, ?, ?, 'USER', ?, 'PRIVATE', 'UPLOAD')",
        library,
        fixture.organization(),
        "Bibliothek " + library,
        fixture.userId());
    UUID grant = UUID.randomUUID();
    execute(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
            + " subject_group_id, role, granted_by_user_id) VALUES (?, ?, ?, 'GROUP', ?,"
            + " 'MANAGER', ?)",
        grant,
        library,
        fixture.organization(),
        groupId,
        fixture.userId());
    return grant;
  }

  /** {@code provider_id}, {@code kind} and {@code external_id} of one group, nulls included. */
  private List<Object> groupRow(UUID groupId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT provider_id, kind, external_id FROM groups WHERE id = ?")) {
      statement.setObject(1, groupId);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        List<Object> row = new ArrayList<>();
        row.add(rows.getObject(1));
        row.add(rows.getString(2));
        row.add(rows.getString(3));
        return row;
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

  private long count(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  private String stringOf(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
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
        connection.prepareStatement("SELECT 1 FROM pg_constraint WHERE conname = ?")) {
      statement.setString(1, name);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private String deleteRuleOf(String constraintName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT confdeltype FROM pg_constraint WHERE conname = ? AND contype = 'f'")) {
      statement.setString(1, constraintName);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return switch (rows.getString(1)) {
          case "r" -> "RESTRICT";
          case "c" -> "CASCADE";
          case "n" -> "SET NULL";
          case "a" -> "NO ACTION";
          default -> rows.getString(1);
        };
      }
    }
  }

  /** Whether the unique index treats two empty provider ids as equal rather than as distinct. */
  private boolean nullsNotDistinct(String indexName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT i.indnullsnotdistinct FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid"
                + " WHERE c.relname = ?")) {
      statement.setString(1, indexName);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getBoolean(1);
      }
    }
  }

  private String indexDefinition(String name) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema()"
                + " AND indexname = ?")) {
      statement.setString(1, name);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }
}
