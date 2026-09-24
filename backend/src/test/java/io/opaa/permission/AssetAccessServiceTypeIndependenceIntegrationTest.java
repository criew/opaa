package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AssetRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The acceptance criterion of #1811 that no other test can state: a grant for a <b>second asset
 * type, defined here in the test</b>, is written and evaluated by the same machinery, with no
 * type-specific code anywhere in {@code io.opaa.permission}. If any branch on the asset type ever
 * creeps into the grant path, {@link #AGENT} stops working and this class says so.
 *
 * <p>The second type has a row on the asset shell ({@code assets}, #1899) - the one thing every
 * asset owes - and nothing else: no type table, no entity, no definition. The grant stores, finds
 * and resolves all the same, and so does the organization-wide release read off the shell.
 */
@OpaaIntegrationTest
class AssetAccessServiceTypeIndependenceIntegrationTest {

  /** An asset type this test invents; no production code knows it. */
  private static final AssetType AGENT = AssetType.of("TEST_AGENT");

  @Autowired private AssetAccessService accessService;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private PermissionHistoryService permissionHistoryService;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final List<UUID> createdAssetIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @BeforeEach
  @AfterEach
  void cleanUp() {
    // The grants go with their asset (fk_asset_grants_asset_organization, ON DELETE CASCADE).
    createdAssetIds.forEach(id -> jdbcTemplate.update("DELETE FROM assets WHERE id = ?", id));
    grantHistoryRepository.deleteBySubjectUserIdIn(createdUserIds);
    userRepository.deleteAllById(createdUserIds);
    createdAssetIds.clear();
    createdUserIds.clear();
  }

  @Test
  void aGrantOnASecondAssetTypeIsResolvedByTheSameFormula() {
    UUID userId = createUser();
    UUID agentId = createAgent(userId, "PRIVATE");
    saveGrant(AGENT, agentId, userId, AssetRole.EDITOR);

    assertThat(accessService.effectiveRole(AGENT, agentId, userId, false))
        .isEqualTo(AssetRole.EDITOR);
    assertThat(accessService.readableAssetIds(AGENT, userId, Organization.DEFAULT_ID))
        .containsExactly(agentId);
    assertThat(accessService.effectiveRoles(AGENT, Set.of(agentId), userId, Set.of()))
        .containsEntry(agentId, AssetRole.EDITOR);
  }

  /** The organization-wide release of the second type reaches everybody, without any grant. */
  @Test
  void anOrganizationWideAssetOfASecondTypeIsReadableWithoutAGrant() {
    UUID owner = createUser();
    UUID reader = createUser();
    UUID agentId = createAgent(owner, "ORGANIZATION");

    assertThat(accessService.readableAssetIds(AGENT, reader, Organization.DEFAULT_ID))
        .contains(agentId);
    assertThat(accessService.effectiveRole(AGENT, agentId, reader, true))
        .isEqualTo(AssetRole.VIEWER);
    assertThat(
            accessService.readableAssetIds(
                KnowledgeLibrary.ASSET_TYPE, reader, Organization.DEFAULT_ID))
        .doesNotContain(agentId);
  }

  /**
   * The type is part of the identity of the object a grant refers to: a grant on an asset of one
   * type says nothing about the same id asked for under another type.
   */
  @Test
  void aGrantOnOneAssetTypeDoesNotReachAnotherType() {
    UUID userId = createUser();
    UUID agentId = createAgent(userId, "PRIVATE");
    saveGrant(AGENT, agentId, userId, AssetRole.OWNER);

    assertThat(accessService.effectiveRole(AGENT, agentId, userId, false))
        .isEqualTo(AssetRole.OWNER);
    assertThat(accessService.effectiveRole(KnowledgeLibrary.ASSET_TYPE, agentId, userId, false))
        .isNull();
    assertThat(
            accessService.readableAssetIds(
                KnowledgeLibrary.ASSET_TYPE, userId, Organization.DEFAULT_ID))
        .doesNotContain(agentId);
  }

  /** The rights history is type-independent as well - the Stichtag answer covers the new type. */
  @Test
  void theRightsHistoryOfASecondAssetTypeIsReconstructable() {
    UUID userId = createUser();
    UUID agentId = createAgent(userId, "PRIVATE");
    AssetGrant grant = saveGrant(AGENT, agentId, userId, AssetRole.VIEWER);
    permissionHistoryService.recordGrantCreated(grant, userId);

    assertThat(
            permissionHistoryService.readableAssetIdsAsOf(
                AGENT, userId, Organization.DEFAULT_ID, java.time.Instant.now()))
        .containsExactly(agentId);
    assertThat(
            permissionHistoryService.readableAssetIdsAsOf(
                KnowledgeLibrary.ASSET_TYPE,
                userId,
                Organization.DEFAULT_ID,
                java.time.Instant.now()))
        .doesNotContain(agentId);
  }

  /** The one row an asset of any type owes: the shell, written here by plain SQL. */
  private UUID createAgent(UUID owner, String visibility) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_user_id,"
            + " visibility) VALUES (?, ?, ?, 'Agent', 'USER', ?, ?)",
        id,
        AGENT.value(),
        Organization.DEFAULT_ID,
        owner,
        visibility);
    createdAssetIds.add(id);
    return id;
  }

  private AssetGrant saveGrant(AssetType assetType, UUID assetId, UUID userId, AssetRole role) {
    return grantRepository.save(
        AssetGrant.forUser(
            assetType, assetId, Organization.DEFAULT_ID, userId, role, null, userId));
  }

  private UUID createUser() {
    User user =
        new User(
            "type-independence-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            "Testperson");
    user.setOrganizationId(Organization.DEFAULT_ID);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }
}
