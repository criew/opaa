package io.opaa.succession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.api.types.SuccessionAddressee;
import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupRepository;
import io.opaa.group.GroupSteward;
import io.opaa.group.GroupStewardRepository;
import io.opaa.library.AssetGrantService;
import io.opaa.library.AssetGrantUpsert;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.PermissionSubject;
import io.opaa.space.Space;
import io.opaa.space.SpaceCreation;
import io.opaa.space.SpaceService;
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
 * The lifecycle of ownership and responsibility against the real schema (#1819, ADR-0036
 * Entscheidung 6): the derived state, the detection run behind the age, the frozen reach and the
 * three tabs of the operational list.
 */
@OpaaIntegrationTest
class SuccessionLifecycleIntegrationTest {

  @Autowired private SuccessionService successionService;
  @Autowired private SuccessionDetectionService detectionService;
  @Autowired private SuccessionCaseRepository cases;
  @Autowired private KnowledgeLibraryRepository libraries;
  @Autowired private AssetGrantService grantService;
  @Autowired private SpaceService spaceService;
  @Autowired private GroupRepository groups;
  @Autowired private GroupStewardRepository stewards;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private CurrentUser admin;

  @BeforeEach
  void createOrganization() {
    organizationId =
        organizations
            .save(new Organization(UUID.randomUUID(), "Nachfolge " + UUID.randomUUID()))
            .getId();
    admin = user(SystemRole.SYSTEM_ADMIN);
  }

  @AfterEach
  void tearDown() {
    for (String table :
        List.of(
            "succession_reviews",
            "succession_cases",
            "space_memberships",
            "space_membership_history",
            "asset_ownership_history",
            "asset_grants",
            "asset_grant_history",
            "library_visibility_history",
            "knowledge_libraries",
            "group_membership_history",
            "group_memberships",
            "group_stewards",
            "groups")) {
      jdbcTemplate.update("DELETE FROM " + table + " WHERE organization_id = ?", organizationId);
    }
    ownOrganizationFixtures.removeOrganizations(organizationId);
  }

  // -------------------------------------------------------------------------------------------
  // The derived state
  // -------------------------------------------------------------------------------------------

  /**
   * The acceptance criterion of the issue: the account is locked at once, its libraries go into the
   * state - and leave it by themselves when the account is usable again. No flag, no cleanup path.
   */
  @Test
  void aLibraryOfALockedAccountEntersAndLeavesTheStateByItself() {
    CurrentUser owner = user(SystemRole.USER);
    UUID library = libraryOwnedByUser(owner.id());

    assertThat(successionService.isOpen(SuccessionObjectType.KNOWLEDGE_LIBRARY, library)).isFalse();

    lock(owner.id());
    assertThat(successionService.isOpen(SuccessionObjectType.KNOWLEDGE_LIBRARY, library)).isTrue();

    unlock(owner.id());
    assertThat(successionService.isOpen(SuccessionObjectType.KNOWLEDGE_LIBRARY, library))
        .as("a derived state ends when its reason ends, without anybody clearing anything")
        .isFalse();
  }

  /** The group half: emptied of active accounts, then filled again. */
  @Test
  void aLibraryOfAGroupWithoutActiveMembersEntersAndLeavesTheState() {
    CurrentUser member = user(SystemRole.USER);
    UUID group = group("Referat 50", member.id());
    UUID library = libraryOwnedByGroup(group);

    assertThat(successionService.isOpen(SuccessionObjectType.KNOWLEDGE_LIBRARY, library)).isFalse();

    lock(member.id());
    membershipResolver.invalidateUser(member.id());
    assertThat(successionService.isOpen(SuccessionObjectType.KNOWLEDGE_LIBRARY, library)).isTrue();
    assertThat(
            successionService
                .findingFor(SuccessionObjectType.KNOWLEDGE_LIBRARY, library)
                .orElseThrow()
                .addressee())
        .as("an internal group's library is the business of its stewards")
        .isEqualTo(SuccessionAddressee.GROUP_STEWARDS);

    unlock(member.id());
    membershipResolver.invalidateUser(member.id());
    assertThat(successionService.isOpen(SuccessionObjectType.KNOWLEDGE_LIBRARY, library)).isFalse();
  }

  /** A dissolved group can act as little as an empty one - same state, same list. */
  @Test
  void aLibraryOfADissolvedGroupIsInTheState() {
    CurrentUser member = user(SystemRole.USER);
    UUID group = group("Referat 50", member.id());
    UUID library = libraryOwnedByGroup(group);
    Group dissolved = groups.findById(group).orElseThrow();
    dissolved.dissolve(Instant.now());
    groups.save(dissolved);

    assertThat(successionService.isOpen(SuccessionObjectType.KNOWLEDGE_LIBRARY, library)).isTrue();
  }

