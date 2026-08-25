package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.SpaceListResponse;
import io.opaa.api.dto.SpaceMemberResponse;
import io.opaa.api.dto.SpaceResponse;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.space.Space;
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
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organization));
    space.addMembership(new SpaceMembership(member, SpaceRole.MEMBER, organization));

    SpaceResponse response = SpaceResponseMapper.toResponse(space, owner);

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
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organization));

    SpaceResponse response = SpaceResponseMapper.toResponse(space, systemAdmin);

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
    space.addMembership(new SpaceMembership(owner, SpaceRole.MEMBER, organization));

    SpaceResponse response = SpaceResponseMapper.toResponse(space, owner);

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
    space.addMembership(new SpaceMembership(owner, SpaceRole.CURATOR, organization));
    SpaceOverview overview = new SpaceOverview(space, 0, 0);

    SpaceListResponse response = SpaceResponseMapper.toListResponse(overview, owner);

    assertThat(response.getUserRole()).isEqualTo(SpaceRole.ADMIN);
  }

  @Test
  void toListResponseCarriesOverviewFiguresAlongsideSpaceFields() {
    UUID owner = UUID.randomUUID();
    UUID organization = UUID.randomUUID();
    Space space = new Space("Team", "Docs", false, SpaceVisibility.OPEN, owner, organization);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organization));
    SpaceOverview overview = new SpaceOverview(space, 3, 5);

    SpaceListResponse response = SpaceResponseMapper.toListResponse(overview, owner);

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
        List.of(new SpaceOverview(first, 0, 0), new SpaceOverview(second, 1, 2));

    List<SpaceListResponse> responses = SpaceResponseMapper.toListResponses(overviews, owner);

    assertThat(responses).extracting(SpaceListResponse::getName).containsExactly("A", "B");
  }

  @Test
  void toMemberResponseCarriesTheResolvedDisplayName() {
    UUID userId = UUID.randomUUID();
    UUID organization = UUID.randomUUID();
    SpaceMembership membership = new SpaceMembership(userId, SpaceRole.CURATOR, organization);
    SpaceMemberView view = new SpaceMemberView(membership, "Ada Lovelace");

    SpaceMemberResponse response = SpaceResponseMapper.toMemberResponse(view);

    assertThat(response.getUserId()).isEqualTo(userId);
    assertThat(response.getRole()).isEqualTo(SpaceRole.CURATOR);
    assertThat(response.getDisplayName()).isEqualTo("Ada Lovelace");
    assertThat(response.getCreatedAt()).isEqualTo(membership.getCreatedAt());
  }

  @Test
  void toMemberResponseAllowsANullDisplayName() {
    SpaceMembership membership =
        new SpaceMembership(UUID.randomUUID(), SpaceRole.MEMBER, UUID.randomUUID());
    SpaceMemberView view = new SpaceMemberView(membership, null);

    assertThat(SpaceResponseMapper.toMemberResponse(view).getDisplayName()).isNull();
  }

  @Test
  void toMemberResponsesMapsEveryViewInOrder() {
    UUID organization = UUID.randomUUID();
    SpaceMembership first = new SpaceMembership(UUID.randomUUID(), SpaceRole.MEMBER, organization);
    SpaceMembership second = new SpaceMembership(UUID.randomUUID(), SpaceRole.ADMIN, organization);
    List<SpaceMemberView> views =
        List.of(new SpaceMemberView(first, "First"), new SpaceMemberView(second, "Second"));

    List<SpaceMemberResponse> responses = SpaceResponseMapper.toMemberResponses(views);

    assertThat(responses)
        .extracting(SpaceMemberResponse::getDisplayName)
        .containsExactly("First", "Second");
  }

  @Test
  void toMemberResponsesReturnsAnEmptyListForNoViewsInsteadOfNull() {
    List<SpaceMemberResponse> responses = SpaceResponseMapper.toMemberResponses(List.of());

    assertThat(responses).isEmpty();
  }
}
