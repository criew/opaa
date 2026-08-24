package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.GroupListResponse;
import io.opaa.api.dto.GroupMemberResponse;
import io.opaa.api.dto.GroupResponse;
import io.opaa.group.Group;
import io.opaa.group.GroupDetail;
import io.opaa.group.GroupKind;
import io.opaa.group.GroupMemberView;
import io.opaa.group.GroupMembership;
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
    Group group =
        new Group(
            UUID.randomUUID(),
            GroupKind.ORG_UNIT,
            "Referat 5",
            "Beschreibung",
            "ext-1",
            UUID.randomUUID());

    GroupListResponse response = GroupResponseMapper.toListResponse(group);

    assertThat(response.getId()).isEqualTo(group.getId());
    assertThat(response.getName()).isEqualTo("Referat 5");
    assertThat(response.getDescription()).isEqualTo("Beschreibung");
    assertThat(response.getKind()).isEqualTo(GroupKind.ORG_UNIT);
    assertThat(response.getExternalId()).isEqualTo("ext-1");
    assertThat(response.getParentGroupId()).isEqualTo(group.getParentGroupId());
    assertThat(response.getMemberCount()).isZero();
    assertThat(response.getCreatedAt()).isEqualTo(group.getCreatedAt());
    assertThat(response.getUpdatedAt()).isEqualTo(group.getUpdatedAt());
  }

  @Test
  void toListResponsesMapsEveryGroupInOrder() {
    Group first = new Group(UUID.randomUUID(), GroupKind.AD_HOC, "A", null, null, null);
    Group second = new Group(UUID.randomUUID(), GroupKind.AD_HOC, "B", null, null, null);

    List<GroupListResponse> responses = GroupResponseMapper.toListResponses(List.of(first, second));

    assertThat(responses).extracting(GroupListResponse::getName).containsExactly("A", "B");
  }

  @Test
  void toResponseCarriesTheMemberListAndItsSize() {
    Group group = new Group(UUID.randomUUID(), GroupKind.AD_HOC, "Team", "Desc", null, null);
    GroupMembership membership = new GroupMembership(UUID.randomUUID(), group.getOrganizationId());
    GroupMemberView view = new GroupMemberView(membership, "Ada Lovelace");
    GroupDetail detail = new GroupDetail(group, List.of(view));

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
    Group group = new Group(UUID.randomUUID(), GroupKind.AD_HOC, "Team", null, null, null);
    GroupDetail detail = new GroupDetail(group, List.of());

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