  // -------------------------------------------------------------------------------------------
  // The frozen reach
  // -------------------------------------------------------------------------------------------

  /**
   * The object keeps working and keeps every right it has; only its reach is frozen - and the
   * refusal says who is responsible.
   */
  @Test
  void refusesToWidenTheReachOfAnObjectWhoseSuccessionIsOpenAndNamesTheAddressee() {
    CurrentUser owner = user(SystemRole.USER);
    UUID library = libraryOwnedByUser(owner.id());
    CurrentUser reader = user(SystemRole.USER);
    lock(owner.id());

    assertThatThrownBy(
            () ->
                grantService.upsertGrant(
                    library,
                    new AssetGrantUpsert(PermissionSubjectType.USER, reader.id(), AssetRole.VIEWER),
                    admin))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Nachfolge offen")
        .hasMessageContaining("nichts wird")
        .hasMessageContaining("Systemverwaltung");
    assertThat(libraries.findById(library))
        .as("nothing is deleted and nothing is changed - the object stays as it is")
        .isPresent();
  }

  /** A space without a capable ADMIN takes no new members. */
  @Test
  void refusesANewSpaceMemberWhileTheSuccessionOfTheSpaceIsOpen() {
    CurrentUser owner = user(SystemRole.USER);
    Space space = space(owner);
    CurrentUser newcomer = user(SystemRole.USER);
    lock(owner.id());

    assertThatThrownBy(
            () ->
                spaceService.addMember(
                    space.getId(),
                    PermissionSubject.user(newcomer.id(), organizationId),
                    SpaceRole.MEMBER,
                    owner))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Nachfolge offen");
  }

  // -------------------------------------------------------------------------------------------
  // The detection run and the list
  // -------------------------------------------------------------------------------------------

  /** The run writes the first sighting and the end; the age of the list comes from it. */
  @Test
  void theRunRecordsTheFirstSightingAndTheEnd() {
    CurrentUser owner = user(SystemRole.USER);
    UUID library = libraryOwnedByUser(owner.id());
    lock(owner.id());

    detectionService.runFor(organizationId);
    SuccessionCase open =
        cases
            .findByKindAndObjectTypeAndObjectIdAndClosedAtIsNull(
                SuccessionKind.OPEN_SUCCESSION, SuccessionObjectType.KNOWLEDGE_LIBRARY, library)
            .orElseThrow();
    assertThat(open.getFirstSeenAt()).isNotNull();
    assertThat(open.getClosedAt()).isNull();

    // A second pass keeps the first sighting - "Alter" must not restart at every look.
    detectionService.runFor(organizationId);
    SuccessionCase again =
        cases
            .findByKindAndObjectTypeAndObjectIdAndClosedAtIsNull(
                SuccessionKind.OPEN_SUCCESSION, SuccessionObjectType.KNOWLEDGE_LIBRARY, library)
            .orElseThrow();
    assertThat(again.getId()).isEqualTo(open.getId());
    assertThat(again.getFirstSeenAt()).isEqualTo(open.getFirstSeenAt());

    unlock(owner.id());
    detectionService.runFor(organizationId);
    assertThat(cases.findById(open.getId()).orElseThrow().getClosedAt()).isNotNull();
  }

