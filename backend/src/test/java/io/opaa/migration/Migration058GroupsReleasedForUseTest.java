package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

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
 * Delta tests for {@code changes/058-groups-released-for-use.yaml} (#1814, ADR-0036 Entscheidung
 * 9). The point of the changeset is the stock migration, so every test here starts from a stock:
 * "Vorgabe nicht freigegeben" applies prospectively, and no library manager loses a possibility
 * they were already using.
 *
 * <p>The fixture chain runs 041 (the provider column an internal group is told apart by), 042 (the
 * capability grants the backfill reads) and 043 (the {@code group_id} a space membership names
 * since #1815) on top of the baseline - the backfill reads all three. 043 runs before this
 * changeset in the master changelog too, which {@link
 * #theChangelogIsReferencedByTheMasterChangelog} holds on to: without it the backfill would
 * silently stop marking a group whose only effect is a space membership.
 */
class Migration058GroupsReleasedForUseTest extends AbstractMigrationTest {

  private static final String PROVIDER_ORIGIN_PATH =
      "db/changelog/changes/041-groups-provider-origin.yaml";

  private static final String CAPABILITY_GRANTS_PATH =
      "db/changelog/changes/042-create-capability-grants.yaml";

  private static final String SPACE_MEMBERSHIP_SUBJECT_PATH =
      "db/changelog/changes/043-space-memberships-subject.yaml";

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/058-groups-released-for-use.yaml";

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
    applyChangelog(connection, CAPABILITY_GRANTS_PATH);
    applyChangelog(connection, SPACE_MEMBERSHIP_SUBJECT_PATH);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheChangesetThereIsNoReleaseColumn() throws Exception {
    assertThat(columnCount("released_for_use")).isZero();
  }

  /** A group nobody ever used stays where the default puts it: not selectable by third parties. */
  @Test
  void anInternalGroupWithoutAnyEffectIsNotReleased() throws Exception {
    UUID group = seedInternalGroup("Ungenutzt");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(released(group)).isFalse();
  }

  /** "Niemand verliert eine Möglichkeit, die er benutzt hat" - the grant it holds. */
  @Test
  void anInternalGroupHoldingAGrantIsReleased() throws Exception {
    UUID group = seedInternalGroup("Mit Grant");
    seedGroupGrant(group);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(released(group)).isTrue();
  }

  /** The same for the asset it owns. */
  @Test
  void anInternalGroupOwningALibraryIsReleased() throws Exception {
    UUID group = seedInternalGroup("Eigentuemerin");
    seedLibraryOwnedByGroup(group);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(released(group)).isTrue();
  }

  /** And for the capability it holds - a group that opens a creation path is in use too. */
  @Test
  void anInternalGroupHoldingACapabilityIsReleased() throws Exception {
    UUID group = seedInternalGroup("Mit Anlegerecht");
    execute(
        "INSERT INTO capability_grants (id, organization_id, capability, subject_type,"
            + " subject_group_id) VALUES (?, ?, 'CREATE_SPACE', 'GROUP', ?)",
        UUID.randomUUID(),
        DEFAULT_ORGANIZATION,
        group);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(released(group)).isTrue();
  }

  /**
   * The fourth effect of ADR-0036, Entscheidung 9: a group whose only reach is a space membership
   * (#1815) is in use as much as one holding a grant, and losing the release would take its members
   * out of the space they work in.
   */
  @Test
  void anInternalGroupThatIsOnlyASpaceMemberIsReleased() throws Exception {
    UUID group = seedInternalGroup("Nur im Space");
    seedGroupSpaceMembership(group);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(released(group)).isTrue();
  }

  /**
   * The column is about internal groups alone. A provider group needs no release - the response
   * derives that - so the migration leaves its column at the default rather than pretending the
   * house decided anything about it.
   */
  @Test
  void aProviderGroupIsLeftAtTheDefault() throws Exception {
    UUID provider = seedProvider();
    UUID group = seedProviderGroup("Referat 50", provider);
    seedGroupGrant(group);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(released(group)).isFalse();
  }

  /** The migration decides about the flag and about nothing else. */
  @Test
  void theMigrationTouchesNeitherMembershipsNorGrants() throws Exception {
    UUID group = seedInternalGroup("Mit Grant");
    UUID grant = seedGroupGrant(group);
    UUID member = seedUser();
    UUID membership = UUID.randomUUID();
    execute(
        "INSERT INTO group_memberships (id, user_id, group_id, organization_id)"
            + " VALUES (?, ?, ?, ?)",
        membership,
        member,
        group,
        DEFAULT_ORGANIZATION);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(count("SELECT count(*) FROM group_memberships WHERE id = '" + membership + "'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM asset_grants WHERE id = '"
                    + grant
                    + "' AND subject_group_id = '"
                    + group
                    + "'"))
        .isEqualTo(1);
  }

  /** After the changeset a newly created group starts unreleased - the prospective rule. */
  @Test
  void afterTheChangesetANewGroupStartsUnreleased() throws Exception {
    applyChangelog(connection, CHANGELOG_PATH);

    UUID group = seedInternalGroup("Neu");

    assertThat(released(group)).isFalse();
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
    assertThat(master.indexOf(SPACE_MEMBERSHIP_SUBJECT_PATH))
        .as("the backfill reads space_memberships.group_id, which 043 adds")
        .isLessThan(master.indexOf(CHANGELOG_PATH));
  }

  // -----------------------------------------------------------------------------------------
  // Fixture
  // -----------------------------------------------------------------------------------------

  private UUID seedInternalGroup(String name) throws SQLException {
    UUID group = UUID.randomUUID();
    execute(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'AD_HOC', ?)",
        group,
        DEFAULT_ORGANIZATION,
        name);
    return group;
  }

  private UUID seedProvider() throws SQLException {
    UUID provider = UUID.randomUUID();
    execute(
        "INSERT INTO oidc_providers (id, display_name, issuer_uri, client_id) VALUES (?, ?, ?, ?)",
        provider,
        "Haus A",
        "https://a.example/" + provider,
        "opaa-frontend");
    return provider;
  }

  private UUID seedProviderGroup(String name, UUID providerId) throws SQLException {
    UUID group = UUID.randomUUID();
    execute(
        "INSERT INTO groups (id, organization_id, kind, name, provider_id, external_id)"
            + " VALUES (?, ?, 'IDENTITY_PROVIDER', ?, ?, ?)",
        group,
        DEFAULT_ORGANIZATION,
        name,
        providerId,
        name);
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

  private UUID seedLibraryOwnedByGroup(UUID groupId) throws SQLException {
    UUID library = UUID.randomUUID();
    execute(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_group_id,"
            + " visibility, source_type) VALUES (?, ?, ?, 'GROUP', ?, 'PRIVATE', 'UPLOAD')",
        library,
        DEFAULT_ORGANIZATION,
        "Bibliothek " + library,
        groupId);
    return library;
  }

  private UUID seedGroupSpaceMembership(UUID groupId) throws SQLException {
    UUID owner = seedUser();
    UUID space = UUID.randomUUID();
    execute(
        "INSERT INTO spaces (id, organization_id, name, owner_id) VALUES (?, ?, ?, ?)",
        space,
        DEFAULT_ORGANIZATION,
        "Space " + space,
        owner);
    UUID membership = UUID.randomUUID();
    execute(
        "INSERT INTO space_memberships (id, space_id, organization_id, role, subject_type,"
            + " group_id, member_count_at_grant) VALUES (?, ?, ?, 'MEMBER', 'GROUP', ?, 0)",
        membership,
        space,
        DEFAULT_ORGANIZATION,
        groupId);
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
    // The fixture chain stops before 038, so a grant still names its library directly.
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

  private boolean released(UUID groupId) throws SQLException {
    return count("SELECT count(*) FROM groups WHERE id = '" + groupId + "' AND released_for_use")
        == 1;
  }

  private long columnCount(String column) throws SQLException {
    return count(
        "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema()"
            + " AND table_name = 'groups' AND column_name = '"
            + column
            + "'");
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      statement.executeUpdate();
    }
  }

  private long count(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }
}
