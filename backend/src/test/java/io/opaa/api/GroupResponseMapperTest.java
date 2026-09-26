package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.GroupListResponse;
import io.opaa.api.dto.GroupMemberResponse;
import io.opaa.api.dto.GroupResponse;
import io.opaa.api.dto.SelectableGroupResponse;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.GroupMechanism;
import io.opaa.api.types.GroupOrigin;
import io.opaa.group.Group;
import io.opaa.group.GroupDetail;
import io.opaa.group.GroupMemberView;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupOverview;
import io.opaa.group.GroupProviderView;
import io.opaa.group.GroupSteward;
import io.opaa.group.GroupStewardView;
import io.opaa.group.SelectableGroup;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure JUnit tests (no Spring context) against directly constructed entities - pins the mapper's
 * field-by-field behaviour, since {@code GroupServiceIntegrationTest} now asserts against {@link
 * Group}/{@link GroupDetail}/{@link GroupMemberView} directly rather than against the response
 * shape.
 */
class GroupResponseMapperTest {

  /**
   * Timestamps are written by {@code @PrePersist}, so an entity built here carries {@code null}
   * until it is set: an assertion against the entity's own getter would compare {@code null} with
   * {@code null} and pass whatever the mapper did with the field.
   */
  private static <T> T withTimestamp(T entity, String field, Instant value) {
    ReflectionTestUtils.setField(entity, field, value);
    return entity;
  }

  private static final java.time.Instant LAST_SYNC_AT =
      java.time.Instant.parse("2026-09-20T04:00:00Z");

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
    withTimestamp(group, "createdAt", Instant.parse("2026-03-01T10:00:00Z"));
    withTimestamp(group, "updatedAt", Instant.parse("2026-03-02T11:30:00Z"));

