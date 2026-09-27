package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Invariants of the baseline's rights changeSet (ADR-0036, ADR-0037): groups and their directory
 * synchronisation, the asset shell with its grants, capabilities, the permission history with its
 * retention and transfers, account states and succession cases.
 */
class RightsBaselineTest extends AbstractBaselineTest {

  private static final List<String> DELIVERED_CAPABILITIES =
      List.of(
          "CREATE_CONNECTOR_LIBRARY", "CREATE_LIBRARY", "CREATE_PROMPT_LIBRARY", "CREATE_SPACE");

  // ---------------------------------------------------------------------------------------------
  // Groups
  // ---------------------------------------------------------------------------------------------

  /** ADR-0036, Entscheidung 2: a group has a provider exactly when it is not internal. */
  @Test
  void aGroupHasAProviderExactlyWhenItIsNotInternal() throws SQLException {
    UUID provider = insertProvider();
    insertGroup("AD_HOC", null, null);
    insertGroup("ORG_UNIT", provider, "/Haus/Referat 50");
    insertGroup("IDENTITY_PROVIDER", provider, "Sachbearbeitung");

    assertRejected(groupSql("AD_HOC", provider, null), "chk_groups_provider_kind");
    assertRejected(groupSql("ORG_UNIT", null, "ou-1"), "chk_groups_provider_kind");
    assertRejected(groupSql("IDENTITY_PROVIDER", null, "idp-1"), "chk_groups_provider_kind");
    assertRejected(groupSql("EVERYONE", provider, "alle"), "chk_groups_kind");
  }

  /**
   * The external id is keyed per provider and kind, so two providers delivering a group of the same
   * name yield two groups, and the same name collides only within one provider.
   */
  @Test
  void sameNamedGroupsOfTwoProvidersStaySeparateAndCollideOnlyWithinOneProvider()
      throws SQLException {
    UUID first = insertProvider();
    UUID second = insertProvider();
    insertGroup("IDENTITY_PROVIDER", first, "Sachbearbeitung");
    insertGroup("IDENTITY_PROVIDER", second, "Sachbearbeitung");
    insertGroup("ORG_UNIT", first, "Sachbearbeitung");

    assertRejected(
        groupSql("IDENTITY_PROVIDER", first, "Sachbearbeitung"),
        "uk_groups_organization_provider_kind_external_id");
  }

  /** An internal group has no id from a source, so internal groups never collide on one. */
  @Test
  void internalGroupsWithoutAnExternalIdNeverCollide() throws SQLException {
    insertInternalGroup();
    insertInternalGroup();

    assertThat(countWhere("groups", "kind = 'AD_HOC'")).isEqualTo(2);
  }

  @Test
  void aProviderThatStillHasGroupsCannotBeDeleted() throws SQLException {
    UUID provider = insertProvider();
    insertGroup("IDENTITY_PROVIDER", provider, "Sachbearbeitung");

    assertRejected(
        "DELETE FROM oidc_providers WHERE id = '" + provider + "'", "fk_groups_provider");
  }

  /**
   * Findability of an internal group is a deliberate act of its stewards, and the protection mark
   * is set by the responsible body only - so a new group is neither released nor protected.
   */
  @Test
  void aNewGroupStartsUnreleasedAndUnprotectedAndNeitherMarkCanBeEmptied() throws SQLException {
    UUID group = insertInternalGroup();

    assertThat(booleanOf("SELECT released_for_use FROM groups WHERE id = '" + group + "'"))
        .isFalse();
    assertThat(booleanOf("SELECT protected FROM groups WHERE id = '" + group + "'")).isFalse();
    assertRejected("UPDATE groups SET protected = NULL WHERE id = '" + group + "'", "protected");
    assertRejected(
        "UPDATE groups SET released_for_use = NULL WHERE id = '" + group + "'", "released_for_use");
  }

  @Test
  void aPersonStewardsAGroupOnceAndTheStewardshipGoesWithTheGroup() throws SQLException {
    UUID group = insertInternalGroup();
    UUID steward = insertUser();
    execute(stewardSql(group, steward, null));

    assertRejected(stewardSql(group, steward, null), "uk_group_stewards_group_user");
    execute("DELETE FROM groups WHERE id = '" + group + "'");
    assertThat(countRows("group_stewards")).isZero();
  }

  /** ADR-0016: the person columns restrict, and the composite keys hold the organization. */
  @Test
  void aStewardAndTheAppointerAreAccountsOfTheGroupsOrganizationThatCannotBeDeleted()
      throws SQLException {
    UUID group = insertInternalGroup();
    UUID steward = insertUser();
    UUID appointer = insertUser();
    execute(stewardSql(group, steward, appointer));

    assertRejected(
        "DELETE FROM users WHERE id = '" + steward + "'", "fk_group_stewards_user_organization");
    assertRejected(
        "DELETE FROM users WHERE id = '" + appointer + "'",
        "fk_group_stewards_appointed_by_organization");
    UUID stranger = insertUser(insertOrganization());
    assertRejected(stewardSql(group, stranger, null), "fk_group_stewards_user_organization");
  }

  // ---------------------------------------------------------------------------------------------
  // Directory synchronisation per provider
  // ---------------------------------------------------------------------------------------------