  /** The list holds every open succession from day one - before the run has ever looked. */
  @Test
  void theListIsCompleteBeforeTheRunHasSeenAnything() {
    CurrentUser owner = user(SystemRole.USER);
    UUID library = libraryOwnedByUser(owner.id());
    lock(owner.id());

    SuccessionPage page =
        successionService.list(organizationId, SuccessionKind.OPEN_SUCCESSION, 0, 50);

    assertThat(page.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.finding().objectId()).isEqualTo(library);
              assertThat(entry.caseId()).as("the record follows within the hour").isNull();
              assertThat(entry.firstSeenAt()).isNull();
              assertThat(entry.highlighted()).isFalse();
            });
  }

  /** "Freigaben ohne Empfänger": a group with grants and no active account left. */
  @Test
  void findsAGroupWithGrantsAndWithoutAnActiveMember() {
    CurrentUser member = user(SystemRole.USER);
    UUID group = group("Referat 50", member.id());
    UUID library = libraryOwnedByUser(admin.id());
    grantService.upsertGrant(
        library, new AssetGrantUpsert(PermissionSubjectType.GROUP, group, AssetRole.VIEWER), admin);
    lock(member.id());
    membershipResolver.invalidateUser(member.id());

    SuccessionPage page =
        successionService.list(organizationId, SuccessionKind.GRANTS_WITHOUT_RECIPIENT, 0, 50);

    assertThat(page.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.finding().objectId()).isEqualTo(group);
              assertThat(entry.finding().affectedObjects())
                  .as("the tab names how many objects hang on the dead group")
                  .isEqualTo(1);
            });
  }

  /** "Gruppen ohne Wirkung": an internal group holding nothing and reaching nobody. */
  @Test
  void findsAnInternalGroupWithoutEffect() {
    UUID empty = group("Arbeitskreis ohne Wirkung");

    SuccessionPage page =
        successionService.list(organizationId, SuccessionKind.GROUP_WITHOUT_EFFECT, 0, 50);

    assertThat(page.entries()).extracting(entry -> entry.finding().objectId()).contains(empty);
  }

  /** An internal group without an active steward is an open succession, not a dead group. */
  @Test
  void findsAnInternalGroupWithoutAnActiveSteward() {
    CurrentUser steward = user(SystemRole.USER);
    UUID group = group("Arbeitskreis", steward.id());
    stewards.save(new GroupSteward(group, steward.id(), organizationId, steward.id()));
    lock(steward.id());
    membershipResolver.invalidateUser(steward.id());

    SuccessionPage page =
        successionService.list(organizationId, SuccessionKind.OPEN_SUCCESSION, 0, 50);

    assertThat(page.entries()).extracting(entry -> entry.finding().objectId()).contains(group);
  }

  /** The Sichtungsvermerk lifts the highlight and triggers nothing else. */
  @Test
  void aSichtungsvermerkIsRecordedAgainstTheCase() {
    CurrentUser owner = user(SystemRole.USER);
    libraryOwnedByUser(owner.id());
    lock(owner.id());
    detectionService.runFor(organizationId);
    UUID caseId =
        successionService
            .list(organizationId, SuccessionKind.OPEN_SUCCESSION, 0, 50)
            .entries()
            .get(0)
            .caseId();

    SuccessionReview review =
        successionService.review(caseId, "Nachfolge wird im Referat geklärt", admin);

    assertThat(review.getCaseId()).isEqualTo(caseId);
    assertThat(review.getReviewedByUserId()).isEqualTo(admin.id());
    assertThat(
            successionService
                .list(organizationId, SuccessionKind.OPEN_SUCCESSION, 0, 50)
                .entries()
                .get(0)
                .lastReviewReason())
        .isEqualTo("Nachfolge wird im Referat geklärt");
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private void lock(UUID userId) {
    jdbcTemplate.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", userId);
  }

  private void unlock(UUID userId) {
    jdbcTemplate.update("UPDATE users SET directory_locked_at = NULL WHERE id = ?", userId);
  }

  private UUID libraryOwnedByUser(UUID ownerId) {
    return libraries
        .save(
            KnowledgeLibrary.ownedByUser(
                organizationId,
                "Bibliothek " + UUID.randomUUID(),
                null,
                ownerId,
                LibraryVisibility.PRIVATE,
                false))
        .getId();
  }

  private UUID libraryOwnedByGroup(UUID groupId) {
    return libraries
        .save(
            KnowledgeLibrary.ownedByGroup(
                organizationId,
                "Referatsbibliothek " + UUID.randomUUID(),
                null,
                groupId,
                LibraryVisibility.PRIVATE,
                false))
        .getId();
  }

  private Space space(CurrentUser owner) {
    return spaceService.createSpace(
        new SpaceCreation("Team", null, owner.id(), SpaceVisibility.PRIVATE, List.of(), null),
        owner);
  }

  private UUID group(String name, UUID... memberIds) {
    Group group = Group.internal(organizationId, name, null, null);
    group.release(true);
    for (UUID memberId : memberIds) {
      group.addMembership(new GroupMembership(memberId, organizationId));
    }
    UUID groupId = groups.save(group).getId();
    membershipResolver.invalidateUsers(List.of(memberIds));
    return groupId;
  }

  private CurrentUser user(SystemRole role) {
    User user =
        new User(
            UUID.randomUUID().toString(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.org",
            "Nachfolge");
    user.setOrganizationId(organizationId);
    user.setSystemRole(role);
    User saved = users.save(user);
    membershipResolver.invalidateUser(saved.getId());
    return CurrentUser.of(saved.getId(), organizationId, role, saved.getDisplayName());
  }
}
