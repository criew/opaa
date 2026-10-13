package io.opaa.asset;

import static io.opaa.test.TestAssetType.TEST_ASSET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.PermissionTransferScope;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.group.GroupService;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.PermissionTransferOrder;
import io.opaa.permission.PermissionTransferService;
import io.opaa.space.Space;
import io.opaa.space.SpaceAssetAssociationService;
import io.opaa.space.SpaceAssetLink;
import io.opaa.space.SpaceAssetLinks;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceRepository;
import io.opaa.succession.SuccessionCaseRepository;
import io.opaa.succession.SuccessionDetectionService;
import io.opaa.succession.SuccessionService;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import io.opaa.test.TestAssetType;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * An asset type behind a switched-off feature (ADR-0044, #2588), shown with the test-defined type
 * so the rule holds for every type of the shell: no read interface names its assets - catalog,
 * favorites, space contents, succession list - while ownership transfer and group removal still
 * handle them, nothing is deleted, and switched on again everything is back unchanged.
 */
@OpaaIntegrationTest
class InactiveAssetTypeIntegrationTest {

  @Autowired private TestAssetType testAssetType;
  @Autowired private AssetCatalogService catalogService;
  @Autowired private AssetFavoriteService favoriteService;
  @Autowired private AssetFavoriteRepository favoriteRepository;
  @Autowired private SpaceAssetAssociationService associationService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private SuccessionDetectionService detectionService;
  @Autowired private SuccessionService successionService;
  @Autowired private SuccessionCaseRepository successionCases;
  @Autowired private PermissionTransferService transferService;
  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private AssetRepository assetRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private UUID owner;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Abgeschaltet " + UUID.randomUUID()))
            .getId();
    owner = createUser("Eigentuemerin");
  }

  @AfterEach
  void tearDown() {
    testAssetType.switchOn();
    for (String table :
        List.of(
            "permission_transfer_objects",
            "permission_transfers",
            "asset_ownership_history",
            "asset_grants",
            "asset_grant_history",
            "knowledge_libraries",
            "assets",
            "group_memberships",
            "groups")) {
      jdbcTemplate.update("DELETE FROM " + table + " WHERE organization_id = ?", organizationId);
    }
    ownOrganizationFixtures.removeOrganizations(organizationId);
  }

  @Test
  void theCatalogAndTheFavoritesLeaveAnInactiveTypeOut() {
    UUID asset = createUserOwnedTestAsset();
    UUID library = createLibrary();
    favoriteService.mark(TEST_ASSET, asset, callerOf(owner));

    testAssetType.switchOff();

    assertThat(catalogIds(false)).containsExactly(library);
    assertThat(catalogIds(true)).isEmpty();
    assertThat(favoriteRepository.findMarkedAmong(owner, List.of(asset)))
        .as("the mark itself is kept")
        .containsExactly(asset);

    testAssetType.switchOn();

    assertThat(catalogIds(false)).containsExactlyInAnyOrder(asset, library);
    assertThat(catalogIds(true)).containsExactly(asset);
  }

  @Test
  void theSpaceContentsNeitherListNorCountAnInactiveTypeAndTakeNoNewAssociation() {
    UUID asset = createUserOwnedTestAsset();
    UUID other = createUserOwnedTestAsset();
    UUID library = createLibrary();
    Space space = createSpace();
    associationService.associate(space.getId(), TEST_ASSET, asset, callerOf(owner));
    associationService.associate(
        space.getId(), KnowledgeLibrary.ASSET_TYPE, library, callerOf(owner));

    testAssetType.switchOff();

    SpaceAssetLinks contents = associationService.listForSpace(space.getId(), callerOf(owner));
    assertThat(contents.items()).extracting(link -> link.association().getAssetId()).containsExactly(library);
    assertThat(contents.hasUnreadableAssociations()).isFalse();
    assertThat(associationService.countVisibleBySpace(List.of(space), callerOf(owner)))
        .containsEntry(space.getId(), 1L);
    assertThatThrownBy(
            () -> associationService.associate(space.getId(), TEST_ASSET, other, callerOf(owner)))
        .isInstanceOf(NotFoundException.class);

    testAssetType.switchOn();

    assertThat(associationService.listForSpace(space.getId(), callerOf(owner)).items())
        .extracting(link -> link.association().getAssetId())
        .containsExactlyInAnyOrder(asset, library);
  }

  /** The case stays open while the type is off, so its age is intact once it is back. */
  @Test
  void theSuccessionListLeavesAnInactiveTypeOutAndTheRunKeepsItsCaseOpen() {
    UUID asset = createUserOwnedTestAsset();
    jdbcTemplate.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", owner);
    detectionService.runFor(organizationId);
    assertThat(openCases(asset)).isEqualTo(1);

    testAssetType.switchOff();
    detectionService.runFor(organizationId);

    assertThat(openSuccessionIds()).doesNotContain(asset);
    assertThat(openCases(asset)).isEqualTo(1);

    testAssetType.switchOn();

    assertThat(openSuccessionIds()).contains(asset);
  }

  @Test
  void aGroupOwningAnAssetOfAnInactiveTypeIsHandedOverAndRemoved() {
    CurrentUser admin = createAdmin();
    UUID source = createGroup("Referat 50");
    UUID target = createGroup("Referat 52");
    UUID asset = createGroupOwnedTestAsset(source);

    testAssetType.switchOff();

    assertThatThrownBy(() -> groupService.deleteGroup(source, admin))
        .isInstanceOf(ConflictException.class);

    PermissionTransferOrder order =
        new PermissionTransferOrder(
            PermissionSubjectType.GROUP,
            source,
            PermissionSubjectType.GROUP,
            target,
            EnumSet.of(PermissionTransferScope.OWNERSHIP, PermissionTransferScope.ASSET_GRANTS));
    UUID previewId = transferService.preview(order, admin).previewId();
    transferService.transfer(order, true, previewId, admin);

    assertThat(assetRepository.findById(asset).orElseThrow().getOwnerGroupId()).isEqualTo(target);
    groupService.deleteGroup(source, admin);
    assertThat(groupRepository.findById(source)).isEmpty();
  }

  @Test
  void anAccountOwningAnAssetOfAnInactiveTypeHandsItOver() {
    CurrentUser admin = createAdmin();
    UUID successor = createUser("Nachfolgerin");
    UUID asset = createUserOwnedTestAsset();

    testAssetType.switchOff();

    PermissionTransferOrder order =
        new PermissionTransferOrder(
            PermissionSubjectType.USER,
            owner,
            PermissionSubjectType.USER,
            successor,
            EnumSet.of(PermissionTransferScope.OWNERSHIP));
    UUID previewId = transferService.preview(order, admin).previewId();
    transferService.transfer(order, true, previewId, admin);

    assertThat(assetRepository.findById(asset).orElseThrow().getOwnerUserId())
        .isEqualTo(successor);
  }

  private List<UUID> catalogIds(boolean favoritesOnly) {
    return catalogService
        .list(callerOf(owner), new AssetCatalogQuery(null, null, favoritesOnly, null), 0, 50)
        .entries()
        .stream()
        .map(entry -> entry.asset().getId())
        .toList();
  }

  private List<UUID> openSuccessionIds() {
    return successionService
        .list(organizationId, SuccessionKind.OPEN_SUCCESSION, 0, 50)
        .entries()
        .stream()
        .map(entry -> entry.finding().objectId())
        .toList();
  }

  private int openCases(UUID asset) {
    return successionCases
        .findByKindAndObjectIdAndClosedAtIsNull(SuccessionKind.OPEN_SUCCESSION, asset)
        .size();
  }

  private UUID createUserOwnedTestAsset() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_user_id,"
            + " created_by_user_id) VALUES (?, ?, ?, 'Testobjekt', 'USER', ?, ?)",
        id,
        TEST_ASSET.value(),
        organizationId,
        owner,
        owner);
    grantRepository.save(
        AssetGrant.forUser(TEST_ASSET, id, organizationId, owner, AssetRole.OWNER, null, owner));
    return id;
  }

  private UUID createGroupOwnedTestAsset(UUID groupId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_group_id,"
            + " created_by_user_id) VALUES (?, ?, ?, 'Testobjekt', 'GROUP', ?, ?)",
        id,
        TEST_ASSET.value(),
        organizationId,
        groupId,
        owner);
    grantRepository.save(
        AssetGrant.forGroup(
            TEST_ASSET, id, organizationId, groupId, AssetRole.MANAGER, null, owner));
    return id;
  }

  private UUID createLibrary() {
    UUID library =
        libraryRepository
            .save(KnowledgeLibrary.ownedByUser(organizationId, "Bibliothek", null, owner))
            .getId();
    grantRepository.save(
        AssetGrant.forUser(
            KnowledgeLibrary.ASSET_TYPE,
            library,
            organizationId,
            owner,
            AssetRole.OWNER,
            null,
            owner));
    return library;
  }

  private Space createSpace() {
    Space space = new Space("Abgeschaltet", null, false, owner, organizationId);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, organizationId));
    return spaceRepository.save(space);
  }

  private UUID createGroup(String name) {
    Group group = Group.internal(organizationId, name, null, null);
    group.release(true);
    return groupRepository.save(group).getId();
  }

  private UUID createUser(String displayName) {
    User user =
        new User(
            "inactive-type-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            displayName);
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }

  private CurrentUser createAdmin() {
    User user =
        new User(
            "inactive-type-admin-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            "Systemverwaltung");
    user.setOrganizationId(organizationId);
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    UUID id = userRepository.save(user).getId();
    return CurrentUser.of(id, organizationId, SystemRole.SYSTEM_ADMIN, "Systemverwaltung");
  }

  private CurrentUser callerOf(UUID userId) {
    return CurrentUser.of(userId, organizationId, SystemRole.USER, "Sachbearbeitung");
  }
}
