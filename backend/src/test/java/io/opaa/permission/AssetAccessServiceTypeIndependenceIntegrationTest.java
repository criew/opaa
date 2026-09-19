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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The acceptance criterion of #1811 that no other test can state: a grant for a <b>second asset
 * type, defined here in the test</b>, is written and evaluated by the same machinery, with no
 * type-specific code anywhere in {@code io.opaa.permission}. If any branch on the asset type ever
 * creeps into the grant path, {@link #AGENT} stops working and this class says so.
 *
 * <p>The second type deliberately refers to nothing: there is no {@code agents} table, and the
 * grant still stores, finds and resolves. That is the whole point of dropping the foreign key - see
 * {@code changes/038-asset-grants-type-independent.yaml} for what replaces its guarantees.
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

  private final List<UUID> createdGrantIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @BeforeEach
  @AfterEach
  void cleanUp() {
    grantRepository.deleteAllById(createdGrantIds);
    grantHistoryRepository.deleteBySubjectUserIdIn(createdUserIds);
    userRepository.deleteAllById(createdUserIds);
    createdGrantIds.clear();
    createdUserIds.clear();
  }

  @Test
  void aGrantOnASecondAssetTypeIsResolvedByTheSameFormula() {
    UUID userId = createUser();
    UUID agentId = UUID.randomUUID();
    saveGrant(AGENT, agentId, userId, AssetRole.EDITOR);

    assertThat(accessService.effectiveRole(AGENT, agentId, userId, null))
        .isEqualTo(AssetRole.EDITOR);
    assertThat(accessService.readableAssetIds(AGENT, userId, Organization.DEFAULT_ID))
        .containsExactly(agentId);
    assertThat(accessService.effectiveRoles(AGENT, Set.of(agentId), userId, Map.of()))
        .containsEntry(agentId, AssetRole.EDITOR);
  }

  /**
   * The type is part of the identity of the object a grant refers to: the same id under two types
   * is two different assets, and a grant on one says nothing about the other.
   */
  @Test
  void twoAssetTypesSharingAnIdDoNotReachEachOther() {
    UUID userId = createUser();
    UUID sharedId = UUID.randomUUID();
    saveGrant(AGENT, sharedId, userId, AssetRole.OWNER);

    assertThat(accessService.effectiveRole(AGENT, sharedId, userId, null))
        .isEqualTo(AssetRole.OWNER);
    assertThat(accessService.effectiveRole(KnowledgeLibrary.ASSET_TYPE, sharedId, userId, null))
        .isNull();
    assertThat(
            accessService.readableAssetIds(
                KnowledgeLibrary.ASSET_TYPE, userId, Organization.DEFAULT_ID))
        .doesNotContain(sharedId);
  }

  /** The rights history is type-independent as well - the Stichtag answer covers the new type. */
  @Test
  void theRightsHistoryOfASecondAssetTypeIsReconstructable() {
    UUID userId = createUser();
    UUID agentId = UUID.randomUUID();
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

  private AssetGrant saveGrant(AssetType assetType, UUID assetId, UUID userId, AssetRole role) {
    AssetGrant grant =
        grantRepository.save(
            AssetGrant.forUser(
                assetType, assetId, Organization.DEFAULT_ID, userId, role, null, userId));
    createdGrantIds.add(grant.getId());
    return grant;
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
