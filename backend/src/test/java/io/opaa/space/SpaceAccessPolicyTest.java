package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSubject;
import io.opaa.permission.GroupSubjectDirectory;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pure JUnit tests (no Spring context) for {@link SpaceAccessPolicy#effectiveRole}, {@link
 * SpaceAccessPolicy#hasAtLeast} and {@link SpaceAccessPolicy#hasCapableAdminAfter} - the rank
 * matrix every {@code require*} guard builds on, plus the group half #1815 adds. #891 review:
 * exercises every combination of (no membership/MEMBER/CURATOR/ADMIN) × (owner yes/no) against
 * every {@link SpaceRole} bar, rather than relying on the guard-level integration tests to cover
 * the matrix indirectly.
 */
class SpaceAccessPolicyTest {

  private static final UUID ORGANIZATION = UUID.randomUUID();

  private final GroupMembershipResolver groupMemberships = mock(GroupMembershipResolver.class);
  private final GroupSubjectDirectory groupDirectory = mock(GroupSubjectDirectory.class);
  private final SpaceAccessPolicy policy =
      new SpaceAccessPolicy(
          groupMemberships, groupDirectory, mock(SpaceMembershipRepository.class));

  private Space spaceWithOwner(UUID ownerId) {
    return new Space("Team", null, false, SpaceVisibility.PRIVATE, ownerId, ORGANIZATION);
  }

  private static SpaceRole roleOf(Space space, UUID userId) {
    return SpaceAccessPolicy.effectiveRole(space, userId, Set.of());
  }

  @Test
  void effectiveRoleIsNullForANonMemberNonOwner() {
    UUID owner = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    Space space = spaceWithOwner(owner);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, ORGANIZATION));

    assertThat(roleOf(space, stranger)).isNull();
  }

  @ParameterizedTest
  @MethodSource("allRoles")
  void effectiveRoleOfANonOwnerMemberIsTheirRawMembershipRole(SpaceRole role) {
    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    Space space = spaceWithOwner(owner);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, ORGANIZATION));
    space.addMembership(SpaceMembership.ofUser(member, role, ORGANIZATION));

    assertThat(roleOf(space, member)).isEqualTo(role);
  }

  @ParameterizedTest
  @MethodSource("allRoles")
  void effectiveRoleOfTheOwnerIsAlwaysAtLeastAdminRegardlessOfTheirRawMembershipRole(
      SpaceRole rawRole) {
    UUID owner = UUID.randomUUID();
    Space space = spaceWithOwner(owner);
    space.addMembership(SpaceMembership.ofUser(owner, rawRole, ORGANIZATION));

    assertThat(roleOf(space, owner)).isEqualTo(SpaceRole.ADMIN);
  }

  @Test
  void effectiveRoleOfTheOwnerIsAdminEvenWithoutAMembershipRow() {
    // Unreachable through the public API but effectiveRole itself makes no membership-row
    // assumption - only the require* guards do.
    UUID owner = UUID.randomUUID();
    Space space = spaceWithOwner(owner);

    assertThat(roleOf(space, owner)).isEqualTo(SpaceRole.ADMIN);
  }

  @ParameterizedTest
  @MethodSource("allRoles")
  void aMemberOfAGroupMembershipHoldsTheGroupsRoleWithoutARowOfTheirOwn(SpaceRole groupRole) {
    UUID owner = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    UUID person = UUID.randomUUID();
    Space space = spaceWithOwner(owner);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, ORGANIZATION));
    space.addMembership(SpaceMembership.ofGroup(group, groupRole, 7, ORGANIZATION));

    assertThat(SpaceAccessPolicy.effectiveRole(space, person, Set.of(group))).isEqualTo(groupRole);
    assertThat(roleOf(space, person)).as("without that group membership").isNull();
  }

  /** ADR-0036, Entscheidung 6: the effective role is the best of the two, not the last one seen. */
  @Test
  void theBetterOfDirectAndGroupMembershipWins() {
    UUID owner = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    UUID person = UUID.randomUUID();
    Space space = spaceWithOwner(owner);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, ORGANIZATION));
    space.addMembership(SpaceMembership.ofUser(person, SpaceRole.MEMBER, ORGANIZATION));
    space.addMembership(SpaceMembership.ofGroup(group, SpaceRole.CURATOR, 7, ORGANIZATION));

    assertThat(SpaceAccessPolicy.effectiveRole(space, person, Set.of(group)))
        .isEqualTo(SpaceRole.CURATOR);
  }

  @Test
  void theBetterOfTwoGroupMembershipsWinsRegardlessOfTheirOrder() {
    UUID owner = UUID.randomUUID();
    UUID higher = UUID.randomUUID();
    UUID lower = UUID.randomUUID();
    UUID person = UUID.randomUUID();
    Space space = spaceWithOwner(owner);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, ORGANIZATION));
    space.addMembership(SpaceMembership.ofGroup(higher, SpaceRole.ADMIN, 7, ORGANIZATION));
    space.addMembership(SpaceMembership.ofGroup(lower, SpaceRole.MEMBER, 7, ORGANIZATION));

    assertThat(SpaceAccessPolicy.effectiveRole(space, person, Set.of(higher, lower)))
        .isEqualTo(SpaceRole.ADMIN);
    assertThat(SpaceAccessPolicy.effectiveRole(space, person, Set.of(lower, higher)))
        .isEqualTo(SpaceRole.ADMIN);
  }

  @ParameterizedTest
  @MethodSource("rankMatrix")
  void hasAtLeastMatchesTheDeclaredRankOrdering(
      SpaceRole membershipRole, boolean owner, SpaceRole minRole, boolean expected) {
    UUID subject = UUID.randomUUID();
    UUID otherOwner = UUID.randomUUID();
    Space space = spaceWithOwner(owner ? subject : otherOwner);
    space.addMembership(SpaceMembership.ofUser(subject, membershipRole, ORGANIZATION));
    if (!owner) {
      space.addMembership(SpaceMembership.ofUser(otherOwner, SpaceRole.ADMIN, ORGANIZATION));
    }
    when(groupMemberships.groupIdsForUser(subject)).thenReturn(Set.of());

    assertThat(policy.hasAtLeast(space, subject, minRole)).isEqualTo(expected);
  }

  @Test
  void hasAtLeastIsFalseForANonMemberNonOwnerRegardlessOfTheRequiredRole() {
    UUID owner = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    Space space = spaceWithOwner(owner);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, ORGANIZATION));
    when(groupMemberships.groupIdsForUser(stranger)).thenReturn(Set.of());

    for (SpaceRole minRole : SpaceRole.values()) {
      assertThat(policy.hasAtLeast(space, stranger, minRole)).isFalse();
    }
  }

  /**
   * ADR-0036, Entscheidung 6: an effective group with at least one active account counts as the
   * space's ADMIN; one that has lost its last account, is dissolved, or belongs to a switched-off
   * provider does not - it stays a member and simply holds the space for nobody.
   */
  @ParameterizedTest
  @MethodSource("groupCapability")
  void aGroupCountsAsAdminOnlyWhileItCanAct(
      boolean dissolved, boolean providerDisabled, int activeMembers, boolean expected) {
    UUID owner = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    Space space = spaceWithOwner(owner);
    SpaceMembership ownerRow = SpaceMembership.ofUser(owner, SpaceRole.MEMBER, ORGANIZATION);
    space.addMembership(ownerRow);
    space.addMembership(SpaceMembership.ofGroup(group, SpaceRole.ADMIN, 7, ORGANIZATION));
    when(groupDirectory.find(group))
        .thenReturn(
            Optional.of(
                new GroupSubject(group, ORGANIZATION, "Referat 50", dissolved, providerDisabled)));
    when(groupMemberships.activeMemberCount(eq(group), any())).thenReturn(activeMembers);

    // The owner's row is removed in the hypothetical, so only the group can still hold the space.
    assertThat(policy.hasCapableAdminAfter(space, ownerRow, null)).isEqualTo(expected);
  }

  /**
   * The owner's own row always counts, whatever role it carries - which is why "Nachfolge offen" is
   * not reachable for a space through the API today (see {@link
   * SpaceAccessPolicy#hasCapableAdmin}).
   */
  @Test
  void theOwnersOwnRowCountsAsAdminWhateverRoleItCarries() {
    UUID owner = UUID.randomUUID();
    Space space = spaceWithOwner(owner);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.MEMBER, ORGANIZATION));

    assertThat(policy.hasCapableAdmin(space)).isTrue();
  }

  @Test
  void aDowngradeOfTheLastAdminPersonLeavesNoCapableAdminBehind() {
    UUID owner = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    Space space = spaceWithOwner(owner);
    SpaceMembership adminRow = SpaceMembership.ofUser(admin, SpaceRole.ADMIN, ORGANIZATION);
    space.addMembership(adminRow);
    space.addMembership(SpaceMembership.ofUser(UUID.randomUUID(), SpaceRole.MEMBER, ORGANIZATION));

    assertThat(policy.hasCapableAdminAfter(space, adminRow, SpaceRole.CURATOR)).isFalse();
    assertThat(policy.hasCapableAdminAfter(space, adminRow, SpaceRole.ADMIN)).isTrue();
  }

  private static Stream<SpaceRole> allRoles() {
    return Stream.of(SpaceRole.values());
  }

  /** (dissolved, provider disabled, active accounts, counts as the space's ADMIN). */
  private static Stream<Arguments> groupCapability() {
    return Stream.of(
        Arguments.of(false, false, 3, true),
        Arguments.of(false, false, 0, false),
        Arguments.of(true, false, 3, false),
        Arguments.of(false, true, 3, false));
  }

  /**
   * (membership role, is owner, required minRole, expected hasAtLeast) - the owner rows prove the
   * owner⇒ADMIN floor applies against every bar, including one the raw membership role alone would
   * not clear.
   */
  private static Stream<Arguments> rankMatrix() {
    return Stream.of(
        // Non-owner: hasAtLeast follows the raw membership role exactly.
        Arguments.of(SpaceRole.MEMBER, false, SpaceRole.MEMBER, true),
        Arguments.of(SpaceRole.MEMBER, false, SpaceRole.CURATOR, false),
        Arguments.of(SpaceRole.MEMBER, false, SpaceRole.ADMIN, false),
        Arguments.of(SpaceRole.CURATOR, false, SpaceRole.MEMBER, true),
        Arguments.of(SpaceRole.CURATOR, false, SpaceRole.CURATOR, true),
        Arguments.of(SpaceRole.CURATOR, false, SpaceRole.ADMIN, false),
        Arguments.of(SpaceRole.ADMIN, false, SpaceRole.MEMBER, true),
        Arguments.of(SpaceRole.ADMIN, false, SpaceRole.CURATOR, true),
        Arguments.of(SpaceRole.ADMIN, false, SpaceRole.ADMIN, true),
        // Owner: the ADMIN floor clears every bar even for a MEMBER/CURATOR membership.
        Arguments.of(SpaceRole.MEMBER, true, SpaceRole.MEMBER, true),
        Arguments.of(SpaceRole.MEMBER, true, SpaceRole.CURATOR, true),
        Arguments.of(SpaceRole.MEMBER, true, SpaceRole.ADMIN, true),
        Arguments.of(SpaceRole.CURATOR, true, SpaceRole.MEMBER, true),
        Arguments.of(SpaceRole.CURATOR, true, SpaceRole.CURATOR, true),
        Arguments.of(SpaceRole.CURATOR, true, SpaceRole.ADMIN, true),
        Arguments.of(SpaceRole.ADMIN, true, SpaceRole.MEMBER, true),
        Arguments.of(SpaceRole.ADMIN, true, SpaceRole.CURATOR, true),
        Arguments.of(SpaceRole.ADMIN, true, SpaceRole.ADMIN, true));
  }
}