  /**
   * Status line, pending plan and directory access belong to a provider: one each per provider, and
   * all three go with it.
   */
  @Test
  void eachProviderHoldsOneStatusLinePendingPlanAndDirectoryAccessThatGoWithIt()
      throws SQLException {
    UUID first = insertProvider();
    UUID second = insertProvider();
    for (UUID provider : List.of(first, second)) {
      execute(statusSql(provider));
      execute(pendingPlanSql(provider));
      execute(directoryAccessSql(provider, "KEYCLOAK", "enc:v1:abc"));
    }

    assertRejected(statusSql(first), "uk_directory_sync_status_organization_provider");
    assertRejected(pendingPlanSql(first), "uk_directory_sync_pending_plans_provider");
    assertRejected(
        directoryAccessSql(first, "KEYCLOAK", "enc:v1:def"), "uk_directory_connectors_provider");
    assertThat(longOf("SELECT accounts_locked FROM directory_sync_pending_plans LIMIT 1")).isZero();

    execute("DELETE FROM oidc_providers WHERE id = '" + first + "'");
    for (String table :
        List.of("directory_sync_status", "directory_sync_pending_plans", "directory_connectors")) {
      assertThat(countWhere(table, "provider_id = '" + first + "'")).as(table).isZero();
      assertThat(countWhere(table, "provider_id = '" + second + "'")).as(table).isEqualTo(1);
    }
  }

  /** The secret column holds the ciphertext only, and a connector type no code knows is refused. */
  @Test
  void aDirectoryAccessNeedsAKnownConnectorTypeAndAnEncryptedSecret() throws SQLException {
    UUID provider = insertProvider();

    assertRejected(
        directoryAccessSql(provider, "KEYCLOAK", "geheim"),
        "chk_directory_connectors_secret_encrypted");
    assertRejected(
        directoryAccessSql(provider, "LDAP", "enc:v1:abc"), "chk_directory_connectors_type");
  }

  // ---------------------------------------------------------------------------------------------
  // Asset shell and grants
  // ---------------------------------------------------------------------------------------------

  /**
   * The set of asset types belongs to the domain packages, so no table carries a value list - but
   * every type-independent column checks the form AssetType accepts.
   */
  @Test
  void everyTypeIndependentTableAcceptsOnlyAnEnumShapedAssetType() throws SQLException {
    UUID owner = insertUser();
    assertRejected(assetSql("knowledge-library", owner), "chk_assets_asset_type_format");
    UUID asset = insertAsset("KNOWLEDGE_LIBRARY", owner);
    UUID transfer = insertPermissionTransfer();

    assertRejected(
        grantHistorySql("knowledgeLibrary", asset, "USER", owner, "VIEWER", "GRANTED", null),
        "chk_asset_grant_history_asset_type_format");
    assertRejected(
        ownershipSql("Space", asset, owner, "CREATED"),
        "chk_asset_ownership_history_asset_type_format");
    assertRejected(
        visibilitySql("1LIBRARY", asset, "CREATED"),
        "chk_asset_visibility_history_asset_type_format");
    assertRejected(
        transferObjectSql(transfer, "prompt library", asset),
        "chk_permission_transfer_objects_asset_type_format");
  }

  /** ADR-0016: an account or a group that still owns an asset cannot be deleted. */
  @Test
  void anAccountOrGroupThatStillOwnsAnAssetCannotBeDeleted() throws SQLException {
    UUID owner = insertUser();
    insertAsset("KNOWLEDGE_LIBRARY", owner);
    UUID group = insertInternalGroup();
    execute(
        "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_group_id)"
            + " VALUES (gen_random_uuid(), 'PROMPT_LIBRARY', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'Prompts', 'GROUP', '"
            + group
            + "')");

    assertRejected(
        "DELETE FROM users WHERE id = '" + owner + "'", "fk_assets_owner_user_organization");
    assertRejected(
        "DELETE FROM groups WHERE id = '" + group + "'", "fk_assets_owner_group_organization");
  }

  /**
   * One foreign key on the shell carries all three promises for every asset type: the asset exists,
   * it has the type the grant names, and no grant outlives it.
   */
  @Test
  void aGrantNeedsAnExistingAssetOfItsOwnTypeAndDiesWithIt() throws SQLException {
    UUID owner = insertUser();
    UUID library = insertAsset("KNOWLEDGE_LIBRARY", owner);
    UUID prompts = insertAsset("PROMPT_LIBRARY", owner);
    execute(grantSql("KNOWLEDGE_LIBRARY", library, "USER", owner, null));
    execute(grantSql("PROMPT_LIBRARY", prompts, "USER", owner, null));

    assertRejected(
        grantSql("KNOWLEDGE_LIBRARY", UUID.randomUUID(), "USER", owner, null),
        "fk_asset_grants_asset_organization");
    assertRejected(
        grantSql("PROMPT_LIBRARY", library, "USER", insertUser(), null),
        "fk_asset_grants_asset_organization");

    execute("DELETE FROM assets WHERE id = '" + library + "'");
    assertThat(countWhere("asset_grants", "asset_id = '" + library + "'")).isZero();
    assertThat(countWhere("asset_grants", "asset_id = '" + prompts + "'")).isEqualTo(1);
  }