    GroupListResponse response =
        GroupResponseMapper.toListResponse(
            new GroupOverview(
                group,
                List.of(),
                new GroupProviderView(
                    providerId,
                    "Verzeichnis Haus A",
                    true,
                    false,
                    GroupMechanism.DIRECTORY,
                    360,
                    LAST_SYNC_AT)));

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
    // The delay of the directory run is visible to every member, not only to the management
    // (ADR-0036, Entscheidung 3).
    assertThat(response.getProvider().getGroupMechanism()).isEqualTo(GroupMechanism.DIRECTORY);
    assertThat(response.getProvider().getDirectorySyncIntervalMinutes()).isEqualTo(360);
    assertThat(response.getProvider().getLastDirectorySyncAt()).isEqualTo(LAST_SYNC_AT);
    assertThat(response.getParentGroupId()).isEqualTo(group.getParentGroupId());
    assertThat(response.getMemberCount()).isZero();
    assertThat(response.getCreatedAt()).isEqualTo(group.getCreatedAt());
    assertThat(response.getUpdatedAt()).isEqualTo(group.getUpdatedAt());
  }

  /** #1820: what the Subjekt-Auswahl shows - origin, source path, size, and every reason. */
  @Test
  void toSelectableResponseCopiesEveryFieldOfTheSelection() {
    UUID providerId = UUID.randomUUID();
    Group group =
        new Group(
            UUID.randomUUID(),
            GroupKind.ORG_UNIT,
            "Referat 50",
            null,
            providerId,
            "ext-1",
            "/Haus/Abteilung 5/Referat 50",
            null);
    GroupProviderView provider =
        new GroupProviderView(
            providerId, "Verzeichnis Partner", true, true, GroupMechanism.DIRECTORY, 360, null);

    SelectableGroupResponse response =
        GroupResponseMapper.toSelectableResponse(
            new SelectableGroup(
                group,
                "Referat 50",
                List.of(),
                provider,
                41,
                false,
                false,
                true,
                false,
                false,
                false));

    assertThat(response.getId()).isEqualTo(group.getId());
    assertThat(response.getName()).isEqualTo("Referat 50");
    assertThat(response.getOrigin()).isEqualTo(GroupOrigin.PROVIDER);
    assertThat(response.getProvider().getDisplayName()).isEqualTo("Verzeichnis Partner");
    assertThat(response.getProvider().getExternal()).isTrue();
    assertThat(response.getSourcePath()).isEqualTo("/Haus/Abteilung 5/Referat 50");
    assertThat(response.getActiveMemberCount()).isEqualTo(41);
    assertThat(response.getSmallGroup()).isFalse();
    assertThat(response.getEmptyGroup()).isFalse();
    assertThat(response.getProtectedGroup()).isFalse();
    assertThat(response.getSelectable()).isTrue();
    assertThat(response.getDissolved()).isFalse();
    assertThat(response.getProviderDisabled()).isFalse();
    assertThat(response.getUnmaintained()).isFalse();
  }

  /**
   * The suppression happens in the service; the mapper must not invent a figure where the domain
   * record withheld one.
   */
  @Test
  void toSelectableResponsePassesAWithheldFigureOnAsWithheld() {
    Group group = Group.internal(UUID.randomUUID(), "Kleine Runde", null, null);

    SelectableGroupResponse response =
        GroupResponseMapper.toSelectableResponse(
            new SelectableGroup(
                group,
                "Kleine Runde",
                List.of(),
                null,
                null,
                true,
                false,
                true,
                false,
                false,
                false));

    assertThat(response.getActiveMemberCount()).isNull();
    assertThat(response.getSmallGroup()).isTrue();
    assertThat(response.getOrigin()).isEqualTo(GroupOrigin.INTERNAL);
    assertThat(response.getProvider()).isNull();
  }

  @Test
  void toSelectableResponseCarriesTheReasonAGroupIsNotChoosable() {
    Group group = Group.internal(UUID.randomUUID(), "Aufgeloeste Runde", null, null);

    SelectableGroupResponse response =
        GroupResponseMapper.toSelectableResponse(
            new SelectableGroup(
                group,
                "Aufgeloeste Runde",
                List.of(),
                null,
                null,
                false,
                true,
                false,
                true,
                false,
                false));

    assertThat(response.getSelectable()).isFalse();
    assertThat(response.getDissolved()).isTrue();
    assertThat(response.getEmptyGroup()).isTrue();
  }

  /** ADR-0036/9 (#1820): Wo der Dienst den Namen zurueckhaelt, erfindet der Mapper keinen. */
  @Test
  void toSelectableResponseLeavesAWithheldNameWithheld() {
    Group group = Group.internal(UUID.randomUUID(), "Personalrat", null, null);
    group.markProtected(true);

    SelectableGroupResponse response =
        GroupResponseMapper.toSelectableResponse(
            new SelectableGroup(
                group, null, List.of(), null, null, false, false, true, false, false, false));

    assertThat(response.getName()).isNull();
    assertThat(response.getProtectedGroup()).isTrue();
    assertThat(response.getActiveMemberCount()).isNull();
  }

  /** A group without a provider is INTERNAL and carries no provider block at all. */
  @Test
  void aGroupWithoutAProviderIsMappedAsInternal() {
    Group group = Group.internal(UUID.randomUUID(), "Projektteam", null, null);

    GroupListResponse response =
        GroupResponseMapper.toListResponse(new GroupOverview(group, List.of(), null));

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

    GroupListResponse response =
        GroupResponseMapper.toListResponse(new GroupOverview(group, List.of(), null));

    assertThat(response.getMemberCount()).isEqualTo(2);
  }

  @Test
  void toListResponsesMapsEveryGroupInOrder() {
    Group first = Group.internal(UUID.randomUUID(), "A", null, null);
    Group second = Group.internal(UUID.randomUUID(), "B", null, null);

    List<GroupListResponse> responses =
        GroupResponseMapper.toListResponses(
            List.of(
                new GroupOverview(first, List.of(), null),
                new GroupOverview(second, List.of(), null)));

    assertThat(responses).extracting(GroupListResponse::getName).containsExactly("A", "B");
  }

  @Test
  void toResponseCarriesTheMemberListAndItsSize() {
    Group group = Group.internal(UUID.randomUUID(), "Team", "Desc", null);
    GroupMembership membership = new GroupMembership(UUID.randomUUID(), group.getOrganizationId());
    GroupMemberView view = new GroupMemberView(membership, "Ada Lovelace");
    GroupDetail detail = new GroupDetail(group, List.of(view), List.of(), null);

    GroupResponse response = GroupResponseMapper.toResponse(detail);

    assertThat(response.getId()).isEqualTo(group.getId());
    assertThat(response.getName()).isEqualTo("Team");
    assertThat(response.getDescription()).isEqualTo("Desc");
    assertThat(response.getMemberCount()).isEqualTo(1);
    assertThat(response.getMembers()).hasSize(1);
    assertThat(response.getMembers().get(0).getUserId()).isEqualTo(membership.getUserId());
    assertThat(response.getMembers().get(0).getDisplayName()).isEqualTo("Ada Lovelace");
  }

  /** A withheld list stays distinguishable from an empty one, and the count still stands. */
  @Test
  void toResponseKeepsAWithheldMemberListNullAndCountsTheMemberships() {
    Group group = Group.internal(UUID.randomUUID(), "Team", null, null);
    group.addMembership(new GroupMembership(UUID.randomUUID(), group.getOrganizationId()));
    GroupDetail detail = new GroupDetail(group, null, List.of(), null);

    GroupResponse response = GroupResponseMapper.toResponse(detail);

    assertThat(response.getMembers()).isNull();
    assertThat(response.getMemberCount()).isEqualTo(1);
  }

  /** The detail path has to carry the origin as fully as the list path does (AGENTS.md). */
  @Test
  void toResponseCopiesTheOriginOfAProviderGroup() {
    UUID providerId = UUID.randomUUID();
    Group group =
        new Group(
            UUID.randomUUID(),
            GroupKind.IDENTITY_PROVIDER,
            "Referat 50",
            null,
            providerId,
            "Referat 50",
            "/Haus/Abteilung 5/Referat 50",
            null);
    GroupDetail detail =
        new GroupDetail(
            group,
            List.of(),
            List.of(),
            new GroupProviderView(
                providerId, "Verzeichnis Haus A", true, false, GroupMechanism.TOKEN, null, null));

    GroupResponse response = GroupResponseMapper.toResponse(detail);

    assertThat(response.getOrigin()).isEqualTo(GroupOrigin.PROVIDER);
    assertThat(response.getExternalId()).isEqualTo("Referat 50");
    assertThat(response.getSourcePath()).isEqualTo("/Haus/Abteilung 5/Referat 50");
    assertThat(response.getProvider().getId()).isEqualTo(providerId);
    assertThat(response.getProvider().getDisplayName()).isEqualTo("Verzeichnis Haus A");
    assertThat(response.getProvider().getExternal()).isTrue();
    assertThat(response.getProvider().getEnabled()).isFalse();
    assertThat(response.getProvider().getGroupMechanism()).isEqualTo(GroupMechanism.TOKEN);
    assertThat(response.getProvider().getDirectorySyncIntervalMinutes()).isNull();
    assertThat(response.getProvider().getLastDirectorySyncAt()).isNull();
  }

  @Test
  void toResponseMapsAGroupWithoutAProviderAsInternal() {
    Group group = Group.internal(UUID.randomUUID(), "Projektteam", null, null);

    GroupResponse response =
        GroupResponseMapper.toResponse(new GroupDetail(group, List.of(), List.of(), null));

    assertThat(response.getOrigin()).isEqualTo(GroupOrigin.INTERNAL);
    assertThat(response.getProvider()).isNull();
    assertThat(response.getSourcePath()).isNull();
  }

  @Test
  void toResponseReturnsAnEmptyMemberListInsteadOfNullForAGroupWithoutMembers() {
    Group group = Group.internal(UUID.randomUUID(), "Team", null, null);
    GroupDetail detail = new GroupDetail(group, List.of(), List.of(), null);

    GroupResponse response = GroupResponseMapper.toResponse(detail);

    assertThat(response.getMembers()).isEmpty();
    assertThat(response.getMemberCount()).isZero();
  }

  /**
   * The three fields of #1814 on the list path: the release as the derived "may somebody else name
   * this group", the protection mark, and the stewards a member sees by name.
   */
  @Test
  void toListResponseCarriesReleaseProtectionAndStewards() {
    Group group = Group.internal(UUID.randomUUID(), "Projektteam", null, null);
    group.release(true);
    group.markProtected(true);
    GroupSteward steward =
        withTimestamp(
            new GroupSteward(group.getId(), UUID.randomUUID(), group.getOrganizationId(), null),
            "createdAt",
            Instant.parse("2026-03-01T10:00:00Z"));

    GroupListResponse response =
        GroupResponseMapper.toListResponse(
            new GroupOverview(group, List.of(new GroupStewardView(steward, "Ada Lovelace")), null));

    assertThat(response.getReleasedForUse()).isTrue();
    assertThat(response.getProtectedGroup()).isTrue();
    assertThat(response.getStewards()).hasSize(1);
    assertThat(response.getStewards().get(0).getUserId()).isEqualTo(steward.getUserId());
    assertThat(response.getStewards().get(0).getDisplayName()).isEqualTo("Ada Lovelace");
    assertThat(response.getStewards().get(0).getAppointedAt()).isEqualTo(steward.getCreatedAt());
  }

  @Test
  void toResponseCarriesReleaseProtectionAndStewards() {
    Group group = Group.internal(UUID.randomUUID(), "Projektteam", null, null);
    GroupSteward steward =
        new GroupSteward(group.getId(), UUID.randomUUID(), group.getOrganizationId(), null);

    GroupResponse response =
        GroupResponseMapper.toResponse(
            new GroupDetail(
                group, List.of(), List.of(new GroupStewardView(steward, "Ada Lovelace")), null));

    assertThat(response.getReleasedForUse()).as("an internal group starts unreleased").isFalse();
    assertThat(response.getProtectedGroup()).isFalse();
    assertThat(response.getStewards()).hasSize(1);
    assertThat(response.getStewards().get(0).getDisplayName()).isEqualTo("Ada Lovelace");
  }

  /**
   * A provider group needs no release: its existence is not a decision of this house, so the
   * response says "selectable" regardless of the column only an internal group's stewards set.
   */
  @Test
  void aProviderGroupIsAlwaysReportedAsReleasedForUse() {
    UUID providerId = UUID.randomUUID();
    Group group =
        new Group(
            UUID.randomUUID(),
            GroupKind.ORG_UNIT,
            "Referat 5",
            null,
            providerId,
            "ext-1",
            null,
            null);

    GroupListResponse response =
        GroupResponseMapper.toListResponse(
            new GroupOverview(
                group,
                List.of(),
                new GroupProviderView(
                    providerId, "Haus A", false, true, GroupMechanism.DIRECTORY, 360, null)));

    assertThat(response.getReleasedForUse()).isTrue();
    assertThat(response.getStewards()).isEmpty();
  }

  @Test
  void toStewardResponseAllowsANullDisplayName() {
    GroupSteward steward =
        new GroupSteward(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null);

    assertThat(
            GroupResponseMapper.toStewardResponse(new GroupStewardView(steward, null))
                .getDisplayName())
        .isNull();
  }

  @Test
  void toMemberResponseCarriesTheResolvedDisplayName() {
    UUID organizationId = UUID.randomUUID();
    GroupMembership membership =
        withTimestamp(
            new GroupMembership(UUID.randomUUID(), organizationId),
            "createdAt",
            Instant.parse("2026-03-01T10:00:00Z"));
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

  /** At a protected group the grant giver is handed the responsible people, never the members. */
  @Test
  void theSelectableResponseCarriesWhoIsResponsibleForAProtectedGroup() {
    Group group = Group.internal(UUID.randomUUID(), "Personalrat", null, null);
    group.markProtected(true);

    SelectableGroupResponse response =
        GroupResponseMapper.toSelectableResponse(
            new SelectableGroup(
                group,
                null,
                List.of("Andrea Vogt", "Bernd Sommer"),
                null,
                null,
                false,
                false,
                true,
                false,
                false,
                false));

    assertThat(response.getProtectedGroup()).isTrue();
    assertThat(response.getName()).as("resolved by its id, it keeps its namelessness").isNull();
    assertThat(response.getActiveMemberCount()).isNull();
    assertThat(response.getResponsible()).containsExactly("Andrea Vogt", "Bernd Sommer");
  }

  @Test
  void anUnprotectedGroupNamesNobodyAsResponsible() {
    Group group = Group.internal(UUID.randomUUID(), "Projektteam", null, null);

    SelectableGroupResponse response =
        GroupResponseMapper.toSelectableResponse(
            new SelectableGroup(
                group, "Projektteam", List.of(), null, 7, false, false, true, false, false, false));

    assertThat(response.getResponsible()).isEmpty();
    assertThat(response.getActiveMemberCount()).isEqualTo(7);
  }
}
