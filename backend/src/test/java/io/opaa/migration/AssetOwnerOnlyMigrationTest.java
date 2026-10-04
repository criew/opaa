package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The mark "nur Besitzerin" on the asset shell, applied to an existing installation: every asset
 * already there stays shareable and keeps its grants, owner changes and grants to anyone still pass
 * for it, and the mark itself is refused for a group owner.
 */
class AssetOwnerOnlyMigrationTest extends AbstractBaselineTest {

  static final String FILE = "db/changelog/rights/2026-10-04-asset-owner-only.yaml";
  static final String RELEASE_FILE =
      "db/changelog/knowledge/2026-10-04-private-library-external-access.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE, RELEASE_FILE);
  }

  @Test
  void existingAssetsStayShareableAndKeepTheirGrants() throws Exception {
    UUID owner = insertUser();
    UUID asset = insertAsset("KNOWLEDGE_LIBRARY", owner);
    UUID group = insertInternalGroup();
    execute(grant(asset, "GROUP", null, group, "VIEWER"));
    UUID released = insertLibrary();
    execute(release(released));

    applyBoth();

    assertThat(booleanOf("SELECT owner_only FROM assets WHERE id = '" + asset + "'")).isFalse();
    assertThat(countWhere("asset_grants", "asset_id = '" + asset + "'")).isEqualTo(1);
    UUID other = insertUser();
    execute(grant(asset, "USER", other, null, "EDITOR"));
    execute(grant(asset, "ALL_ACCOUNTS", null, null, "VIEWER"));
    execute("UPDATE assets SET owner_user_id = '" + other + "' WHERE id = '" + asset + "'");
    assertThat(countWhere("asset_grants", "asset_id = '" + asset + "'")).isEqualTo(3);
    assertThat(
            countWhere(
                "knowledge_libraries",
                "external_access_state = 'ACTIVE' AND id = '" + released + "'"))
        .isEqualTo(1);
    execute(
        "UPDATE knowledge_libraries SET external_access_expires_at = now() + interval '60 days'"
            + " WHERE id = '"
            + released
            + "'");
  }

  @Test
  void aGroupOwnedAssetCannotBeOwnerOnly() throws Exception {
    applyBoth();
    UUID group = insertInternalGroup();

    assertRejected(
        "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_group_id,"
            + " owner_only) VALUES (gen_random_uuid(), 'KNOWLEDGE_LIBRARY', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'Privat', 'GROUP', '"
            + group
            + "', true)",
        "chk_assets_owner_only");
  }

  /** Both files of the mark, in the order an existing installation receives them. */
  void applyBoth() throws Exception {
    applyChangelog(connection, FILE);
    applyChangelog(connection, RELEASE_FILE);
  }

  /** Releases a library for Fremdzugänge for thirty days. */
  static String release(UUID library) {
    return "UPDATE knowledge_libraries SET external_access_state = 'ACTIVE',"
        + " external_access_expires_at = now() + interval '30 days',"
        + " external_access_set_at = now() WHERE id = '"
        + library
        + "'";
  }

  static String grant(UUID asset, String subjectType, UUID user, UUID group, String role) {
    return "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type,"
        + " subject_user_id, subject_group_id, role) VALUES (gen_random_uuid(),"
        + " 'KNOWLEDGE_LIBRARY', '"
        + asset
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + subjectType
        + "', "
        + quoted(user)
        + ", "
        + quoted(group)
        + ", '"
        + role
        + "')";
  }
}
