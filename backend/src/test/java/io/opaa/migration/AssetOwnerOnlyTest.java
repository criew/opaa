package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The mark "nur Besitzerin" on the asset shell: an asset is shareable unless created owner-only, so
 * owner changes, grants to anyone and a release for Fremdzugänge pass for it, and the mark itself
 * is refused for a group owner.
 */
class AssetOwnerOnlyTest extends AbstractBaselineTest {

  @Test
  void anAssetIsShareableByDefault() throws Exception {
    UUID owner = insertUser();
    UUID asset = insertAsset("KNOWLEDGE_LIBRARY", owner);

    assertThat(booleanOf("SELECT owner_only FROM assets WHERE id = '" + asset + "'")).isFalse();
    UUID other = insertUser();
    execute(grant(asset, "GROUP", null, insertInternalGroup(), "VIEWER"));
    execute(grant(asset, "USER", other, null, "EDITOR"));
    execute(grant(asset, "ALL_ACCOUNTS", null, null, "VIEWER"));
    execute("UPDATE assets SET owner_user_id = '" + other + "' WHERE id = '" + asset + "'");
    assertThat(countWhere("asset_grants", "asset_id = '" + asset + "'")).isEqualTo(3);

    UUID released = insertLibrary();
    execute(release(released));
    execute(
        "UPDATE knowledge_libraries SET external_access_expires_at = now() + interval '60 days'"
            + " WHERE id = '"
            + released
            + "'");
    assertThat(
            countWhere(
                "knowledge_libraries",
                "external_access_state = 'ACTIVE' AND id = '" + released + "'"))
        .isEqualTo(1);
  }

  @Test
  void aGroupOwnedAssetCannotBeOwnerOnly() throws Exception {
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

  /** Releases a library for Fremdzugänge for thirty days. */
  private static String release(UUID library) {
    return "UPDATE knowledge_libraries SET external_access_state = 'ACTIVE',"
        + " external_access_expires_at = now() + interval '30 days',"
        + " external_access_set_at = now() WHERE id = '"
        + library
        + "'";
  }

  private static String grant(UUID asset, String subjectType, UUID user, UUID group, String role) {
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
