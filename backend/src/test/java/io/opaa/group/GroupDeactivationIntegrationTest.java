package io.opaa.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetGrantSubjectType;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.Capability;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.GroupState;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetGrantUpsert;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.OidcProviderRepository;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetAccessService;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.CapabilityGrant;
import io.opaa.permission.CapabilityGrantRepository;
import io.opaa.permission.CapabilityService;
import io.opaa.permission.GroupMembershipHistoryCause;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.PermissionHistoryService;
import io.opaa.permission.PermissionSubject;
import io.opaa.space.Space;
import io.opaa.space.SpaceAccessPolicy;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceRepository;
import io.opaa.space.SpaceService;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import io.opaa.test.ProviderFixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deactivating an internal group for the time being (ADR-0036, Entscheidung 14, #1987) against the
 * real, versioned schema: while deactivated the group reaches nobody on any path, reactivating
 * restores exactly what it held, and the Stichtagsauskunft agrees with both.
 */
@OpaaIntegrationTest
class GroupDeactivationIntegrationTest {

  @Autowired private GroupService groupService;
  @Autowired private GroupListService groupListService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupStewardRepository stewardRepository;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private CapabilityGrantRepository capabilityGrantRepository;
  @Autowired private CapabilityService capabilityService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository assetGrantRepository;
  @Autowired private AssetGrantService assetGrantService;
  @Autowired private AssetAccessService assetAccessService;
  @Autowired private LibraryAccessService libraryAccessService;
  @Autowired private PermissionHistoryService permissionHistoryService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private SpaceService spaceService;
  @Autowired private SpaceAccessPolicy spaceAccessPolicy;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private UUID otherOrganizationId;
  private UUID providerId;
  private final List<UUID> createdUserIds = new ArrayList<>();

  private CurrentUser steward;
  private CurrentUser member;
  private CurrentUser owner;

  @BeforeEach
  void setUp() {
    createdUserIds.clear();
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org 1987")).getId();
    otherOrganizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org 1987 B")).getId();
    providerId = ProviderFixtures.tokenProvider(providerRepository).getId();
    steward = currentUserOf(user(organizationId, SystemRole.USER));
    capabilityGrantRepository.save(
        CapabilityGrant.forUser(
            organizationId, Capability.CREATE_INTERNAL_GROUP, steward.id(), steward.id()));
    member = currentUserOf(user(organizationId, SystemRole.USER));
    owner = currentUserOf(user(organizationId, SystemRole.USER));
  }

  @AfterEach
  void tearDown() {
    for (UUID org : List.of(organizationId, otherOrganizationId)) {
      for (String table :
          List.of(
              "space_memberships",
              "space_membership_history",
              "capability_grants",
              "capability_grant_history",
              "asset_grants",
              "asset_grant_history",
              "asset_ownership_history",
              "group_membership_history",
              "group_memberships",
              "group_stewards",
              "knowledge_libraries",
              "assets",
              "spaces",
              "groups")) {
        jdbcTemplate.update("DELETE FROM " + table + " WHERE organization_id = ?", org);
      }
    }
    membershipResolver.invalidateUsers(createdUserIds);
    ownOrganizationFixtures.removeOrganizations(organizationId, otherOrganizationId);
    if (providerId != null) {
      providerRepository.deleteById(providerId);
      providerId = null;
    }
  }

  // -------------------------------------------------------------------------------------------
  // Wirkung
  // -------------------------------------------------------------------------------------------

  /**
   * Every path at once: a library grant, a space membership and a creation right reach the member
   * before, reach nobody while deactivated - without a new sign-in, although the resolver's cache
   * was warm - and come back unchanged with the reactivation.
   */
  @Test
  void aDeactivatedGroupReachesNobodyAndReactivatingRestoresEverything() {
    UUID groupId = groupWithMember("Projekt Phoenix");
    UUID libraryId = libraryGrantedTo(groupId, AssetRole.EDITOR);
    Space space = spaceWithGroup(groupId, SpaceRole.CURATOR);
    capabilityGrantRepository.save(
        CapabilityGrant.forGroup(
            organizationId, Capability.CREATE_INTERNAL_GROUP, groupId, steward.id()));
    assertInEffect(groupId, libraryId, space);

    groupService.setDeactivated(groupId, true, steward);

    assertThat(roleOn(libraryId)).isNull();
    assertThat(
            assetAccessService.readableAssetIds(
                KnowledgeLibrary.ASSET_TYPE, member.id(), organizationId))
        .doesNotContain(libraryId);
    assertThat(spaceAccessPolicy.effectiveRole(reload(space), member.id())).isNull();
    assertThat(capabilityService.hasCapability(member, Capability.CREATE_INTERNAL_GROUP)).isFalse();
    assertThat(membershipResolver.groupIdsForUser(member.id())).doesNotContain(groupId);
    assertThat(assetGrantRepository.findBySubjectGroupIdIn(List.of(groupId)))
        .as("the grant stays stored")
        .hasSize(1);

    groupService.setDeactivated(groupId, false, steward);

    assertInEffect(groupId, libraryId, space);
  }

  /** The Rechteprofil of a deactivated group reads what "Alle Konten" reads, nothing of its own. */
  @Test
  void theProfileOfADeactivatedGroupReadsNoneOfItsOwnGrants() {
    UUID groupId = groupWithMember("Profil");
    UUID libraryId = libraryGrantedTo(groupId, AssetRole.VIEWER);
    assertThat(libraryAccessService.readableLibraryIdsForGroup(groupId, organizationId))
        .contains(libraryId);

    groupService.setDeactivated(groupId, true, steward);

    assertThat(libraryAccessService.readableLibraryIdsForGroup(groupId, organizationId))
        .doesNotContain(libraryId);
    assertThat(
            libraryAccessService
                .readableLibraryCountsForGroups(List.of(groupId), organizationId)
                .get(groupId))
        .isZero();
  }

  /** Stored history and current state agree: the group reached nobody while it was deactivated. */
  @Test
  void theStichtagsauskunftKnowsTheGroupReachedNobodyWhileDeactivated() throws Exception {
    UUID groupId = groupWithMember("Stichtag");
    UUID libraryId = libraryGrantedTo(groupId, AssetRole.VIEWER);
    Thread.sleep(5);
    Instant beforeDeactivation = Instant.now();
    Thread.sleep(5);

    groupService.setDeactivated(groupId, true, steward);
    Thread.sleep(5);
    Instant whileDeactivated = Instant.now();
    Thread.sleep(5);
    groupService.setDeactivated(groupId, false, steward);
    Thread.sleep(5);
    Instant afterReactivation = Instant.now();

    assertThat(readableAsOf(beforeDeactivation)).contains(libraryId);
    assertThat(readableAsOf(whileDeactivated)).doesNotContain(libraryId);
    assertThat(readableAsOf(afterReactivation)).contains(libraryId);
    assertThat(historyCauses(groupId))
        .contains(
            GroupMembershipHistoryCause.GROUP_DEACTIVATED.name(),
            GroupMembershipHistoryCause.GROUP_REACTIVATED.name());
  }

  /** Somebody taken in while the group is deactivated reaches its rights from the reactivation. */
  @Test
  void aMemberTakenInWhileDeactivatedGetsAnIntervalOnlyWithTheReactivation() {
    UUID groupId = groupWithMember("Nachzügler");
    groupService.setDeactivated(groupId, true, steward);
    UUID latecomer = user(organizationId, SystemRole.USER);

    groupService.addMember(groupId, latecomer, steward);

    assertThat(openIntervals(groupId, latecomer)).isZero();
    assertThat(membershipResolver.groupIdsForUser(latecomer)).doesNotContain(groupId);

    groupService.setDeactivated(groupId, false, steward);

    assertThat(openIntervals(groupId, latecomer)).isEqualTo(1);
    assertThat(membershipResolver.groupIdsForUser(latecomer)).contains(groupId);
  }

  // -------------------------------------------------------------------------------------------
  // Kein neues Ziel, Zustand, eigene Gruppen
  // -------------------------------------------------------------------------------------------

  @Test
  void aDeactivatedGroupIsNoNewTargetAndTheSelectionNamesTheReason() {
    UUID groupId = groupWithMember("Ruhendes Projekt");
    groupService.setRelease(groupId, true, steward);
    groupService.setDeactivated(groupId, true, steward);
    UUID libraryId = libraryManagedByOwner();

    assertThatThrownBy(
            () ->
                assetGrantService.upsertGrant(
                    KnowledgeLibrary.ASSET_TYPE,
                    libraryId,
                    new AssetGrantUpsert(AssetGrantSubjectType.GROUP, groupId, AssetRole.VIEWER),
                    owner))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("deaktiviert");
    Space space = spaceWithoutGroup();
    assertThatThrownBy(
            () ->
                spaceService.addMember(
                    space.getId(),
                    PermissionSubject.group(groupId, organizationId),
                    SpaceRole.MEMBER,
                    owner))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("deaktiviert");

    assertThat(groupService.searchSelectableGroups("Ruhendes", owner))
        .singleElement()
        .satisfies(
            selectable -> {
              assertThat(selectable.selectable()).isFalse();
              assertThat(selectable.deactivated()).isTrue();
            });
  }

  @Test
  void theMemberStillSeesTheGroupAndTheListShowsItsState() {
    UUID groupId = groupWithMember("Umorganisation");
    groupService.setDeactivated(groupId, true, steward);

    assertThat(groupService.listMyGroups(member))
        .extracting(overview -> overview.group().getId())
        .contains(groupId);
    CurrentUser admin = currentUserOf(user(organizationId, SystemRole.SYSTEM_ADMIN));
    GroupPage page =
        groupListService.pageGroups(
            admin,
            new GroupListQuery(null, null, null, null, GroupState.DEACTIVATED, null, false, 0, 25));
    assertThat(page.items())
        .extracting(overview -> overview.group().getId())
        .containsExactly(groupId);
  }

  // -------------------------------------------------------------------------------------------
  // Verweigerung
  // -------------------------------------------------------------------------------------------

  @Test
  void theOnlyOwnerOfALibraryCannotBeDeactivated() {
    UUID groupId = groupWithMember("Eigentümerin");
    libraryRepository.save(
        KnowledgeLibrary.ownedByGroup(organizationId, "Gruppenbibliothek", null, groupId));

    assertThat(groupService.deactivationPreview(groupId, steward).blockedReason())
        .contains("einzige Eigentümerin von 1 Bibliothek");
    assertThatThrownBy(() -> groupService.setDeactivated(groupId, true, steward))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("einzige Eigentümerin von 1 Bibliothek");
    assertThat(groupRepository.findById(groupId).orElseThrow().isDeactivated()).isFalse();
  }

  /** Schutzregel 1 of ADR-0036, Entscheidung 6, applied to this administrative act. */
  @Test
  void theLastCapableAdminOfASpaceCannotBeDeactivated() {
    UUID groupId = groupWithMember("Raumleitung");
    Space space = spaceWithGroup(groupId, SpaceRole.ADMIN);
    jdbcTemplate.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", owner.id());

    assertThatThrownBy(() -> groupService.setDeactivated(groupId, true, steward))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("letzte handlungsfähige ADMIN-Mitglied von 1 Space");

    jdbcTemplate.update("UPDATE users SET directory_locked_at = NULL WHERE id = ?", owner.id());
    groupService.setDeactivated(groupId, true, steward);
    assertThat(groupRepository.findById(groupId).orElseThrow().isDeactivated()).isTrue();
    assertThat(spaceAccessPolicy.effectiveRole(reload(space), member.id())).isNull();
  }

  // -------------------------------------------------------------------------------------------
  // Berechtigung
  // -------------------------------------------------------------------------------------------

  @Test
  void onlyStewardsAndTheSystemAdministrationDecideIncludingForAProtectedGroup() {
    UUID groupId = groupWithMember("Personalrat");
    CurrentUser admin = currentUserOf(user(organizationId, SystemRole.SYSTEM_ADMIN));
    groupService.setProtection(groupId, true, admin);

    assertThatThrownBy(() -> groupService.setDeactivated(groupId, true, member))
        .as("a member is no steward")
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> groupService.deactivationPreview(groupId, member))
        .isInstanceOf(NotFoundException.class);

    assertThat(groupService.setDeactivated(groupId, true, steward).group().isDeactivated())
        .isTrue();
    assertThat(groupService.setDeactivated(groupId, false, admin).group().isDeactivated())
        .isFalse();
  }

  @Test
  void anAdministratorOfAnotherOrganizationGetsNotFound() {
    UUID groupId = groupWithMember("Grenze");
    CurrentUser foreignAdmin = currentUserOf(user(otherOrganizationId, SystemRole.SYSTEM_ADMIN));

    assertThatThrownBy(() -> groupService.setDeactivated(groupId, true, foreignAdmin))
        .isInstanceOf(NotFoundException.class);
    assertThat(groupRepository.findById(groupId).orElseThrow().isDeactivated()).isFalse();
  }

  @Test
  void aProviderGroupIsNotDeactivatedHere() {
    CurrentUser admin = currentUserOf(user(organizationId, SystemRole.SYSTEM_ADMIN));
    Group providerGroup =
        groupRepository.save(
            new Group(
                organizationId,
                GroupKind.IDENTITY_PROVIDER,
                "Referat 50",
                null,
                providerId,
                "Referat 50",
                null,
                null));

    assertThatThrownBy(() -> groupService.setDeactivated(providerGroup.getId(), true, admin))
        .isInstanceOf(ValidationException.class);
  }

  // -------------------------------------------------------------------------------------------
  // Protokoll und Vorschau
  // -------------------------------------------------------------------------------------------

  @Test
  void bothChangesAreAuditedWithTheirCountedConsequencesAndARepeatRecordsNothing() {
    UUID groupId = groupWithMember("Protokoll");
    libraryGrantedTo(groupId, AssetRole.VIEWER);

    GroupDeactivationPreview preview = groupService.deactivationPreview(groupId, steward);
    assertThat(preview.assetGrants()).isEqualTo(1);
    assertThat(preview.grantedAssets()).isEqualTo(1);
    assertThat(preview.activeMembers()).isEqualTo(1);
    assertThat(preview.blockedReason()).isNull();

    groupService.setDeactivated(groupId, true, steward);
    groupService.setDeactivated(groupId, true, steward);
    groupService.setDeactivated(groupId, false, steward);

    assertThat(auditCount(groupId, AuditEventType.GROUP_DEACTIVATED)).isEqualTo(1);
    assertThat(auditCount(groupId, AuditEventType.GROUP_REACTIVATED)).isEqualTo(1);
    assertThat(auditAfter(groupId, AuditEventType.GROUP_DEACTIVATED))
        .contains("\"assetGrants\":1")
        .contains("\"memberCount\":1")
        .doesNotContain(member.id().toString());
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private void assertInEffect(UUID groupId, UUID libraryId, Space space) {
    assertThat(membershipResolver.groupIdsForUser(member.id())).contains(groupId);
    assertThat(roleOn(libraryId)).isEqualTo(AssetRole.EDITOR);
    assertThat(spaceAccessPolicy.effectiveRole(reload(space), member.id()))
        .isEqualTo(SpaceRole.CURATOR);
    assertThat(capabilityService.hasCapability(member, Capability.CREATE_INTERNAL_GROUP)).isTrue();
  }

  private AssetRole roleOn(UUID libraryId) {
    return assetAccessService.effectiveRole(KnowledgeLibrary.ASSET_TYPE, libraryId, member.id());
  }

  private java.util.Set<UUID> readableAsOf(Instant asOf) {
    return permissionHistoryService.readableAssetIdsAsOf(
        KnowledgeLibrary.ASSET_TYPE, member.id(), organizationId, asOf);
  }

  /** An internal group created by the steward, with {@link #member} as its one member. */
  private UUID groupWithMember(String name) {
    UUID groupId = groupService.createGroup(new GroupCreation(name, null), steward).group().getId();
    groupService.addMember(groupId, member.id(), steward);
    return groupId;
  }

  private UUID libraryOwnedBy(UUID ownerId) {
    return libraryRepository
        .save(
            KnowledgeLibrary.ownedByUser(
                organizationId, "Bibliothek " + UUID.randomUUID(), null, ownerId))
        .getId();
  }

  /** A library of {@link #owner} granted to the group, with the history row a grant writes. */
  private UUID libraryGrantedTo(UUID groupId, AssetRole role) {
    UUID libraryId = libraryManagedByOwner();
    AssetGrant grant =
        assetGrantRepository.save(
            AssetGrant.forGroup(
                KnowledgeLibrary.ASSET_TYPE,
                libraryId,
                organizationId,
                groupId,
                role,
                null,
                owner.id()));
    permissionHistoryService.recordGrantCreated(grant, owner.id());
    assetAccessService.invalidateAsset(KnowledgeLibrary.ASSET_TYPE, libraryId);
    return libraryId;
  }

  /** A library of {@link #owner}, who holds {@code OWNER} on it. */
  private UUID libraryManagedByOwner() {
    UUID libraryId = libraryOwnedBy(owner.id());
    AssetGrant ownerGrant =
        assetGrantRepository.save(
            AssetGrant.forUser(
                KnowledgeLibrary.ASSET_TYPE,
                libraryId,
                organizationId,
                owner.id(),
                AssetRole.OWNER,
                null,
                owner.id()));
    permissionHistoryService.recordGrantCreated(ownerGrant, owner.id());
    return libraryId;
  }

  private Space spaceWithGroup(UUID groupId, SpaceRole role) {
    Space space = new Space("Space " + UUID.randomUUID(), null, false, owner.id(), organizationId);
    space.addMembership(SpaceMembership.ofUser(owner.id(), SpaceRole.ADMIN, organizationId));
    space.addMembership(SpaceMembership.ofGroup(groupId, role, organizationId));
    return spaceRepository.save(space);
  }

  private Space spaceWithoutGroup() {
    Space space = new Space("Space " + UUID.randomUUID(), null, false, owner.id(), organizationId);
    space.addMembership(SpaceMembership.ofUser(owner.id(), SpaceRole.ADMIN, organizationId));
    return spaceRepository.save(space);
  }

  private Space reload(Space space) {
    return spaceRepository.findByIdWithMemberships(space.getId()).orElseThrow();
  }

  private UUID user(UUID organization, SystemRole role) {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organization);
    user.setSystemRole(role);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  private CurrentUser currentUserOf(UUID userId) {
    User user = userRepository.findById(userId).orElseThrow();
    return CurrentUser.of(
        user.getId(),
        user.getOrganizationId(),
        user.getSystemRole(),
        user.getDisplayName(),
        user.getEmail());
  }

  private List<String> historyCauses(UUID groupId) {
    return jdbcTemplate.queryForList(
        "SELECT cause FROM group_membership_history WHERE group_id = ?", String.class, groupId);
  }

  private int openIntervals(UUID groupId, UUID userId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM group_membership_history"
                + " WHERE group_id = ? AND user_id = ? AND valid_to IS NULL",
            Integer.class,
            groupId,
            userId);
    return count == null ? 0 : count;
  }

  private int auditCount(UUID groupId, AuditEventType type) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log WHERE object_id = ? AND event_type = ?",
            Integer.class,
            groupId.toString(),
            type.name());
    return count == null ? 0 : count;
  }

  private String auditAfter(UUID groupId, AuditEventType type) {
    return jdbcTemplate.queryForObject(
        "SELECT after::text FROM audit_log WHERE object_id = ? AND event_type = ?",
        String.class,
        groupId.toString(),
        type.name());
  }
}