  /** ADR-0037: organisation-wide reach is one grant to all accounts, naming no subject. */
  @Test
  void aGrantToAllAccountsNamesNoSubjectAndExistsOncePerAsset() throws SQLException {
    UUID owner = insertUser();
    UUID asset = insertAsset("KNOWLEDGE_LIBRARY", owner);
    execute(grantSql("KNOWLEDGE_LIBRARY", asset, "ALL_ACCOUNTS", null, null));

    assertRejected(
        grantSql("KNOWLEDGE_LIBRARY", asset, "ALL_ACCOUNTS", null, null),
        "uk_asset_grants_all_accounts");
    assertRejected(
        grantSql(
            "KNOWLEDGE_LIBRARY",
            insertAsset("KNOWLEDGE_LIBRARY", owner),
            "ALL_ACCOUNTS",
            owner,
            null),
        "chk_asset_grants_subject");
    assertRejected(
        grantSql("KNOWLEDGE_LIBRARY", asset, "USER", null, null), "chk_asset_grants_subject");
  }

  /** ADR-0036, Entscheidung 9: the size of a group at the moment of the grant, never a person's. */
  @Test
  void onlyAGroupGrantCarriesANonNegativeMemberCount() throws SQLException {
    UUID owner = insertUser();
    UUID asset = insertAsset("KNOWLEDGE_LIBRARY", owner);
    UUID group = insertInternalGroup();
    execute(grantSql("KNOWLEDGE_LIBRARY", asset, "GROUP", group, 23));

    assertRejected(
        grantSql("KNOWLEDGE_LIBRARY", asset, "USER", insertUser(), 1),
        "chk_asset_grants_member_count");
    assertRejected(
        grantSql("KNOWLEDGE_LIBRARY", asset, "GROUP", insertInternalGroup(), -1),
        "chk_asset_grants_member_count");
  }

  // ---------------------------------------------------------------------------------------------
  // Permission history (ADR-0016, ADR-0036 Entscheidung 8)
  // ---------------------------------------------------------------------------------------------

  /**
   * The object columns of the history carry no foreign key: an interval outlives its asset, and two
   * asset types sharing an id each keep their own open interval.
   */
  @Test
  void theGrantHistoryOutlivesItsAssetAndKeepsOneOpenIntervalPerTypeAndId() throws SQLException {
    UUID owner = insertUser();
    UUID asset = insertAsset("KNOWLEDGE_LIBRARY", owner);
    execute(grantHistorySql("KNOWLEDGE_LIBRARY", asset, "USER", owner, "OWNER", "GRANTED", null));
    execute(grantHistorySql("PROMPT_LIBRARY", asset, "USER", owner, "OWNER", "GRANTED", null));

    assertRejected(
        grantHistorySql("KNOWLEDGE_LIBRARY", asset, "USER", owner, "VIEWER", "GRANTED", null),
        "uk_asset_grant_history_open_user");
    execute("DELETE FROM assets WHERE id = '" + asset + "'");
    assertThat(countWhere("asset_grant_history", "asset_id = '" + asset + "'")).isEqualTo(2);
  }

  /**
   * The history knows the stricken role no more, calls a deletion ASSET_DELETED for every type, and
   * records both sides of a transfer.
   */
  @Test
  void theGrantHistoryKnowsTheCurrentRolesAndCausesOnly() throws SQLException {
    UUID owner = insertUser();
    UUID asset = insertAsset("KNOWLEDGE_LIBRARY", owner);

    assertRejected(
        grantHistorySql("KNOWLEDGE_LIBRARY", asset, "USER", owner, "USER", "GRANTED", null),
        "chk_asset_grant_history_role");
    assertRejected(
        grantHistorySql(
            "KNOWLEDGE_LIBRARY", asset, "USER", owner, "VIEWER", "LIBRARY_DELETED", "now()"),
        "chk_asset_grant_history_cause");
    for (String cause : List.of("ASSET_DELETED", "TRANSFERRED_OUT", "TRANSFERRED_IN", "BACKFILL")) {
      execute(grantHistorySql("KNOWLEDGE_LIBRARY", asset, "USER", owner, "VIEWER", cause, "now()"));
    }
  }

  /**
   * The visibility history keeps listed state and external-access release per asset: one open
   * interval per type and id, deletions recorded as ASSET_DELETED, releases with their own causes.
   */
  @Test
  void theVisibilityHistoryKnowsItsCausesAndHoldsOneOpenIntervalPerAsset() throws SQLException {
    UUID asset = insertAsset("KNOWLEDGE_LIBRARY", insertUser());
    execute(visibilitySql("KNOWLEDGE_LIBRARY", asset, "CREATED"));
    execute(visibilitySql("PROMPT_LIBRARY", asset, "CREATED"));

    assertRejected(
        visibilitySql("KNOWLEDGE_LIBRARY", asset, "VISIBILITY_CHANGED"),
        "uk_asset_visibility_history_open");
    execute(
        "UPDATE asset_visibility_history SET valid_to = now() WHERE asset_type = 'KNOWLEDGE_LIBRARY'");
    assertRejected(
        visibilitySql("KNOWLEDGE_LIBRARY", asset, "LIBRARY_DELETED"),
        "chk_asset_visibility_history_cause");
    for (String cause :
        List.of("EXTERNAL_ACCESS_CHANGED", "EXTERNAL_ACCESS_EXPIRED", "ASSET_DELETED")) {
      execute(visibilitySql("KNOWLEDGE_LIBRARY", asset, cause));
      execute(
          "UPDATE asset_visibility_history SET valid_to = now()"
              + " WHERE valid_to IS NULL AND asset_type = 'KNOWLEDGE_LIBRARY'");
    }
  }

