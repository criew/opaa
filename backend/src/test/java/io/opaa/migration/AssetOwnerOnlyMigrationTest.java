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

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void existingAssetsStayShareableAndKeepTheirGrants() throws Exception {
    UUID owner = insertUser();
    UUID asset = insertAsset("KNOWLEDGE_LIBRARY", owner);
    UUID group = insertInternalGroup();
    execute(grant(asset, "GROUP", null, group, "VIEWER"));

    applyChangelog(connection, FILE);

    assertThat(booleanOf("SELECT owner_only FROM assets WHERE id = '" + asset + "'")).isFalse();
    assertThat(countWhere("asset_grants", "asset_id = '" + asset + "'")).isEqualTo(1);
    UUID other = insertUser();
    execute(grant(asset, "USER", other, null, "EDITOR"));
    execute(grant(asset, "ALL_ACCOUNTS", null, null, "VIEWER"));
    execute("UPDATE assets SET owner_user_id = '" + other + "' WHERE id = '" + asset + "'");
    assertThat(countWhere("asset_grants", "asset_id = '" + asset + "'")).isEqualTo(3);
  }

  @Test
  void aGroupOwnedAssetCannotBeOwnerOnly() throws Exception {
    applyChangelog(connection, FILE);
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
