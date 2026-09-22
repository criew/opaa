package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.GroupMemberDisclosureResponse;
import io.opaa.permission.DisclosedGroupMember;
import io.opaa.permission.GroupMemberDisclosure;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the field-by-field behaviour of {@link GroupMemberDisclosureResponseMapper}: the integration
 * tests assert against the domain record, so nothing else proves the response carries every value
 * (AGENTS.md, API-Konvention).
 */
class GroupMemberDisclosureResponseMapperTest {

  @Test
  void anUnprotectedGroupCarriesNameCountAndEveryMember() {
    UUID groupId = UUID.randomUUID();
    UUID anna = UUID.randomUUID();
    UUID bert = UUID.randomUUID();

    GroupMemberDisclosureResponse response =
        GroupMemberDisclosureResponseMapper.toResponse(
            new GroupMemberDisclosure(
                groupId,
                "Referat 50",
                false,
                false,
                41,
                List.of(
                    new DisclosedGroupMember(anna, "Anna Bauer"),
                    new DisclosedGroupMember(bert, null)),
                List.of()));

    assertThat(response.getGroupId()).isEqualTo(groupId);
    assertThat(response.getName()).isEqualTo("Referat 50");
    assertThat(response.getProtectedGroup()).isFalse();
    assertThat(response.getSmallGroup()).isFalse();
    assertThat(response.getActiveMemberCount()).isEqualTo(41);
    assertThat(response.getResponsible()).isEmpty();
    assertThat(response.getMembers()).hasSize(2);
    assertThat(response.getMembers().get(0).getUserId()).isEqualTo(anna);
    assertThat(response.getMembers().get(0).getDisplayName()).isEqualTo("Anna Bauer");
    assertThat(response.getMembers().get(1).getUserId()).isEqualTo(bert);
    assertThat(response.getMembers().get(1).getDisplayName()).isNull();
  }

  /** Limit (e): below the minimum group size neither the names nor the figure are handed out. */
  @Test
  void aSmallGroupCarriesNeitherNamesNorFigure() {
    UUID groupId = UUID.randomUUID();

    GroupMemberDisclosureResponse response =
        GroupMemberDisclosureResponseMapper.toResponse(
            new GroupMemberDisclosure(
                groupId, "Kleine Runde", false, true, null, List.of(), List.of()));

    assertThat(response.getSmallGroup()).isTrue();
    assertThat(response.getName()).isEqualTo("Kleine Runde");
    assertThat(response.getActiveMemberCount()).isNull();
    assertThat(response.getMembers()).isEmpty();
  }

  /** ADR-0036, Entscheidung 9: no name, no size, no members - the people to ask instead. */
  @Test
  void aProtectedGroupCarriesOnlyThePeopleToAsk() {
    UUID groupId = UUID.randomUUID();

    GroupMemberDisclosureResponse response =
        GroupMemberDisclosureResponseMapper.toResponse(
            new GroupMemberDisclosure(
                groupId, null, true, false, null, List.of(), List.of("Andrea Vogt")));

    assertThat(response.getGroupId()).isEqualTo(groupId);
    assertThat(response.getName()).isNull();
    assertThat(response.getProtectedGroup()).isTrue();
    assertThat(response.getSmallGroup()).isFalse();
    assertThat(response.getActiveMemberCount()).isNull();
    assertThat(response.getMembers()).isEmpty();
    assertThat(response.getResponsible()).containsExactly("Andrea Vogt");
  }
}