  @Test
  void theOwnershipHistoryHoldsOneOwnerAndOneOpenIntervalPerAssetAndBlocksDeletingTheOwner()
      throws SQLException {
    UUID owner = insertUser();
    UUID asset = insertAsset("KNOWLEDGE_LIBRARY", owner);
    execute(ownershipSql("KNOWLEDGE_LIBRARY", asset, owner, "CREATED"));

    assertRejected(
        ownershipSql("KNOWLEDGE_LIBRARY", asset, insertUser(), "TRANSFERRED"),
        "uk_asset_ownership_history_open");
    assertRejected(
        "INSERT INTO asset_ownership_history (id, asset_type, asset_id, organization_id,"
            + " owner_type, cause, valid_from) VALUES (gen_random_uuid(), 'SPACE', gen_random_uuid(),"
            + " '"
            + SEEDED_ORGANIZATION_ID
            + "', 'USER', 'CREATED', now())",
        "chk_asset_ownership_history_owner");
    execute("DELETE FROM assets WHERE id = '" + asset + "'");
    assertRejected(
        "DELETE FROM users WHERE id = '" + owner + "'",
        "fk_asset_ownership_history_owner_user_organization");
  }

  @Test
  void thePermissionHistoryRetentionIsASingletonOfThreeYearsBoundedToOneToTenYears()
      throws SQLException {
    assertThat(longOf("SELECT retention_months FROM permission_history_retention_settings"))
        .isEqualTo(36);
    assertThat(
            booleanOf(
                "SELECT last_cutoff = (date_trunc('month', updated_at AT TIME ZONE 'UTC')"
                    + " - interval '36 months') AT TIME ZONE 'UTC'"
                    + " FROM permission_history_retention_settings WHERE id = 1"))
        .as("the deletion progress starts one retention period before the installation month")
        .isTrue();

    assertRejected(
        "UPDATE permission_history_retention_settings SET retention_months = 11",
        "chk_permission_history_retention_months");
    assertRejected(
        "UPDATE permission_history_retention_settings SET retention_months = 121",
        "chk_permission_history_retention_months");
    execute("UPDATE permission_history_retention_settings SET retention_months = 12");
    execute("UPDATE permission_history_retention_settings SET retention_months = 120");
    assertRejected(
        "INSERT INTO permission_history_retention_settings (id, retention_months, updated_at)"
            + " VALUES (2, 36, now())",
        "chk_permission_history_retention_singleton");
  }

  /**
   * ADR-0015's restricted ownership covers the two protocols only; the history tables are written
   * by the application account itself, and so is their retention setting.
   */
  @Test
  void thePermissionHistoryRetentionStaysOwnedByTheMigrationAccount() throws SQLException {
    assertThat(
            stringOf(
                "SELECT tableowner FROM pg_tables WHERE schemaname = current_schema()"
                    + " AND tablename = 'permission_history_retention_settings'"))
        .isNotEqualTo("opaa_audit_owner");
  }

  // ---------------------------------------------------------------------------------------------
  // Capabilities (ADR-0036 Entscheidung 5)
  // ---------------------------------------------------------------------------------------------

  /**
   * Every organization starts with the delivered state - four capabilities for all accounts, each
   * with an open DELIVERED interval - whether it is the seeded one or created later.
   */
  @Test
  void everyOrganizationStartsWithTheFourDeliveredCapabilitiesAndAnOpenIntervalEach()
      throws SQLException {
    UUID later = insertOrganization();

    for (String organization : List.of(SEEDED_ORGANIZATION_ID, later.toString())) {
      assertThat(
              strings(
                  "SELECT capability FROM capability_grants WHERE subject_type = 'ALL_ACCOUNTS'"
                      + " AND organization_id = '"
                      + organization
                      + "' ORDER BY capability"))
          .containsExactlyElementsOf(DELIVERED_CAPABILITIES);
      assertThat(
              strings(
                  "SELECT h.capability FROM capability_grant_history h JOIN capability_grants g"
                      + " USING (organization_id, capability) WHERE h.cause = 'DELIVERED'"
                      + " AND h.subject_type = 'ALL_ACCOUNTS' AND h.valid_to IS NULL"
                      + " AND h.valid_from = g.created_at AND h.organization_id = '"
                      + organization
                      + "' ORDER BY h.capability"))
          .containsExactlyElementsOf(DELIVERED_CAPABILITIES);
    }
    assertThat(countWhere("capability_grants", "capability = 'CREATE_INTERNAL_GROUP'")).isZero();
  }

