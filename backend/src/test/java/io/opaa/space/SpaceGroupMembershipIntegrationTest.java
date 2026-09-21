package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupRepository;
import io.opaa.group.GroupService;
import io.opaa.group.GroupSteward;
import io.opaa.group.GroupStewardRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetOwnershipHistoryCause;
import io.opaa.permission.AssetOwnershipHistoryRepository;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.PermissionSubject;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A group as a space member (#1815, ADR-0036 Entscheidungen 6, 8 und 9) against the real, versioned
 * Liquibase schema: the subject columns of {@code space_memberships}, the effective role, the
 * protection of the last capable ADMIN, the growth signal and the rights history all carry real
 * foreign keys that only the changelog creates.
 */
@OpaaIntegrationTest
class SpaceGroupMembershipIntegrationTest {

  @Autowired private SpaceService spaceService;
  @Autowired private SpaceAccessPolicy accessPolicy;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private SpaceMembershipRepository membershipRepository;
  @Autowired private SpaceMembershipHistoryRepository historyRepository;
  @Autowired private SpaceMembershipHistoryService historyService;
  @Autowired private AssetOwnershipHistoryRepository ownershipHistoryRepository;
  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupStewardRepository stewardRepository;
  @Autowired private GroupMembershipResolver groupMembershipResolver;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationA;
  private UUID organizationB;

  @BeforeEach
  void createOrganizations() {
    organizationA =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org A")).getId();
    organizationB =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org B")).getId();
  }

  @AfterEach
  void tearDown() {
    // Groups are not part of the shared fixture helper and reference their organization with
    // RESTRICT, so this class removes its own. The order matters:
    // space_memberships.group_id is RESTRICT, so those rows have to go before the groups do.
    for (String table :
        List.of(
            "space_memberships",
            "space_membership_history",
            "asset_ownership_history",
            "group_membership_history",
            "group_memberships",
            "group_stewards",
            "groups")) {
      jdbcTemplate.update(
          "DELETE FROM " + table + " WHERE organization_id IN (?, ?)",
          organizationA,
          organizationB);
    }
    ownOrganizationFixtures.removeOrganizations(organizationA, organizationB);
  }

  // -------------------------------------------------------------------------------------------
  // Effective role
  // -------------------------------------------------------------------------------------------

  @Test
  void aGroupBecomesAMemberAndItsMembersHoldTheRoleWithoutARowOfTheirOwn() {
    UUID owner = createUser(organizationA);
    UUID person = createUser(organizationA);
    UUID group = createGroup(organizationA, "Referat 50", person);
    Space space = createSpace(owner);

    SpaceMemberView view =
        spaceService.addMember(
            space.getId(), groupSubject(group), SpaceRole.CURATOR, currentUserOf(owner));

    assertThat(view.membership().getSubjectType()).isEqualTo(PermissionSubjectType.GROUP);
    assertThat(view.displayName()).isEqualTo("Referat 50");
    assertThat(membershipRepository.findBySpaceId(space.getId()))
        .noneMatch(membership -> person.equals(membership.getUserId()));
    assertThat(effectiveRoleOf(space.getId(), person)).isEqualTo(SpaceRole.CURATOR);
    assertThat(accessPolicy.isMember(space.getId(), person)).isTrue();
  }

  @Test
  void leavingTheGroupEndsTheAccessWithoutAFurtherStepAndWithoutAStaleCache() {
    UUID owner = createUser(organizationA);
    UUID person = createUser(organizationA);
    UUID group = createGroup(organizationA, "Referat 50", person);
    makeSteward(group, owner);
    Space space = createSpace(owner);
    spaceService.addMember(
        space.getId(), groupSubject(group), SpaceRole.MEMBER, currentUserOf(owner));
    // Warms GroupMembershipResolver's per-user cache before the removal, so a cache that outlived
    // the change would keep the access alive here.
    assertThat(accessPolicy.isMember(space.getId(), person)).isTrue();

    groupService.removeMember(group, person, currentUserOf(owner));

    assertThat(accessPolicy.isMember(space.getId(), person)).isFalse();
    assertThat(effectiveRoleOf(space.getId(), person)).isNull();
  }

  @Test
  void theHigherOfADirectAndAGroupMembershipApplies() {
    UUID owner = createUser(organizationA);
    UUID person = createUser(organizationA);
    UUID group = createGroup(organizationA, "Referat 50", person);
    Space space = createSpace(owner);
    spaceService.addMember(
        space.getId(),
        PermissionSubject.user(person, organizationA),
        SpaceRole.MEMBER,
        currentUserOf(owner));
    spaceService.addMember(
        space.getId(), groupSubject(group), SpaceRole.ADMIN, currentUserOf(owner));

    assertThat(effectiveRoleOf(space.getId(), person)).isEqualTo(SpaceRole.ADMIN);
  }

  @Test
  void aSpaceReachedOnlyThroughAGroupAppearsInTheOwnSpaceList() {
    UUID owner = createUser(organizationA);
    UUID person = createUser(organizationA);
    UUID group = createGroup(organizationA, "Referat 50", person);
    Space space = createSpace(owner);
    spaceService.addMember(
        space.getId(), groupSubject(group), SpaceRole.MEMBER, currentUserOf(owner));

    assertThat(spaceService.listSpaces(currentUserOf(person)))
        .extracting(overview -> overview.space().getId())
        .contains(space.getId());
  }

  // -------------------------------------------------------------------------------------------
  // Which groups may be admitted
  // -------------------------------------------------------------------------------------------

  @Test
  void aDissolvedGroupCannotBeAdmitted() {
    UUID owner = createUser(organizationA);
    UUID group = createGroup(organizationA, "Referat 50", createUser(organizationA));
    Group dissolved = groupRepository.findById(group).orElseThrow();
    dissolved.dissolve(Instant.now());
    groupRepository.save(dissolved);
    Space space = createSpace(owner);

    assertThatThrownBy(
            () ->
                spaceService.addMember(
                    space.getId(), groupSubject(group), SpaceRole.MEMBER, currentUserOf(owner)))
        .isInstanceOf(ConflictException.class);
    assertThat(membershipRepository.findBySpaceId(space.getId())).hasSize(1);
  }

  @Test
  void aGroupOfAnotherOrganizationCannotBecomeAMember() {
    UUID owner = createUser(organizationA);
    UUID foreignGroup = createGroup(organizationB, "Fremdes Referat", createUser(organizationB));
    Space space = createSpace(owner);

    assertThatThrownBy(
            () ->
                spaceService.addMember(
                    space.getId(),
                    PermissionSubject.group(foreignGroup, organizationB),
                    SpaceRole.MEMBER,
                    currentUserOf(owner)))
        .isInstanceOf(NotFoundException.class);
    assertThat(membershipRepository.findBySpaceId(space.getId())).hasSize(1);
  }

  /**
   * ADR-0036, Entscheidung 6: an effective but empty group is admitted on purpose - otherwise
   * "create the group, admit it, then fill it" failed at the first step. The response carries the
   * warning.
   */
  @Test
  void anEffectiveButEmptyGroupIsAdmittedWithAWarning() {
    UUID owner = createUser(organizationA);
    UUID group = createGroup(organizationA, "Noch leer");
    Space space = createSpace(owner);

    SpaceMemberView view =
        spaceService.addMember(
            space.getId(), groupSubject(group), SpaceRole.MEMBER, currentUserOf(owner));

    assertThat(view.groupSize().emptyGroup()).isTrue();
    assertThat(membershipRepository.findBySpaceId(space.getId())).hasSize(2);
  }

  @Test
  void theSameGroupCannotBeAdmittedTwice() {
    UUID owner = createUser(organizationA);
    UUID group = createGroup(organizationA, "Referat 50", createUser(organizationA));
    Space space = createSpace(owner);
    spaceService.addMember(
        space.getId(), groupSubject(group), SpaceRole.MEMBER, currentUserOf(owner));

    assertThatThrownBy(
            () ->
                spaceService.addMember(
                    space.getId(), groupSubject(group), SpaceRole.CURATOR, currentUserOf(owner)))
        .isInstanceOf(ConflictException.class);
  }

  // -------------------------------------------------------------------------------------------
  // The growth signal (ADR-0036, Entscheidung 9)
  // -------------------------------------------------------------------------------------------

  @Test
  void theMemberCountAtGrantIsStoredAndComparedAgainstTodaysFigure() {
    UUID owner = createUser(organizationA);
    UUID group = createGroup(organizationA, "Referat 50", fiveUsers(organizationA));
    makeSteward(group, owner);
    Space space = createSpace(owner);
    spaceService.addMember(
        space.getId(), groupSubject(group), SpaceRole.MEMBER, currentUserOf(owner));

    groupService.addMember(group, createUser(organizationA), currentUserOf(owner));
    SpaceMemberView view = groupRow(space.getId(), owner);

    assertThat(view.membership().getMemberCountAtGrant()).isEqualTo(5);
    assertThat(view.groupSize().memberCountAtGrant()).isEqualTo(5);
    assertThat(view.groupSize().memberCountNow()).isEqualTo(6);
    assertThat(view.groupSize().smallGroup()).isFalse();
  }

  /** Below the enforced minimum group size both figures are withheld, not just the smaller one. */
  @Test
  void bothFiguresAreWithheldForASmallGroup() {
    UUID owner = createUser(organizationA);
    UUID group = createGroup(organizationA, "Kleine Runde", createUser(organizationA));
    Space space = createSpace(owner);
    spaceService.addMember(
        space.getId(), groupSubject(group), SpaceRole.MEMBER, currentUserOf(owner));

    SpaceMemberView view = groupRow(space.getId(), owner);

    assertThat(view.groupSize().smallGroup()).isTrue();
    assertThat(view.groupSize().memberCountAtGrant()).isNull();
    assertThat(view.groupSize().memberCountNow()).isNull();
    assertThat(view.membership().getMemberCountAtGrant())
        .as("the stored figure itself is untouched; only the disclosure is suppressed")
        .isEqualTo(1);
  }

  /**
   * ADR-0036, Entscheidung 7, Schutzpunkt 3 (#1818): counted are active accounts, not membership
   * rows. Six members with two of them locked by the directory synchronisation are a group of four
   * - and four is below the Mindestgruppengröße, so both figures are withheld.
   */
  @Test
  void accountsLockedByTheDirectorySynchronisationDoNotCount() {
    UUID owner = createUser(organizationA);
    UUID[] members = new UUID[6];
    for (int i = 0; i < members.length; i++) {
      members[i] = createUser(organizationA);
    }
    UUID group = createGroup(organizationA, "Referat 50", members);
    Space space = createSpace(owner);
    spaceService.addMember(
        space.getId(), groupSubject(group), SpaceRole.MEMBER, currentUserOf(owner));
    assertThat(groupRow(space.getId(), owner).groupSize().smallGroup())
        .as("six active accounts are no small group")
        .isFalse();

    lockFromDirectory(members[0]);
    lockFromDirectory(members[1]);

    SpaceMemberView view = groupRow(space.getId(), owner);
    assertThat(view.groupSize().smallGroup()).isTrue();
    assertThat(view.groupSize().memberCountNow()).isNull();
    assertThat(view.membership().getMemberCountAtGrant())
        .as("the stored figure of the admission is untouched by a later lock")
        .isEqualTo(6);
    assertThat(groupMembershipResolver.activeMemberCount(group, organizationA)).isEqualTo(4);
    assertThat(
            groupMembershipResolver.resolveUserIds(PermissionSubject.group(group, organizationA)))
        .as("the rights resolution is unchanged: a locked account keeps its membership")
        .hasSize(6);
  }

  // -------------------------------------------------------------------------------------------
  // Protection of the last capable ADMIN (ADR-0036, Entscheidung 6, Schutzregel 1)
  // -------------------------------------------------------------------------------------------

  @Test
  void aCapableGroupHoldsTheSpaceAsItsLastAdminMember() {
    UUID owner = createUser(organizationA);
    UUID group = createGroup(organizationA, "Referat 50", createUser(organizationA));
    makeSteward(group, owner);
    Space space = createSpace(owner);
    spaceService.addMember(
        space.getId(), groupSubject(group), SpaceRole.ADMIN, currentUserOf(owner));
    Space reloaded = spaceRepository.findByIdWithMemberships(space.getId()).orElseThrow();
    SpaceMembership ownerRow = rowOfUser(reloaded, owner);

    assertThat(accessPolicy.hasCapableAdminAfter(reloaded, ownerRow, null)).isTrue();

    groupService.removeMember(group, onlyMemberOf(group, owner), currentUserOf(owner));
    Space afterEmptying = spaceRepository.findByIdWithMemberships(space.getId()).orElseThrow();

    assertThat(
            accessPolicy.hasCapableAdminAfter(afterEmptying, rowOfUser(afterEmptying, owner), null))
        .as("a group without an active account no longer counts as the space's ADMIN")
        .isFalse();
  }

  /**
   * The owner protection, which #1815 moves from {@code 400} to <b>{@code 409}</b> (ADR-0036,
   * Entscheidung 6 names the old status explicitly as the thing to straighten out). Pinned on the
   * <em>message</em>, not only on the exception type: {@code requireCapableAdminRemains} throws the
   * same {@link ConflictException}, so without this the test would pass while claiming to exercise
   * a guard it never reaches. That guard has its own test in {@code
   * SpaceServiceTest#theLastCapableAdminIsProtectedEvenWhenTheOwnerRuleDoesNotApply}.
   */
  @Test
  void removingOrDowngradingTheOwnerIsRefusedWithAConflictNotAValidationError() {
    UUID owner = createUser(organizationA);
    UUID admin = createUser(organizationA);
    Space space = createSpace(owner);
    spaceService.addMember(
        space.getId(),
        PermissionSubject.user(admin, organizationA),
        SpaceRole.ADMIN,
        currentUserOf(owner));
    UUID ownerMembership = membershipIdOfUser(space.getId(), owner);

    assertThatThrownBy(
            () -> spaceService.removeMember(space.getId(), ownerMembership, currentUserOf(owner)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Der Eigentümer kann nicht entfernt werden");
    assertThatThrownBy(
            () ->
                spaceService.updateMemberRole(
                    space.getId(), ownerMembership, SpaceRole.MEMBER, currentUserOf(owner)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Die Rolle des Eigentümers kann nicht geändert werden");
  }

  // -------------------------------------------------------------------------------------------
  // Rights history (ADR-0036, Entscheidung 8)
  // -------------------------------------------------------------------------------------------

  @Test
  void everyChangeToAGroupMembershipIsRecordedAsAnInterval() {
    UUID owner = createUser(organizationA);
    UUID group = createGroup(organizationA, "Referat 50", createUser(organizationA));
    Space space = createSpace(owner);
    spaceService.addMember(
        space.getId(), groupSubject(group), SpaceRole.MEMBER, currentUserOf(owner));
    UUID membershipId = membershipIdOfGroup(space.getId(), group);
    spaceService.updateMemberRole(
        space.getId(), membershipId, SpaceRole.CURATOR, currentUserOf(owner));
    spaceService.removeMember(space.getId(), membershipId, currentUserOf(owner));

    List<SpaceMembershipHistory> intervals =
        historyRepository.findAll().stream()
            .filter(row -> group.equals(row.getSubjectGroupId()))
            .sorted((a, b) -> a.getValidFrom().compareTo(b.getValidFrom()))
            .toList();

    assertThat(intervals)
        .extracting(SpaceMembershipHistory::getCause)
        .containsExactly(
            SpaceMembershipHistoryCause.ADDED,
            SpaceMembershipHistoryCause.ROLE_CHANGED,
            SpaceMembershipHistoryCause.REMOVED);
    assertThat(intervals).allMatch(row -> row.getValidTo() != null);
    assertThat(intervals.get(0).getValidTo()).isEqualTo(intervals.get(1).getValidFrom());
  }

  /**
   * The acceptance criterion of #1815: "was X a member of space Z on day Y" - and above all its
   * negation - is answerable, through a direct membership and through a group.
   */
  @Test
  void theStichtagReconstructionAnswersForBothSubjectKinds() {
    UUID owner = createUser(organizationA);
    UUID person = createUser(organizationA);
    UUID group = createGroup(organizationA, "Referat 50");
    makeSteward(group, owner);
    Space space = createSpace(owner);
    // Through the service, not through the repository: the reconstruction resolves the group half
    // from group_membership_history, which only GroupService writes.
    groupService.addMember(group, person, currentUserOf(owner));
    Instant beforeAdmission = Instant.now();
    spaceService.addMember(
        space.getId(), groupSubject(group), SpaceRole.MEMBER, currentUserOf(owner));
    Instant whileMember = Instant.now();

    assertThat(historyService.spaceIdsAsOf(person, organizationA, beforeAdmission))
        .doesNotContain(space.getId());
    assertThat(historyService.spaceIdsAsOf(person, organizationA, whileMember))
        .contains(space.getId());
    assertThat(historyService.spaceIdsAsOf(owner, organizationA, whileMember))
        .as("the owner reaches the space through their own row")
        .contains(space.getId());
  }

  @Test
  void deletingTheSpaceClosesEveryOpenInterval() {
    UUID owner = createUser(organizationA);
    UUID group = createGroup(organizationA, "Referat 50", createUser(organizationA));
    Space space = createSpace(owner);
    spaceService.addMember(
        space.getId(), groupSubject(group), SpaceRole.MEMBER, currentUserOf(owner));

    spaceService.deleteSpace(space.getId(), currentUserOf(owner));

    assertThat(historyRepository.findBySpaceIdAndValidToIsNull(space.getId())).isEmpty();
    assertThat(
            ownershipHistoryRepository.findByAssetTypeAndAssetIdAndValidToIsNull(
                Space.ASSET_TYPE, space.getId()))
        .isEmpty();
    // The deletion is an ownership event of its own and carries its actor - the closed interval
    // keeps the cause it was opened with.
    assertThat(ownershipHistoryRepository.findAll())
        .filteredOn(row -> space.getId().equals(row.getAssetId()))
        .filteredOn(row -> row.getCause() == AssetOwnershipHistoryCause.ASSET_DELETED)
        .singleElement()
        .satisfies(
            marker -> {
              assertThat(marker.getActorUserId()).isEqualTo(owner);
              assertThat(marker.getOwnerUserId()).isEqualTo(owner);
              assertThat(marker.getValidTo()).isEqualTo(marker.getValidFrom());
            });
  }

  // -------------------------------------------------------------------------------------------
  // Freigabe zur Verwendung
  // -------------------------------------------------------------------------------------------

  /**
   * An internal group its stewards have not released is no subject on this path either (#1814,
   * ADR-0036 Entscheidung 9, Festlegung 2: the rule holds for every way, and the space member
   * administration is named in it). The id typed by hand gets the same answer an unknown group gets
   * - a 403 would confirm that a group with this id exists.
   */
  @Test
  void anUnreleasedGroupCannotBeAdmittedToASpaceByAThirdParty() {
    UUID owner = createUser(organizationA);
    UUID person = createUser(organizationA);
    UUID group = createUnreleasedGroup(organizationA, "Personalrat", person);
    Space space = createSpace(owner);

    assertThatThrownBy(
            () ->
                spaceService.addMember(
                    space.getId(), groupSubject(group), SpaceRole.MEMBER, currentUserOf(owner)))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Gruppe nicht gefunden");
    assertThat(membershipRepository.findBySpaceId(space.getId()))
        .noneMatch(SpaceMembership::isGroupSubject);
  }

  /** Its own members keep seeing it - the rule hides it from third parties only. */
  @Test
  void aMemberOfAnUnreleasedGroupMayStillAdmitItToTheirOwnSpace() {
    UUID owner = createUser(organizationA);
    UUID group = createUnreleasedGroup(organizationA, "Personalrat", owner);
    Space space = createSpace(owner);

    SpaceMemberView view =
        spaceService.addMember(
            space.getId(), groupSubject(group), SpaceRole.MEMBER, currentUserOf(owner));

    assertThat(view.membership().getGroupId()).isEqualTo(group);
  }

  // -------------------------------------------------------------------------------------------
  // Deletion guards
  // -------------------------------------------------------------------------------------------

  @Test
  void aGroupThatIsASpaceMemberCannotBeDeleted() {
    UUID owner = createUser(organizationA);
    UUID group = createGroup(organizationA, "Referat 50", createUser(organizationA));
    makeSteward(group, owner);
    Space space = createSpace(owner);
    spaceService.addMember(
        space.getId(), groupSubject(group), SpaceRole.MEMBER, currentUserOf(owner));

    assertThatThrownBy(() -> groupService.deleteGroup(group, currentUserOf(owner)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("1 Space");
    assertThat(groupRepository.findById(group)).isPresent();
  }

  // -------------------------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------------------------

  private SpaceRole effectiveRoleOf(UUID spaceId, UUID userId) {
    Space space = spaceRepository.findByIdWithMemberships(spaceId).orElseThrow();
    return accessPolicy.effectiveRole(space, userId);
  }

  private SpaceMemberView groupRow(UUID spaceId, UUID caller) {
    return spaceService.listMembers(spaceId, currentUserOf(caller)).stream()
        .filter(view -> view.membership().isGroupSubject())
        .findFirst()
        .orElseThrow();
  }

  private static SpaceMembership rowOfUser(Space space, UUID userId) {
    return space.getMemberships().stream()
        .filter(membership -> membership.isUserSubject() && userId.equals(membership.getUserId()))
        .findFirst()
        .orElseThrow();
  }

  private UUID membershipIdOfUser(UUID spaceId, UUID userId) {
    return membershipRepository.findBySpaceId(spaceId).stream()
        .filter(membership -> userId.equals(membership.getUserId()))
        .findFirst()
        .orElseThrow()
        .getId();
  }

  private UUID membershipIdOfGroup(UUID spaceId, UUID groupId) {
    return membershipRepository.findBySpaceId(spaceId).stream()
        .filter(membership -> groupId.equals(membership.getGroupId()))
        .findFirst()
        .orElseThrow()
        .getId();
  }

  private PermissionSubject groupSubject(UUID groupId) {
    return PermissionSubject.group(groupId, organizationA);
  }

  private Space createSpace(UUID owner) {
    return spaceService.createSpace(
        new SpaceCreation("Team", "Team docs", owner, SpaceVisibility.PRIVATE, List.of(), null),
        currentUserOf(owner));
  }

  /**
   * Read through the service rather than through {@code Group#getMemberships()}: the collection is
   * lazy and this test runs outside a session.
   */
  private UUID onlyMemberOf(UUID groupId, UUID caller) {
    return groupService.listMembers(groupId, currentUserOf(caller)).stream()
        .map(view -> view.membership().getUserId())
        .findFirst()
        .orElseThrow();
  }

  private UUID[] fiveUsers(UUID organizationId) {
    UUID[] users = new UUID[5];
    for (int i = 0; i < users.length; i++) {
      users[i] = createUser(organizationId);
    }
    return users;
  }

  /**
   * Released for use on purpose (#1814, ADR-0036 Entscheidung 9): every test here admits the group
   * to a space through a third party, and an unreleased internal group answers such a caller like
   * one that does not exist.
   */
  private UUID createGroup(UUID organizationId, String name, UUID... memberIds) {
    Group group = Group.internal(organizationId, name, null, null);
    group.release(true);
    for (UUID memberId : memberIds) {
      group.addMembership(new GroupMembership(memberId, organizationId));
    }
    UUID groupId = groupRepository.save(group).getId();
    groupMembershipResolver.invalidateUsers(List.of(memberIds));
    return groupId;
  }

  /** The delivered state of a new internal group: nobody but its own people may name it. */
  private UUID createUnreleasedGroup(UUID organizationId, String name, UUID... memberIds) {
    Group group = Group.internal(organizationId, name, null, null);
    for (UUID memberId : memberIds) {
      group.addMembership(new GroupMembership(memberId, organizationId));
    }
    UUID groupId = groupRepository.save(group).getId();
    groupMembershipResolver.invalidateUsers(List.of(memberIds));
    return groupId;
  }

  /**
   * Makes the caller a steward of the group: since #1814 only a steward or a system administrator
   * maintains an internal group's membership, and these tests change it through the service.
   */
  private void makeSteward(UUID groupId, UUID userId) {
    UUID organizationId = groupRepository.findById(groupId).orElseThrow().getOrganizationId();
    stewardRepository.save(new GroupSteward(groupId, userId, organizationId, userId));
  }

  /** Takes the access away the way the directory run does (#1818). */
  private void lockFromDirectory(UUID userId) {
    User user = userRepository.findById(userId).orElseThrow();
    user.lockFromDirectory(java.time.Instant.now());
    userRepository.save(user);
  }

  private UUID createUser(UUID organizationId) {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
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
}
