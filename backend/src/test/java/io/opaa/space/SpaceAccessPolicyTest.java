package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pure JUnit tests (no Spring context) for {@link SpaceAccessPolicy#effectiveRole} and {@link
 * SpaceAccessPolicy#hasAtLeast} - the rank matrix every {@code require*} guard in this class builds
 * on. #891 review: exercises every combination of (no membership/MEMBER/CURATOR/ADMIN) × (owner
 * yes/no) against every {@link SpaceRole} bar, rather than relying on the guard-level integration
 * tests in {@code SpaceServiceIntegrationTest}/{@code SpaceAssetAssociationServiceIntegrationTest}
 * to cover the full matrix indirectly.
 */
class SpaceAccessPolicyTest {

  private static final UUID ORGANIZATION = UUID.randomUUID();

  private Space spaceWithOwner(UUID ownerId) {
    return new Space("Team", null, false, SpaceVisibility.PRIVATE, ownerId, ORGANIZATION);
  }

  @Test
  void effectiveRoleIsNullForANonMemberNonOwner() {
    UUID owner = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    Space space = spaceWithOwner(owner);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, ORGANIZATION));

    assertThat(SpaceAccessPolicy.effectiveRole(space, stranger)).isNull();
  }

  @ParameterizedTest
  @MethodSource("nonOwnerRoles")
  void effectiveRoleOfANonOwnerMemberIsTheirRawMembershipRole(SpaceRole role) {
    UUID owner = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    Space space = spaceWithOwner(owner);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, ORGANIZATION));
    space.addMembership(new SpaceMembership(member, role, ORGANIZATION));

    assertThat(SpaceAccessPolicy.effectiveRole(space, member)).isEqualTo(role);
  }

  @ParameterizedTest
  @MethodSource("allRoles")
  void effectiveRoleOfTheOwnerIsAlwaysAtLeastAdminRegardlessOfTheirRawMembershipRole(
      SpaceRole rawRole) {
    UUID owner = UUID.randomUUID();
    Space space = spaceWithOwner(owner);
    space.addMembership(new SpaceMembership(owner, rawRole, ORGANIZATION));

    assertThat(SpaceAccessPolicy.effectiveRole(space, owner)).isEqualTo(SpaceRole.ADMIN);
  }

  @Test
  void effectiveRoleOfTheOwnerIsAdminEvenWithoutAMembershipRow() {
    // Unreachable through the public API (see SpaceAccessPolicy#requireManager's Javadoc) but
    // effectiveRole itself makes no membership-row assumption - only the require* guards do.
    UUID owner = UUID.randomUUID();
    Space space = spaceWithOwner(owner);

    assertThat(SpaceAccessPolicy.effectiveRole(space, owner)).isEqualTo(SpaceRole.ADMIN);
  }

  @ParameterizedTest
  @MethodSource("rankMatrix")
  void hasAtLeastMatchesTheDeclaredRankOrdering(
      SpaceRole membershipRole, boolean owner, SpaceRole minRole, boolean expected) {
    UUID subject = UUID.randomUUID();
    UUID otherOwner = UUID.randomUUID();
    Space space = spaceWithOwner(owner ? subject : otherOwner);
    space.addMembership(new SpaceMembership(subject, membershipRole, ORGANIZATION));
    if (!owner) {
      space.addMembership(new SpaceMembership(otherOwner, SpaceRole.ADMIN, ORGANIZATION));
    }

    assertThat(SpaceAccessPolicy.hasAtLeast(space, subject, minRole)).isEqualTo(expected);
  }

  @Test
  void hasAtLeastIsFalseForANonMemberNonOwnerRegardlessOfTheRequiredRole() {
    UUID owner = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    Space space = spaceWithOwner(owner);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, ORGANIZATION));

    for (SpaceRole minRole : SpaceRole.values()) {
      assertThat(SpaceAccessPolicy.hasAtLeast(space, stranger, minRole)).isFalse();
    }
  }

  private static Stream<SpaceRole> allRoles() {
    return Stream.of(SpaceRole.values());
  }

  private static Stream<SpaceRole> nonOwnerRoles() {
    return Stream.of(SpaceRole.values());
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