  @Test
  void capabilityTablesKnowFiveCapabilitiesAndBindEachSubjectTypeToItsOwnColumn()
      throws SQLException {
    UUID user = insertUser();
    UUID group = insertInternalGroup();
    execute(capabilitySql("CREATE_INTERNAL_GROUP", "USER", user, null));
    execute(capabilitySql("CREATE_INTERNAL_GROUP", "GROUP", null, group));

    assertRejected(
        capabilitySql("CREATE_TEMPLATE", "USER", user, null), "chk_capability_grants_capability");
    assertRejected(
        capabilityHistorySql("CREATE_TEMPLATE", "GRANTED", "now()", null),
        "chk_capability_grant_history_capability");
    assertRejected(
        capabilitySql("CREATE_SPACE", "USER", null, group), "chk_capability_grants_subject");
    assertRejected(
        capabilitySql("CREATE_SPACE", "GROUP", user, group), "chk_capability_grants_subject");
    assertRejected(
        capabilitySql("CREATE_SPACE", "ALL_ACCOUNTS", user, null), "chk_capability_grants_subject");
    assertRejected(
        capabilitySql("CREATE_SPACE", "EVERYONE", null, null), "chk_capability_grants_subject");
  }

  @Test
  void aCapabilityIsGrantedOncePerSubjectAndOrganization() throws SQLException {
    UUID user = insertUser();
    execute(capabilitySql("CREATE_INTERNAL_GROUP", "USER", user, null));

    assertRejected(
        capabilitySql("CREATE_SPACE", "ALL_ACCOUNTS", null, null),
        "uk_capability_grants_all_accounts");
    assertRejected(
        capabilitySql("CREATE_INTERNAL_GROUP", "USER", user, null),
        "uk_capability_grants_user_subject");
  }

  /**
   * Capability rows are configuration of the tenant and go with it; a person holding or having
   * granted a capability stays undeletable (ADR-0016).
   */
  @Test
  void capabilitiesGoWithTheirOrganizationButKeepTheirPersonsUndeletable() throws SQLException {
    UUID organization = insertOrganization();
    execute("DELETE FROM organizations WHERE id = '" + organization + "'");
    assertThat(countWhere("capability_grants", "organization_id = '" + organization + "'"))
        .isZero();
    assertThat(countWhere("capability_grant_history", "organization_id = '" + organization + "'"))
        .isZero();

    UUID holder = insertUser();
    UUID granter = insertUser();
    execute(capabilitySql("CREATE_INTERNAL_GROUP", "USER", holder, null, granter));
    assertRejected(
        "DELETE FROM users WHERE id = '" + holder + "'",
        "fk_capability_grants_subject_user_organization");
    assertRejected(
        "DELETE FROM users WHERE id = '" + granter + "'",
        "fk_capability_grants_granted_by_user_organization");
  }

  /**
   * One open interval per subject, any number of closed ones; an interval never ends before it
   * begins, and a person in the history stays undeletable while the acting person is detachable.
   */
  @Test
  void aCapabilityIntervalIsOpenOncePerSubjectAndEndsNoEarlierThanItBegins() throws SQLException {
    UUID user = insertUser();
    UUID actor = insertUser();
    execute(capabilityHistorySql("CREATE_INTERNAL_GROUP", "GRANTED", null, user));
    execute(capabilityHistorySql("CREATE_INTERNAL_GROUP", "REVOKED", "now()", user));
    execute(capabilityHistorySql("CREATE_INTERNAL_GROUP", "GRANTED", "now()", user));

    assertRejected(
        capabilityHistorySql("CREATE_INTERNAL_GROUP", "GRANTED", null, user),
        "uk_capability_grant_history_open_user");
    assertRejected(
        capabilityHistorySql("CREATE_INTERNAL_GROUP", "GRANTED", "now() - interval '1 day'", user),
        "chk_capability_grant_history_interval");
    assertRejected(
        capabilityHistorySql("CREATE_INTERNAL_GROUP", "EXPIRED", "now()", user),
        "chk_capability_grant_history_cause");

    execute("UPDATE capability_grant_history SET actor_user_id = '" + actor + "'");
    execute("DELETE FROM users WHERE id = '" + actor + "'");
    assertThat(countWhere("capability_grant_history", "actor_user_id IS NOT NULL")).isZero();
    assertRejected(
        "DELETE FROM users WHERE id = '" + user + "'",
        "fk_capability_grant_history_subject_user_organization");
  }

  // ---------------------------------------------------------------------------------------------
  // Transfers (ADR-0036 Entscheidung 10)
  // ---------------------------------------------------------------------------------------------

  /**
   * A transfer names exactly one source and one target, keeps a name snapshot only of a group, and
   * lists its scope as upper-case part names.
   */
  @Test
  void aTransferNamesOneSourceAndTargetLabelsOnlyGroupsAndListsItsScope() throws SQLException {
    UUID user = insertUser();
    UUID group = insertInternalGroup();

    execute(transferSql("GROUP", null, group, "'Referat 50'", "GRANTS,SPACE_MEMBERSHIPS"));
    assertRejected(
        transferSql("USER", user, null, "'Anna Berg'", "GRANTS"),
        "chk_permission_transfers_source_label");
    assertRejected(
        transferSql("USER", user, group, "NULL", "GRANTS"), "chk_permission_transfers_source");
    assertRejected(
        transferSql("GROUP", null, null, "NULL", "GRANTS"), "chk_permission_transfers_source");
    assertRejected(
        transferSql("USER", user, null, "NULL", "grants"), "chk_permission_transfers_scope");
    assertRejected(
        transferSql("USER", user, null, "NULL", "GRANTS,"), "chk_permission_transfers_scope");
  }

