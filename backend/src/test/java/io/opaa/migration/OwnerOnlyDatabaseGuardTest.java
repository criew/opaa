package io.opaa.migration;

import static io.opaa.migration.AssetOwnerOnlyMigrationTest.grant;
import static io.opaa.migration.AssetOwnerOnlyMigrationTest.release;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The database half of the Nur-Besitzerin-Regel, independent of any code path: an owner-only asset
 * admits its owner's own grant and nothing else, and neither its mark nor its owner change. What
 * the application writes when it creates such an asset - the owner's {@code OWNER} grant and the
 * history intervals - passes.
 */
class OwnerOnlyDatabaseGuardTest extends AbstractBaselineTest {

  private UUID owner;
  private UUID asset;

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(
        AssetOwnerOnlyMigrationTest.FILE, AssetOwnerOnlyMigrationTest.RELEASE_FILE);
  }

  @BeforeEach
  void anOwnerOnlyAssetWithItsCreationRows() throws Exception {
    applyChangelog(connection, AssetOwnerOnlyMigrationTest.FILE);
    applyChangelog(connection, AssetOwnerOnlyMigrationTest.RELEASE_FILE);
    owner = insertUser();
    asset = UUID.randomUUID();
    execute(
        "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_user_id,"
            + " owner_only) VALUES ('"
            + asset
            + "', 'KNOWLEDGE_LIBRARY', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'Privat', 'USER', '"
            + owner
            + "', true)");
    execute(grant(asset, "USER", owner, null, "OWNER"));
    execute(
        "INSERT INTO asset_grant_history (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_user_id, role, cause, actor_user_id, valid_from) VALUES"
            + " (gen_random_uuid(), 'KNOWLEDGE_LIBRARY', '"
            + asset
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'USER', '"
            + owner
            + "', 'OWNER', 'GRANTED', '"
            + owner
            + "', now())");
    execute(
        "INSERT INTO asset_ownership_history (id, asset_type, asset_id, organization_id,"
            + " owner_type, owner_user_id, cause, actor_user_id, valid_from) VALUES"
            + " (gen_random_uuid(), 'KNOWLEDGE_LIBRARY', '"
            + asset
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'USER', '"
            + owner
            + "', 'CREATED', '"
            + owner
            + "', now())");
    execute(
        "INSERT INTO knowledge_libraries (id, organization_id, source_type) VALUES ('"
            + asset
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'UPLOAD')");
  }

  @Test
  void aPrivateLibraryIsNeverReleasedForExternalAccess() throws Exception {
    assertRejected(release(asset), "never released for external access");

    UUID shared = insertLibrary();
    execute(release(shared));
    assertThat(
            countWhere(
                "knowledge_libraries",
                "id = '" + shared + "' AND external_access_state" + " = 'ACTIVE'"))
        .isEqualTo(1);
  }

  @Test
  void aPrivateLibraryCannotBeCreatedReleased() throws Exception {
    UUID other = UUID.randomUUID();
    execute(
        "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_user_id,"
            + " owner_only) VALUES ('"
            + other
            + "', 'KNOWLEDGE_LIBRARY', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'Privat 2', 'USER', '"
            + owner
            + "', true)");

    assertRejected(
        "INSERT INTO knowledge_libraries (id, organization_id, source_type, external_access_state,"
            + " external_access_expires_at, external_access_set_at) VALUES ('"
            + other
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'UPLOAD', 'ACTIVE', now() + interval '30 days', now())",
        "never released for external access");
  }

  @Test
  void theOwnersOwnGrantStaysChangeable() throws Exception {
    execute(
        "UPDATE asset_grants SET role = 'OWNER', updated_at = now() WHERE asset_id = '"
            + asset
            + "'");
    execute("UPDATE assets SET name = 'Umbenannt' WHERE id = '" + asset + "'");

    assertThat(countWhere("asset_grants", "asset_id = '" + asset + "'")).isEqualTo(1);
  }

  @Test
  void aGrantToAnotherPersonIsRefused() throws Exception {
    assertRejected(
        grant(asset, "USER", insertUser(), null, "VIEWER"), "admits no grant but its owner's");
  }

  @Test
  void aGrantToAGroupIsRefused() throws Exception {
    assertRejected(
        grant(asset, "GROUP", null, insertInternalGroup(), "VIEWER"),
        "admits no grant but its owner's");
  }

  @Test
  void aGrantToAllAccountsIsRefused() {
    assertRejected(grant(asset, "ALL_ACCOUNTS", null, null, "VIEWER"), "admits no grant");
  }

  @Test
  void movingTheOwnersGrantToAnotherPersonIsRefused() throws Exception {
    UUID other = insertUser();

    assertRejected(
        "UPDATE asset_grants SET subject_user_id = '"
            + other
            + "' WHERE asset_id = '"
            + asset
            + "'",
        "admits no grant but its owner's");
  }

  @Test
  void aChangeOfOwnerIsRefused() throws Exception {
    UUID other = insertUser();

    assertRejected(
        "UPDATE assets SET owner_user_id = '" + other + "' WHERE id = '" + asset + "'",
        "cannot change");
    assertRejected(
        "UPDATE assets SET owner_type = 'GROUP', owner_user_id = NULL, owner_group_id = '"
            + insertInternalGroup()
            + "' WHERE id = '"
            + asset
            + "'",
        "cannot change");
  }

  @Test
  void theMarkCannotBeLiftedOrSetAfterCreation() throws Exception {
    assertRejected(
        "UPDATE assets SET owner_only = false WHERE id = '" + asset + "'", "is fixed at creation");

    UUID shared = insertAsset("KNOWLEDGE_LIBRARY", owner);
    assertRejected(
        "UPDATE assets SET owner_only = true WHERE id = '" + shared + "'", "is fixed at creation");
  }
}
