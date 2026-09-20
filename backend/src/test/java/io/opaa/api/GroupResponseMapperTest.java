package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.GroupListResponse;
import io.opaa.api.dto.GroupMemberResponse;
import io.opaa.api.dto.GroupResponse;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.GroupOrigin;
import io.opaa.group.Group;
import io.opaa.group.GroupDetail;
import io.opaa.group.GroupMemberView;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupOverview;
import io.opaa.group.GroupProviderView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit tests (no Spring context) against directly constructed entities - pins the mapper's
 * field-by-field behaviour, since {@code GroupServiceIntegrationTest} now asserts against {@link
 * Group}/{@link GroupDetail}/{@link GroupMemberView} directly rather than against the response
 * shape.
 */
class GroupResponseMapperTest {

  @Test
  void toListResponseCopiesEveryFieldFromTheEntity() {
    UUID providerId = UUID.randomUUID();
    Group group =
        new Group(
            UUID.randomUUID(),
            GroupKind.ORG_UNIT,
            "Referat 5",
            "Beschreibung",
            providerId,
            "ext-1",
            "/Haus/Abteilung 5/Referat 5",
            UUID.randomUUID());

    GroupListResponse response =
        GroupResponseMapper.toListResponse(
            new GroupOverview(
                group, new GroupProviderView(providerId, "Verzeichnis Haus A", true, false)));

    assertThat(response.getId()).isEqualTo(group.getId());
    assertThat(response.getName()).isEqualTo("Referat 5");
    assertThat(response.getDescription()).isEqualTo("Beschreibung");
    assertThat(response.getKind()).isEqualTo(GroupKind.ORG_UNIT);
    assertThat(response.getExternalId()).isEqualTo("ext-1");
    assertThat(response.getSourcePath()).isEqualTo("/Haus/Abteilung 5/Referat 5");
    assertThat(response.getOrigin()).isEqualTo(GroupOrigin.PROVIDER);
    assertThat(response.getProvider().getId()).isEqualTo(providerId);
    assertThat(response.getProvider().getDisplayName()).isEqualTo("Verzeichnis Haus A");
    assertThat(response.getProvider().getExternal()).isTrue();
    assertThat(response.getProvider().getEnabled()).isFalse();
    assertThat(response.getParentGroupId()).isEqualTo(group.getParentGroupId());
    assertThat(response.getMemberCount()).isZero();
    assertThat(response.getCreatedAt()).isEqualTo(group.getCreatedAt());
    assertThat(response.getUpdatedAt()).isEqualTo(group.getUpdatedAt());
  }

  /** A group without a provider is INTERNAL and carries no provider block at all. */
  @Test
  void aGroupWithoutAProviderIsMappedAsInternal() {
    Group group = Group.internal(UUID.randomUUID(), "Projektteam", null, null);

    GroupListResponse response = GroupResponseMapper.toListResponse(new GroupOverview(group, null));

    assertThat(response.getOrigin()).isEqualTo(GroupOrigin.INTERNAL);
    assertThat(response.getProvider()).isNull();
    assertThat(response.getSourcePath()).isNull();
  }

  @Test
  void toListResponseReflectsTheMemberCountForAGroupWithMembers() {
    // Regression guard: the mapper reads group.getMemberships().size() directly, so a group whose
    // memberships were never initialized (LAZY, no fetch join) would throw
    // LazyInitializationException here instead of returning a wrong count - this case exercises
    // the non-empty path, which an always-empty-collection test cannot.
    Group group = Group.internal(UUID.randomUUID(), "Team", null, null);
    group.addMembership(new GroupMembership(UUID.randomUUID(), group.getOrganizationId()));
    group.addMembership(new GroupMembership(UUID.randomUUID(), group.getOrganizationId()));

    GroupListResponse response = GroupResponseMapper.toListResponse(new GroupOverview(group, null));

    assertThat(response.getMemberCount()).isEqualTo(2);
  }

  @Test
  void toListResponsesMapsEveryGroupInOrder() {
    Group first = Group.internal(UUID.randomUUID(), "A", null, null);
    Group second = Group.internal(UUID.randomUUID(), "B", null, null);

    List<GroupListResponse> responses =
        GroupResponseMapper.toListResponses(
            List.of(new GroupOverview(first, null), new GroupOverview(second, null)));

    assertThat(responses).extracting(GroupListResponse::getName).containsExactly("A", "B");
  }

  @Test
  void toResponseCarriesTheMemberListAndItsSize() {
    Group group = Group.internal(UUID.randomUUID(), "Team", "Desc", null);
    GroupMembership membership = new GroupMembership(UUID.randomUUID(), group.getOrganizationId());
    GroupMemberView view = new GroupMemberView(membership, "Ada Lovelace");
    GroupDetail detail = new GroupDetail(group, List.of(view), null);

    GroupResponse response = GroupResponseMapper.toResponse(detail);

    assertThat(response.getId()).isEqualTo(group.getId());
    assertThat(response.getName()).isEqualTo("Team");
    assertThat(response.getDescription()).isEqualTo("Desc");
    assertThat(response.getMemberCount()).isEqualTo(1);
    assertThat(response.getMembers()).hasSize(1);
    assertThat(response.getMembers().get(0).getUserId()).isEqualTo(membership.getUserId());
    assertThat(response.getMembers().get(0).getDisplayName()).isEqualTo("Ada Lovelace");
  }

  @Test
  void toResponseReturnsAnEmptyMemberListInsteadOfNullForAGroupWithoutMembers() {
    Group group = Group.internal(UUID.randomUUID(), "Team", null, null);
    GroupDetail detail = new GroupDetail(group, List.of(), null);

    GroupResponse response = GroupResponseMapper.toResponse(detail);

    assertThat(response.getMembers()).isEmpty();
    assertThat(response.getMemberCount()).isZero();
  }

  @Test
  void toMemberResponseCarriesTheResolvedDisplayName() {
    UUID organizationId = UUID.randomUUID();
    GroupMembership membership = new GroupMembership(UUID.randomUUID(), organizationId);
    GroupMemberView view = new GroupMemberView(membership, "Ada Lovelace");

    GroupMemberResponse response = GroupResponseMapper.toMemberResponse(view);

    assertThat(response.getUserId()).isEqualTo(membership.getUserId());
    assertThat(response.getDisplayName()).isEqualTo("Ada Lovelace");
    assertThat(response.getCreatedAt()).isEqualTo(membership.getCreatedAt());
  }

  @Test
  void toMemberResponseAllowsANullDisplayName() {
    GroupMembership membership = new GroupMembership(UUID.randomUUID(), UUID.randomUUID());
    GroupMemberView view = new GroupMemberView(membership, null);

    assertThat(GroupResponseMapper.toMemberResponse(view).getDisplayName()).isNull();
  }

  @Test
  void toMemberResponsesMapsEveryViewInOrder() {
    UUID organizationId = UUID.randomUUID();
    GroupMembership first = new GroupMembership(UUID.randomUUID(), organizationId);
    GroupMembership second = new GroupMembership(UUID.randomUUID(), organizationId);
    List<GroupMemberView> views =
        List.of(new GroupMemberView(first, "First"), new GroupMemberView(second, "Second"));

    List<GroupMemberResponse> responses = GroupResponseMapper.toMemberResponses(views);

    assertThat(responses)
        .extracting(GroupMemberResponse::getDisplayName)
        .containsExactly("First", "Second");
  }

  @Test
  void toMemberResponsesReturnsAnEmptyListForNoViewsInsteadOfNull() {
    assertThat(GroupResponseMapper.toMemberResponses(List.of())).isEmpty();
  }
}