  /** The point of a transfer is that the source can be deleted afterwards. */
  @Test
  void theSourceGroupOfATransferStaysDeletable() throws SQLException {
    UUID group = insertInternalGroup();
    execute(transferSql("GROUP", null, group, "'Referat 50'", "GRANTS"));

    execute("DELETE FROM groups WHERE id = '" + group + "'");

    assertThat(countWhere("permission_transfers", "source_group_id = '" + group + "'"))
        .isEqualTo(1);
  }

  @Test
  void aTransferRecordsEachObjectOnceWithoutNeedingItAndTakesTheRecordsWithIt()
      throws SQLException {
    UUID transfer = insertPermissionTransfer();
    UUID object = UUID.randomUUID();
    execute(transferObjectSql(transfer, "KNOWLEDGE_LIBRARY", object));

    assertRejected(
        transferObjectSql(transfer, "KNOWLEDGE_LIBRARY", object), "uk_permission_transfer_objects");
    execute("DELETE FROM permission_transfers WHERE id = '" + transfer + "'");
    assertThat(countRows("permission_transfer_objects")).isZero();
  }

  /**
   * Both sides of a transfer are history causes carrying the operation id; a deleted transfer
   * leaves its intervals behind, only without the id.
   */
  @Test
  void aTransferCutsTheHistoryAndADeletedTransferLeavesItsIntervalsBehind() throws SQLException {
    UUID transfer = insertPermissionTransfer();
    UUID user = insertUser();
    UUID asset = insertAsset("KNOWLEDGE_LIBRARY", user);
    execute(
        grantHistorySql(
            "KNOWLEDGE_LIBRARY",
            asset,
            "USER",
            user,
            "VIEWER",
            "TRANSFERRED_OUT",
            "now()",
            transfer));
    execute(
        "INSERT INTO capability_grant_history (id, organization_id, capability, subject_type,"
            + " subject_user_id, cause, valid_from, transfer_id) VALUES (gen_random_uuid(), '"
            + SEEDED_ORGANIZATION_ID
            + "', 'CREATE_SPACE', 'USER', '"
            + user
            + "', 'TRANSFERRED_IN', now(), '"
            + transfer
            + "')");
    execute(
        "INSERT INTO asset_ownership_history (id, asset_type, asset_id,"
            + " organization_id, owner_type, owner_user_id, cause, valid_from, transfer_id) VALUES"
            + " (gen_random_uuid(), 'KNOWLEDGE_LIBRARY', '"
            + asset
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'USER', '"
            + user
            + "', 'TRANSFERRED', now(), '"
            + transfer
            + "')");
    assertRejected(
        capabilityHistorySql("CREATE_SPACE", "TRANSFERRED", "now()", user),
        "chk_capability_grant_history_cause");

    execute("DELETE FROM permission_transfers WHERE id = '" + transfer + "'");

    assertThat(
            countWhere("asset_grant_history", "cause = 'TRANSFERRED_OUT' AND transfer_id IS NULL"))
        .isEqualTo(1);
    assertThat(
            countWhere(
                "capability_grant_history", "cause = 'TRANSFERRED_IN' AND transfer_id IS NULL"))
        .isEqualTo(1);
    assertThat(
            countWhere("asset_ownership_history", "cause = 'TRANSFERRED' AND transfer_id IS NULL"))
        .isEqualTo(1);
  }

  // ---------------------------------------------------------------------------------------------
  // Account states and succession (ADR-0036 Entscheidungen 6 and 8)
  // ---------------------------------------------------------------------------------------------

  /**
   * An account's state chain starts with its first change only, so an account never locked has no
   * row and stays deletable; once it has one, the history holds it.
   */
  @Test
  void anAccountStateIntervalIsOpenOnceAndHoldsItsAccount() throws SQLException {
    UUID neverLocked = insertUser();
    execute("DELETE FROM users WHERE id = '" + neverLocked + "'");

    UUID user = insertUser();
    execute(accountStateSql(user, "LOCKED", "DIRECTORY_LOCKED"));
    assertRejected(
        accountStateSql(user, "ACTIVE", "DIRECTORY_UNLOCKED"), "uk_account_state_history_open");
    assertRejected(
        accountStateSql(insertUser(), "SUSPENDED", "DIRECTORY_LOCKED"),
        "chk_account_state_history_state");
    assertRejected(
        accountStateSql(insertUser(), "LOCKED", "ADMIN_LOCKED"), "chk_account_state_history_cause");
    assertRejected(
        "DELETE FROM users WHERE id = '" + user + "'",
        "fk_account_state_history_user_organization");
  }

  /**
   * One open case per object and tab; a closed one leaves room for the state to return. The object
   * id needs no existing object - the case outlives it.
   */
  @Test
  void aSuccessionCaseIsOpenOncePerObjectAndTabAndMayReturnAfterClosing() throws SQLException {
    UUID object = UUID.randomUUID();
    UUID first = insertSuccessionCase("OPEN_SUCCESSION", "SPACE", null, object);
    insertSuccessionCase("GROUP_WITHOUT_EFFECT", "SPACE", null, object);

    assertRejected(
        successionCaseSql(UUID.randomUUID(), "OPEN_SUCCESSION", "SPACE", null, object),
        "uk_succession_cases_open");
    execute("UPDATE succession_cases SET closed_at = now() WHERE id = '" + first + "'");
    insertSuccessionCase("OPEN_SUCCESSION", "SPACE", null, object);
  }

