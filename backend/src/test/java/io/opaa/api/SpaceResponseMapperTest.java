package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.SpaceListResponse;
import io.opaa.api.dto.SpaceMemberResponse;
import io.opaa.api.dto.SpaceResponse;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.api.types.SuccessionAddressee;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.permission.GroupSizeSignal;
import io.opaa.permission.SuccessionFinding;
import io.opaa.space.Space;
import io.opaa.space.SpaceDetail;
import io.opaa.space.SpaceMemberView;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceOverview;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit tests (no Spring context) against directly constructed entities - #869 review: the
 * service tests now recompute roleCounts/userRole from the entity themselves, so they exercise that
 * recomputation, not {@link SpaceResponseMapper}. These tests are what actually pin the mapper's
 * field-by-field behaviour.
 */
class SpaceResponseMapperTest {

  @Test
  void toResponseCopiesFieldsAndComputesRoleCountsForEveryRole() {
    UUID owner = UUID.randomUUID();
    UUID organization = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    Space space = new Space("Team", "Docs", false, SpaceVisibility.PRIVATE, owner, organization);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, organization));
    space.addMembership(SpaceMembership.ofUser(member, SpaceRole.MEMBER, organization));

    SpaceResponse response =
        SpaceResponseMapper.toResponse(new SpaceDetail(space, SpaceRole.ADMIN, false));

    assertThat(response.getId()).isEqualTo(space.getId());
    assertThat(response.getName()).isEqualTo("Team");
    assertThat(response.getDescription()).isEqualTo("Docs");
    assertThat(response.getIsDefault()).isFalse();
    assertThat(response.getArchived()).isFalse();
    assertThat(response.getVisibility()).isEqualTo(SpaceVisibility.PRIVATE);
    assertThat(response.getOwnerId()).isEqualTo(owner);
    assertThat(response.getMemberCount()).isEqualTo(2);
    assertThat(response.getCreatedAt()).isEqualTo(space.getCreatedAt());
    assertThat(response.getUpdatedAt()).isEqualTo(space.getUpdatedAt());
    // Every SpaceRole is present with a count, including CURATOR which nobody here holds - not
    // just the roles actually assigned.
    assertThat(response.getRoleCounts())
        .containsEntry("ADMIN", 1L)
        .containsEntry("MEMBER", 1L)
        .containsEntry("CURATOR", 0L);
    assertThat(response.getUserRole()).isEqualTo(SpaceRole.ADMIN);
  }

  @Test
  void toResponseReturnsNullUserRoleForACallerWhoIsNotAMember() {
    // SpaceService#getSpace lets a system admin read a space without being a member - the
    // response must not fabricate a role they do not actually hold.
    UUID owner = UUID.randomUUID();
    UUID organization = UUID.randomUUID();
    UUID systemAdmin = UUID.randomUUID();
    Space space = new Space("Team", null, false, SpaceVisibility.PRIVATE, owner, organization);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, organization));

    SpaceResponse response = SpaceResponseMapper.toResponse(new SpaceDetail(space, null, false));

    assertThat(response.getUserRole()).isNull();
  }

  /**
   * #891 review: {@code userRole} is the caller's {@code SpaceAccessPolicy#effectiveRole}, not
   * their raw {@link SpaceMembership} row - an owner whose own membership is still MEMBER (unraised
   * by {@code SpaceService#transferOwnership}) gets {@code userRole=ADMIN} here, which is what lets
   * the frontend's {@code role==='ADMIN'} gate show them the manager UI.
   */
  @Test
  void toResponseReportsAdminUserRoleForAnOwnerWithABelowAdminMembership() {
    UUID owner = UUID.randomUUID();
    UUID organization = UUID.randomUUID();
    Space space = new Space("Team", null, false, SpaceVisibility.PRIVATE, owner, organization);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.MEMBER, organization));

    SpaceResponse response =
        SpaceResponseMapper.toResponse(new SpaceDetail(space, SpaceRole.ADMIN, false));

    assertThat(response.getUserRole()).isEqualTo(SpaceRole.ADMIN);
    // roleCounts keeps showing the raw membership role, including the owner's own row - only
    // userRole is adjusted for ownership.
    assertThat(response.getRoleCounts()).containsEntry("MEMBER", 1L).containsEntry("ADMIN", 0L);
  }

  @Test
  void toListResponseReportsAdminUserRoleForAnOwnerWithABelowAdminMembership() {
    UUID owner = UUID.randomUUID();
    UUID organization = UUID.randomUUID();
    Space space = new Space("Team", null, false, SpaceVisibility.PRIVATE, owner, organization);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.CURATOR, organization));
    SpaceOverview overview = new SpaceOverview(space, 0, 0, SpaceRole.ADMIN, false, null);

    SpaceListResponse response = SpaceResponseMapper.toListResponse(overview);

    assertThat(response.getUserRole()).isEqualTo(SpaceRole.ADMIN);
  }

  @Test
  void toListResponseCarriesOverviewFiguresAlongsideSpaceFields() {
    UUID owner = UUID.randomUUID();
    UUID organization = UUID.randomUUID();
    Space space = new Space("Team", "Docs", false, SpaceVisibility.OPEN, owner, organization);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, organization));
    SpaceOverview overview = new SpaceOverview(space, 3, 5, SpaceRole.ADMIN, false, null);

    SpaceListResponse response = SpaceResponseMapper.toListResponse(overview);

    assertThat(response.getId()).isEqualTo(space.getId());
    assertThat(response.getName()).isEqualTo("Team");
    assertThat(response.getDescription()).isEqualTo("Docs");
    assertThat(response.getVisibility()).isEqualTo(SpaceVisibility.OPEN);
    assertThat(response.getMemberCount()).isEqualTo(1);
    assertThat(response.getLibraryCount()).isEqualTo(3);
    assertThat(response.getChatCount()).isEqualTo(5);
    assertThat(response.getUserRole()).isEqualTo(SpaceRole.ADMIN);
  }

  @Test
  void toListResponsesMapsEveryOverviewInOrder() {
    UUID owner = UUID.randomUUID();
    UUID organization = UUID.randomUUID();
    Space first = new Space("A", null, false, SpaceVisibility.PRIVATE, owner, organization);
    Space second = new Space("B", null, false, SpaceVisibility.PRIVATE, owner, organization);
    List<SpaceOverview> overviews =
        List.of(
            new SpaceOverview(first, 0, 0, SpaceRole.ADMIN, false, null),
            new SpaceOverview(second, 1, 2, SpaceRole.ADMIN, false, null));

    List<SpaceListResponse> responses = SpaceResponseMapper.toListResponses(overviews);

    assertThat(responses).extracting(SpaceListResponse::getName).containsExactly("A", "B");
  }

  @Test
  void toMemberResponseCarriesTheResolvedDisplayName() {
    UUID userId = UUID.randomUUID();
    UUID organization = UUID.randomUUID();
    SpaceMembership membership = SpaceMembership.ofUser(userId, SpaceRole.CURATOR, organization);
    SpaceMemberView view = SpaceMemberView.ofUser(membership, "Ada Lovelace");

    SpaceMemberResponse response = SpaceResponseMapper.toMemberResponse(view);

    assertThat(response.getId()).isEqualTo(membership.getId());
    assertThat(response.getSubjectType()).isEqualTo(PermissionSubjectType.USER);
    assertThat(response.getSubjectId()).isEqualTo(userId);
    assertThat(response.getRole()).isEqualTo(SpaceRole.CURATOR);
    assertThat(response.getMemberCountAtGrant()).isNull();
    assertThat(response.getMemberCountNow()).isNull();
    assertThat(response.getSmallGroup()).as("a person is never a small group").isNull();
    assertThat(response.getEmptyGroup()).isNull();
    assertThat(response.getDisplayName()).isEqualTo("Ada Lovelace");
    assertThat(response.getCreatedAt()).isEqualTo(membership.getCreatedAt());
  }

  @Test
  void toMemberResponseAllowsANullDisplayName() {
    SpaceMembership membership =
        SpaceMembership.ofUser(UUID.randomUUID(), SpaceRole.MEMBER, UUID.randomUUID());
    SpaceMemberView view = SpaceMemberView.ofUser(membership, null);

    assertThat(SpaceResponseMapper.toMemberResponse(view).getDisplayName()).isNull();
  }

  @Test
  void toMemberResponsesMapsEveryViewInOrder() {
    UUID organization = UUID.randomUUID();
    SpaceMembership first =
        SpaceMembership.ofUser(UUID.randomUUID(), SpaceRole.MEMBER, organization);
    SpaceMembership second =
        SpaceMembership.ofUser(UUID.randomUUID(), SpaceRole.ADMIN, organization);
    List<SpaceMemberView> views =
        List.of(SpaceMemberView.ofUser(first, "First"), SpaceMemberView.ofUser(second, "Second"));

    List<SpaceMemberResponse> responses = SpaceResponseMapper.toMemberResponses(views);

    assertThat(responses)
        .extracting(SpaceMemberResponse::getDisplayName)
        .containsExactly("First", "Second");
  }

  /** #1815: a group row names the group and carries the growth signal of ADR-0036/9. */
  @Test
  void toMemberResponseCarriesTheGroupSubjectAndItsSizeSignal() {
    UUID groupId = UUID.randomUUID();
    UUID organization = UUID.randomUUID();
    SpaceMembership membership =
        SpaceMembership.ofGroup(groupId, SpaceRole.CURATOR, 23, organization);
    SpaceMemberView view =
        new SpaceMemberView(membership, "Referat 50", GroupSizeSignal.of(23, 41, 5), false);

    SpaceMemberResponse response = SpaceResponseMapper.toMemberResponse(view);

    assertThat(response.getSubjectType()).isEqualTo(PermissionSubjectType.GROUP);
    assertThat(response.getSubjectId()).isEqualTo(groupId);
    assertThat(response.getDisplayName()).isEqualTo("Referat 50");
    assertThat(response.getMemberCountAtGrant()).isEqualTo(23);
    assertThat(response.getMemberCountNow()).isEqualTo(41);
    assertThat(response.getSmallGroup()).isFalse();
    assertThat(response.getEmptyGroup()).isFalse();
  }

  /**
   * ADR-0036/9: below the enforced minimum group size both figures are withheld - publishing one of
   * them beside the difference would reconstruct the other.
   */
  @Test
  void toMemberResponseWithholdsBothFiguresForASmallGroup() {
    SpaceMembership membership =
        SpaceMembership.ofGroup(UUID.randomUUID(), SpaceRole.MEMBER, 23, UUID.randomUUID());
    SpaceMemberView view =
        new SpaceMemberView(membership, "Referat 50", GroupSizeSignal.of(23, 4, 5), false);

    SpaceMemberResponse response = SpaceResponseMapper.toMemberResponse(view);

    assertThat(response.getSmallGroup()).isTrue();
    assertThat(response.getMemberCountAtGrant()).isNull();
    assertThat(response.getMemberCountNow()).isNull();
  }

  @Test
  void toMemberResponseMarksAnEffectiveButEmptyGroup() {
    SpaceMembership membership =
        SpaceMembership.ofGroup(UUID.randomUUID(), SpaceRole.MEMBER, 0, UUID.randomUUID());
    SpaceMemberView view =
        new SpaceMemberView(membership, "Neu", GroupSizeSignal.of(0, 0, 5), false);

    SpaceMemberResponse response = SpaceResponseMapper.toMemberResponse(view);

    assertThat(response.getEmptyGroup()).isTrue();
    assertThat(response.getSmallGroup()).isTrue();
  }

  /**
   * ADR-0036/9 (#1820): a protected group is a nameless row in another person's list, and it
   * carries no figure at all - not even "not small, not empty", which would already be a statement
   * about its size. The row itself stays, so an ADMIN can end a membership they cannot see.
   */
  @Test
  void toMemberResponseLeavesAProtectedGroupNamelessAndWithoutASignal() {
    SpaceMembership membership =
        SpaceMembership.ofGroup(UUID.randomUUID(), SpaceRole.MEMBER, 12, UUID.randomUUID());
    SpaceMemberView view = new SpaceMemberView(membership, null, GroupSizeSignal.NONE, true);

    SpaceMemberResponse response = SpaceResponseMapper.toMemberResponse(view);

    assertThat(response.getDisplayName()).isNull();
    assertThat(response.getProtectedGroup()).isTrue();
    assertThat(response.getMemberCountAtGrant()).isNull();
    assertThat(response.getMemberCountNow()).isNull();
    assertThat(response.getSmallGroup()).isNull();
    assertThat(response.getEmptyGroup()).isNull();
    assertThat(response.getId()).isEqualTo(membership.getId());
    assertThat(response.getRole()).isEqualTo(SpaceRole.MEMBER);
  }

  /** #1815: the derived state "Nachfolge offen" reaches both response shapes. */
  @Test
  void bothResponsesCarryTheDerivedSuccessionState() {
    UUID owner = UUID.randomUUID();
    UUID organization = UUID.randomUUID();
    Space space = new Space("Team", null, false, SpaceVisibility.PRIVATE, owner, organization);
    space.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, organization));

    assertThat(
            SpaceResponseMapper.toResponse(new SpaceDetail(space, SpaceRole.ADMIN, true))
                .getSuccessionOpen())
        .isTrue();
    SuccessionFinding finding =
        SuccessionFinding.of(
            SuccessionObjectType.SPACE, space.getId(), "Team", SuccessionAddressee.SPACE_ADMINS);
    SpaceListResponse listResponse =
        SpaceResponseMapper.toListResponse(
            new SpaceOverview(space, 0, 0, SpaceRole.ADMIN, true, finding));
    assertThat(listResponse.getSuccessionOpen()).isTrue();
    // ADR-0036, Entscheidung 6: Die Übersicht trägt Zustand *und* Adressat, nicht nur das
    // Kennzeichen.
    assertThat(listResponse.getSuccession()).isNotNull();
    assertThat(listResponse.getSuccession().getAddressee())
        .isEqualTo(SuccessionAddressee.SPACE_ADMINS);
    assertThat(listResponse.getSuccession().getAddresseeLabel())
        .isEqualTo("die übrigen handlungsfähigen ADMIN-Mitglieder des Space");
  }

  /** Ein Space in Ordnung trägt kein Kennzeichen - weder das Flag noch den Adressaten. */
  @Test
  void theOverviewOfASpaceInOrderCarriesNoSuccessionState() {
    UUID owner = UUID.randomUUID();
    UUID organization = UUID.randomUUID();
    Space space = new Space("Team", null, false, SpaceVisibility.PRIVATE, owner, organization);

    SpaceListResponse response =
        SpaceResponseMapper.toListResponse(
            new SpaceOverview(space, 0, 0, SpaceRole.ADMIN, false, null));

    assertThat(response.getSuccessionOpen()).isFalse();
    assertThat(response.getSuccession()).isNull();
  }

  @Test
  void toMemberResponsesReturnsAnEmptyListForNoViewsInsteadOfNull() {
    List<SpaceMemberResponse> responses = SpaceResponseMapper.toMemberResponses(List.of());

    assertThat(responses).isEmpty();
  }
}
