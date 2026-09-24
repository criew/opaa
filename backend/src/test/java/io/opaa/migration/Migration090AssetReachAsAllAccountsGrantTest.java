package io.opaa.migration;

import static io.opaa.migration.AssetShellMigrationFixtures.DEFAULT_ORGANIZATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/090-asset-reach-as-all-accounts-grant.yaml} (#1931, ADR-0037):
 * {@code assets.visibility} goes, organization-wide reach becomes a grant to {@code ALL_ACCOUNTS},
 * and the share cap becomes a boolean. The test the whole changeset exists for is {@link
 * #everyOrganizationWideIntervalBecomesAGrantInterval}: without it the Stichtagsauskunft would
 * answer "no access" for periods in which access existed.
 */
class Migration090AssetReachAsAllAccountsGrantTest extends AbstractMigrationTest {

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/090-asset-reach-as-all-accounts-grant.yaml";

  private Connection connection;
  private AssetShellMigrationFixtures fixtures;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-087.yaml";
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
  void beforeTheChangesetTheShellStillCarriesVisibility() throws Exception {
    assertThat(fixtures.columnExists("assets", "visibility")).isTrue();
    assertThat(fixtures.columnExists("knowledge_libraries", "visibility_cap")).isTrue();
    assertThat(fixtures.columnExists("knowledge_libraries", "all_accounts_grant_allowed"))
        .isFalse();
  }

  @Test
  void anOrganizationWideAssetKeepsItsReachAsAGrantToAllAccounts() throws Exception {
    UUID owner = fixtures.user();
    UUID organizationWide = organizationWideAsset(owner);
    UUID privateAsset = fixtures.shellLibrary(owner);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(fixtures.columnExists("assets", "visibility")).isFalse();
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM asset_grants WHERE asset_id = ? AND subject_type ="
                    + " 'ALL_ACCOUNTS' AND role = 'VIEWER' AND subject_user_id IS NULL AND"
                    + " subject_group_id IS NULL",
                organizationWide))
        .as("the stage conferred VIEWER to everybody - the grant confers exactly that")
        .isEqualTo(1);
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM asset_grants WHERE asset_id = ? AND subject_type ="
                    + " 'ALL_ACCOUNTS'",
                privateAsset))
        .as("an asset that reached nobody organization-wide gains no grant")
        .isZero();
  }

  /**
   * The point of the whole migration: a closed organization-wide interval is a period in which
   * access existed, and the Stichtagsauskunft answers it from {@code asset_grant_history} from now
   * on. Zero-length markers carry no state and are deliberately left behind.
   */
  @Test
  void everyOrganizationWideIntervalBecomesAGrantInterval() throws Exception {
    UUID owner = fixtures.user();
    UUID asset = organizationWideAsset(owner);
    visibilityInterval(asset, "PRIVATE", "CREATED", owner, "2026-01-01", "2026-02-01");
    visibilityInterval(
        asset, "ORGANIZATION", "VISIBILITY_CHANGED", owner, "2026-02-01", "2026-03-01");
    visibilityInterval(asset, "PRIVATE", "VISIBILITY_CHANGED", owner, "2026-03-01", null);
    // a zero-length deletion marker of an organization-wide asset: an event, never a state
    visibilityInterval(asset, "ORGANIZATION", "ASSET_DELETED", owner, "2026-03-01", "2026-03-01");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(
            fixtures.count(
                "SELECT count(*) FROM asset_grant_history WHERE asset_id = ? AND subject_type ="
                    + " 'ALL_ACCOUNTS'",
                asset))
        .as("one interval per organization-wide state, the marker excluded")
        .isEqualTo(1);
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM asset_grant_history WHERE asset_id = ? AND subject_type ="
                    + " 'ALL_ACCOUNTS' AND role = 'VIEWER' AND cause = 'BACKFILL' AND"
                    + " actor_user_id = ? AND valid_from = timestamptz '2026-02-01 00:00:00+00'"
                    + " AND valid_to = timestamptz '2026-03-01 00:00:00+00'",
                asset,
                owner))
        .as("the closed period is reconstructable to the instant, with its actor")
        .isEqualTo(1);
    assertThat(fixtures.columnExists("asset_visibility_history", "visibility")).isFalse();
    assertThat(fixtures.columnExists("asset_visibility_history", "listed")).isTrue();
    assertThat(fixtures.columnExists("asset_visibility_history", "external_access_state")).isTrue();
  }

  @Test
  void anOpenOrganizationWideIntervalBecomesAnOpenGrantInterval() throws Exception {
    UUID owner = fixtures.user();
    UUID asset = organizationWideAsset(owner);
    visibilityInterval(asset, "ORGANIZATION", "CREATED", owner, "2026-02-01", null);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(
            fixtures.count(
                "SELECT count(*) FROM asset_grant_history WHERE asset_id = ? AND subject_type ="
                    + " 'ALL_ACCOUNTS' AND valid_to IS NULL",
                asset))
        .isEqualTo(1);
  }

  @Test
  void onlyOneGrantToAllAccountsPerAssetIsStorable() throws Exception {
    UUID owner = fixtures.user();
    UUID asset = organizationWideAsset(owner);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(() -> allAccountsGrant(asset))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_asset_grants_all_accounts");
  }

  @Test
  void aGrantToAllAccountsMayNameNoSubject() throws Exception {
    UUID owner = fixtures.user();
    UUID asset = fixtures.shellLibrary(owner);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () ->
                fixtures.execute(
                    "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id,"
                        + " subject_type, subject_user_id, role) VALUES (?, 'KNOWLEDGE_LIBRARY',"
                        + " ?, ?, 'ALL_ACCOUNTS', ?, 'VIEWER')",
                    UUID.randomUUID(),
                    asset,
                    DEFAULT_ORGANIZATION,
                    owner))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_asset_grants_subject");
  }

  @Test
  void theShareCapBecomesABooleanAndKeepsWhatItForbade() throws Exception {
    UUID owner = fixtures.user();
    UUID unrestricted = connectorLibrary(owner, "ORGANIZATION");
    UUID restricted = connectorLibrary(owner, "SHARED");

    applyChangelog(connection, CHANGELOG_PATH);

    assertThat(fixtures.columnExists("knowledge_libraries", "visibility_cap")).isFalse();
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM knowledge_libraries WHERE id = ? AND"
                    + " all_accounts_grant_allowed = true",
                unrestricted))
        .isEqualTo(1);
    assertThat(
            fixtures.count(
                "SELECT count(*) FROM knowledge_libraries WHERE id = ? AND"
                    + " all_accounts_grant_allowed = false",
                restricted))
        .as("a cap that forbade the organization-wide stage forbids the grant to all accounts")
        .isEqualTo(1);
  }

  @Test
  void anUploadLibraryStaysUnrestrictedByTheDatabaseItself() throws Exception {
    UUID owner = fixtures.user();
    UUID upload = fixtures.shellLibrary(owner);

    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () ->
                fixtures.execute(
                    "UPDATE knowledge_libraries SET all_accounts_grant_allowed = false WHERE id ="
                        + " ?",
                    upload))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_share_cap_upload_unrestricted");
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(AssetShellMigrationFixtures.masterChangelog()).contains(CHANGELOG_PATH);
  }

  // -----------------------------------------------------------------------------------------

  private UUID organizationWideAsset(UUID owner) throws SQLException {
    UUID asset = fixtures.shellLibrary(owner);
    fixtures.execute("UPDATE assets SET visibility = 'ORGANIZATION' WHERE id = ?", asset);
    return asset;
  }

  private UUID connectorLibrary(UUID owner, String visibilityCap) throws SQLException {
    UUID library = UUID.randomUUID();
    fixtures.execute(
        "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_user_id,"
            + " visibility) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, 'Konnektor', 'USER', ?, 'PRIVATE')",
        library,
        DEFAULT_ORGANIZATION,
        owner);
    fixtures.execute(
        "INSERT INTO knowledge_libraries (id, organization_id, source_type, source_path,"
            + " visibility_cap) VALUES (?, ?, 'FILESYSTEM', '/data/dokumente', ?)",
        library,
        DEFAULT_ORGANIZATION,
        visibilityCap);
    return library;
  }

  private void allAccountsGrant(UUID asset) throws SQLException {
    fixtures.execute(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type, role)"
            + " VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'ALL_ACCOUNTS', 'VIEWER')",
        UUID.randomUUID(),
        asset,
        DEFAULT_ORGANIZATION);
  }

  private void visibilityInterval(
      UUID asset, String visibility, String cause, UUID actor, String from, String to)
      throws SQLException {
    fixtures.execute(
        "INSERT INTO asset_visibility_history (id, asset_type, asset_id, organization_id,"
            + " visibility, listed, external_access_state, cause, actor_user_id, valid_from,"
            + " valid_to) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, ?, false, 'NEVER_SET', ?, ?,"
            + " ?::timestamptz, ?::timestamptz)",
        UUID.randomUUID(),
        asset,
        DEFAULT_ORGANIZATION,
        visibility,
        cause,
        actor,
        from + " 00:00:00+00",
        to == null ? null : to + " 00:00:00+00");
  }
}