  /**
   * Nobody ended an open case; whoever ended a case stays deletable (a case says nothing about read
   * rights) and so does whoever reviewed it, while the review goes with its case.
   */
  @Test
  void successionCasesAndReviewsNameTheirPersonsWithoutHoldingThem() throws SQLException {
    UUID closer = insertUser();
    UUID reviewer = insertUser();
    UUID open = insertSuccessionCase("OPEN_SUCCESSION", "GROUP", null, UUID.randomUUID());
    assertRejected(
        "UPDATE succession_cases SET closed_by_user_id = '"
            + closer
            + "' WHERE id = '"
            + open
            + "'",
        "chk_succession_cases_closed");

    execute(
        "UPDATE succession_cases SET closed_at = now(), closed_by_user_id = '"
            + closer
            + "' WHERE id = '"
            + open
            + "'");
    execute(
        "INSERT INTO succession_reviews (id, case_id, organization_id, reviewed_at,"
            + " reviewed_by_user_id, reason) VALUES (gen_random_uuid(), '"
            + open
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', now(), '"
            + reviewer
            + "', 'weiterhin offen')");
    execute("DELETE FROM users WHERE id IN ('" + closer + "', '" + reviewer + "')");
    assertThat(countWhere("succession_cases", "closed_by_user_id IS NULL")).isEqualTo(1);
    assertThat(countWhere("succession_reviews", "reviewed_by_user_id IS NULL")).isEqualTo(1);

    execute("DELETE FROM succession_cases WHERE id = '" + open + "'");
    assertThat(countRows("succession_reviews")).isZero();
  }

  /** An asset case says which asset type it concerns; no other case does. */
  @Test
  void exactlyAnAssetCaseCarriesItsAssetType() throws SQLException {
    insertSuccessionCase("OPEN_SUCCESSION", "ASSET", "PROMPT_LIBRARY", UUID.randomUUID());

    assertRejected(
        successionCaseSql(UUID.randomUUID(), "OPEN_SUCCESSION", "ASSET", null, UUID.randomUUID()),
        "chk_succession_cases_asset_type");
    assertRejected(
        successionCaseSql(
            UUID.randomUUID(), "OPEN_SUCCESSION", "SPACE", "KNOWLEDGE_LIBRARY", UUID.randomUUID()),
        "chk_succession_cases_asset_type");
    assertRejected(
        successionCaseSql(
            UUID.randomUUID(), "OPEN_SUCCESSION", "KNOWLEDGE_LIBRARY", null, UUID.randomUUID()),
        "chk_succession_cases_object_type");
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private List<String> strings(String sql) throws SQLException {
    List<String> result = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      while (rs.next()) {
        result.add(rs.getString(1));
      }
    }
    return result;
  }

  private static String groupSql(String kind, UUID provider, String externalId) {
    return "INSERT INTO groups (id, organization_id, kind, name, provider_id, external_id) VALUES"
        + " (gen_random_uuid(), '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + kind
        + "', 'Gruppe', "
        + quoted(provider)
        + ", "
        + quoted(externalId)
        + ")";
  }

  private static String stewardSql(UUID group, UUID user, UUID appointedBy) {
    return "INSERT INTO group_stewards (id, group_id, user_id, organization_id,"
        + " appointed_by_user_id) VALUES (gen_random_uuid(), '"
        + group
        + "', '"
        + user
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', "
        + quoted(appointedBy)
        + ")";
  }

  private static String statusSql(UUID provider) {
    return "INSERT INTO directory_sync_status (id, organization_id, provider_id, last_run_at,"
        + " last_outcome) VALUES (gen_random_uuid(), '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + provider
        + "', now(), 'APPLIED')";
  }

  private static String pendingPlanSql(UUID provider) {
    return "INSERT INTO directory_sync_pending_plans (id, organization_id, provider_id, created_at,"
        + " fingerprint, changed_fraction, memberships_removed, report) VALUES (gen_random_uuid(), '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + provider
        + "', now(), 'abc', 0.4, 12, '{}')";
  }

  private static String directoryAccessSql(UUID provider, String type, String secret) {
    return "INSERT INTO directory_connectors (id, organization_id, provider_id, connector_type,"
        + " client_id, client_secret, created_at, updated_at) VALUES (gen_random_uuid(), '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + provider
        + "', '"
        + type
        + "', 'opaa-sync', '"
        + secret
        + "', now(), now())";
  }

  private static String assetSql(String assetType, UUID owner) {
    return "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_user_id)"
        + " VALUES (gen_random_uuid(), '"
        + assetType
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', 'Asset', 'USER', '"
        + owner
        + "')";
  }

  private static String grantSql(
      String assetType, UUID asset, String subjectType, UUID subject, Integer memberCount) {
    boolean group = "GROUP".equals(subjectType);
    return "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type,"
        + " subject_user_id, subject_group_id, role, member_count_at_grant) VALUES"
        + " (gen_random_uuid(), '"
        + assetType
        + "', '"
        + asset
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + subjectType
        + "', "
        + (group ? "NULL" : quoted(subject))
        + ", "
        + (group ? quoted(subject) : "NULL")
        + ", 'VIEWER', "
        + (memberCount == null ? "NULL" : memberCount)
        + ")";
  }

  private static String grantHistorySql(
      String assetType,
      UUID asset,
      String subjectType,
      UUID subject,
      String role,
      String cause,
      String validToLiteral) {
    return grantHistorySql(
        assetType, asset, subjectType, subject, role, cause, validToLiteral, null);
  }

  private static String grantHistorySql(
      String assetType,
      UUID asset,
      String subjectType,
      UUID subject,
      String role,
      String cause,
      String validToLiteral,
      UUID transfer) {
    return "INSERT INTO asset_grant_history (id, asset_type, asset_id, organization_id,"
        + " subject_type, subject_user_id, role, cause, valid_from, valid_to, transfer_id) VALUES"
        + " (gen_random_uuid(), '"
        + assetType
        + "', '"
        + asset
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + subjectType
        + "', "
        + quoted(subject)
        + ", '"
        + role
        + "', '"
        + cause
        + "', now(), "
        + (validToLiteral == null ? "NULL" : validToLiteral)
        + ", "
        + quoted(transfer)
        + ")";
  }

  private static String ownershipSql(String assetType, UUID asset, UUID owner, String cause) {
    return "INSERT INTO asset_ownership_history (id, asset_type, asset_id, organization_id,"
        + " owner_type, owner_user_id, cause, valid_from) VALUES (gen_random_uuid(), '"
        + assetType
        + "', '"
        + asset
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', 'USER', '"
        + owner
        + "', '"
        + cause
        + "', now())";
  }

  private static String visibilitySql(String assetType, UUID asset, String cause) {
    return "INSERT INTO asset_visibility_history (id, asset_type, asset_id, organization_id,"
        + " listed, cause, valid_from) VALUES (gen_random_uuid(), '"
        + assetType
        + "', '"
        + asset
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', false, '"
        + cause
        + "', now())";
  }

  private static String transferObjectSql(UUID transfer, String assetType, UUID asset) {
    return "INSERT INTO permission_transfer_objects (id, transfer_id, organization_id, asset_type,"
        + " asset_id) VALUES (gen_random_uuid(), '"
        + transfer
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + assetType
        + "', '"
        + asset
        + "')";
  }

  private String transferSql(
      String sourceType, UUID sourceUser, UUID sourceGroup, String sourceLabel, String scope)
      throws SQLException {
    return "INSERT INTO permission_transfers (id, organization_id, source_type, source_user_id,"
        + " source_group_id, source_label, target_type, target_user_id, scope, performed_at)"
        + " VALUES (gen_random_uuid(), '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + sourceType
        + "', "
        + quoted(sourceUser)
        + ", "
        + quoted(sourceGroup)
        + ", "
        + sourceLabel
        + ", 'USER', '"
        + insertUser()
        + "', '"
        + scope
        + "', now())";
  }

  private static String capabilitySql(
      String capability, String subjectType, UUID user, UUID group) {
    return capabilitySql(capability, subjectType, user, group, null);
  }

  private static String capabilitySql(
      String capability, String subjectType, UUID user, UUID group, UUID grantedBy) {
    return "INSERT INTO capability_grants (id, organization_id, capability, subject_type,"
        + " subject_user_id, subject_group_id, granted_by_user_id) VALUES (gen_random_uuid(), '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + capability
        + "', '"
        + subjectType
        + "', "
        + quoted(user)
        + ", "
        + quoted(group)
        + ", "
        + quoted(grantedBy)
        + ")";
  }

  private static String capabilityHistorySql(
      String capability, String cause, String validToLiteral, UUID user) {
    return "INSERT INTO capability_grant_history (id, organization_id, capability, subject_type,"
        + " subject_user_id, cause, valid_from, valid_to) VALUES (gen_random_uuid(), '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + capability
        + "', '"
        + (user == null ? "ALL_ACCOUNTS" : "USER")
        + "', "
        + quoted(user)
        + ", '"
        + cause
        + "', now(), "
        + (validToLiteral == null ? "NULL" : validToLiteral)
        + ")";
  }

  private static String accountStateSql(UUID user, String state, String cause) {
    return "INSERT INTO account_state_history (id, user_id, organization_id, state, cause,"
        + " valid_from) VALUES (gen_random_uuid(), '"
        + user
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + state
        + "', '"
        + cause
        + "', now())";
  }

  private UUID insertSuccessionCase(String kind, String objectType, String assetType, UUID object)
      throws SQLException {
    UUID id = UUID.randomUUID();
    execute(successionCaseSql(id, kind, objectType, assetType, object));
    return id;
  }

  private static String successionCaseSql(
      UUID id, String kind, String objectType, String assetType, UUID object) {
    return "INSERT INTO succession_cases (id, organization_id, kind, object_type, asset_type,"
        + " object_id, first_seen_at, last_seen_at) VALUES ('"
        + id
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + kind
        + "', '"
        + objectType
        + "', "
        + quoted(assetType)
        + ", '"
        + object
        + "', now(), now())";
  }
}
