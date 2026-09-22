package io.opaa.group;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryOwnerType;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.library.AssetGrantService;
import io.opaa.library.AssetGrantUpsert;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.DisclosedGroupMember;
import io.opaa.permission.GroupMemberDisclosure;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.PermissionSubject;
import io.opaa.space.Space;
import io.opaa.space.SpaceCreation;
import io.opaa.space.SpaceService;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * "Wer ein Recht gibt, sieht, an wen" (#1880, ADR-0036 Entscheidung 9) against the real schema, on
 * both objects that can carry a group: the grant on a library and the membership in a space. Every
 * one of the ADR's four limits is exercised here - the right held at the object (a), the release
 * for use (b) with "not released" as the delivered default (c), and the protected group (d), where
 * the answer is the people to ask instead of the members.
 */
@OpaaIntegrationTest
class GrantedGroupMembersIntegrationTest {

  @Autowired private AssetGrantService grantService;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private SpaceService spaceService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupStewardRepository stewardRepository;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organization;
  private final List<UUID> createdUserIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    createdUserIds.clear();
    organization = organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM asset_grant_history WHERE organization_id = ?", organization);
    jdbcTemplate.update("DELETE FROM asset_grants WHERE organization_id = ?", organization);
    jdbcTemplate.update(
        "DELETE FROM asset_ownership_history WHERE organization_id = ?", organization);
    jdbcTemplate.update(
        "DELETE FROM library_visibility_history WHERE organization_id = ?", organization);
    jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE organization_id = ?", organization);
    jdbcTemplate.update(
        "DELETE FROM space_membership_history WHERE organization_id = ?", organization);
    jdbcTemplate.update("DELETE FROM space_memberships WHERE organization_id = ?", organization);
    jdbcTemplate.update("DELETE FROM spaces WHERE organization_id = ?", organization);
    for (String table :
        List.of("group_membership_history", "group_memberships", "group_stewards", "groups")) {
      jdbcTemplate.update("DELETE FROM " + table + " WHERE organization_id = ?", organization);
    }
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organization);
    userRepository.deleteAllById(createdUserIds);
    organizationRepository.deleteById(organization);
  }

  // -------------------------------------------------------------------------------------------
  // Die Berechtigung an einer Bibliothek
  // -------------------------------------------------------------------------------------------

  @Test
  void theManagerOfALibrarySeesTheMembersOfAGroupItGrantedARightTo() {
    UUID manager = createUser();
    UUID anna = createUser("Anna Bauer");
    UUID bert = createUser("Bert Conrad");
    UUID group = createReleasedGroup("Referat 50", anna, bert);
    UUID library = createLibrary(manager);
    grantTo(library, group, manager);

    GroupMemberDisclosure disclosure =
        grantService.listGroupMembers(library, group, 0, 50, callerOf(manager));

    assertThat(disclosure.name()).isEqualTo("Referat 50");
    assertThat(disclosure.protectedGroup()).isFalse();
    assertThat(disclosure.activeMemberCount()).isEqualTo(2);
    assertThat(disclosure.members())
        .extracting(DisclosedGroupMember::displayName)
        .containsExactly("Anna Bauer", "Bert Conrad");
    assertThat(disclosure.members()).extracting(DisclosedGroupMember::userId).contains(anna, bert);
    assertThat(disclosure.responsible())
        .as("an unprotected group answers with its members, not with people to ask")
        .isEmpty();
  }

  /** Limit (a): the list lasts exactly as long as the right the group holds here. */
  @Test
  void afterTheGrantIsRevokedTheMembersAreOutOfReachAgain() {
    UUID manager = createUser();
    UUID group = createReleasedGroup("Referat 50", createUser());
    UUID library = createLibrary(manager);
    grantTo(library, group, manager);
    UUID grantId =
        grantService.listGrants(library, callerOf(manager)).stream()
            .filter(view -> view.grant().getSubjectType() == PermissionSubjectType.GROUP)
            .map(view -> view.grant().getId())
            .findFirst()
            .orElseThrow();

    grantService.revokeGrant(library, grantId, callerOf(manager));

    assertThatThrownBy(
            () -> grantService.listGroupMembers(library, group, 0, 50, callerOf(manager)))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Gruppe nicht gefunden");
  }

  /** Limit (a) again: an expired grant holds nothing, so it discloses nothing. */
  @Test
  void anExpiredGrantDisclosesNothing() {
    UUID manager = createUser();
    UUID group = createReleasedGroup("Referat 50", createUser());
    UUID library = createLibrary(manager);
    grantService.upsertGrant(
        library,
        new AssetGrantUpsert(
            PermissionSubjectType.GROUP,
            group,
            AssetRole.VIEWER,
            Instant.now().plus(1, ChronoUnit.HOURS)),
        callerOf(manager));
    jdbcTemplate.update(
        "UPDATE asset_grants SET expires_at = ? WHERE subject_group_id = ?",
        java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
        group);

    assertThatThrownBy(
            () -> grantService.listGroupMembers(library, group, 0, 50, callerOf(manager)))
        .isInstanceOf(NotFoundException.class);
  }

  /** Limits (b) and (c): taking the release back takes the member list with it. */
  @Test
  void aGroupWhoseReleaseWasTakenBackDisclosesNothing() {
    UUID manager = createUser();
    UUID group = createReleasedGroup("Referat 50", createUser());
    UUID library = createLibrary(manager);
    grantTo(library, group, manager);
    assertThat(grantService.listGroupMembers(library, group, 0, 50, callerOf(manager)).members())
        .hasSize(1);

    Group loaded = groupRepository.findById(group).orElseThrow();
    loaded.release(false);
    groupRepository.save(loaded);

    assertThatThrownBy(
            () -> grantService.listGroupMembers(library, group, 0, 50, callerOf(manager)))
        .isInstanceOf(NotFoundException.class);
  }

  /** Limit (d): a protected group hands over the people to ask - no name, no size, no members. */
  @Test
  void aProtectedGroupAnswersWithItsStewardsInsteadOfItsMembers() {
    UUID manager = createUser();
    UUID steward = createUser("Andrea Vogt");
    UUID group = createReleasedGroup("Personalrat", createUser(), createUser());
    stewardRepository.save(new GroupSteward(group, steward, organization, steward));
    Group loaded = groupRepository.findById(group).orElseThrow();
    loaded.markProtected(true);
    groupRepository.save(loaded);
    UUID library = createLibrary(manager);
    grantTo(library, group, manager);

    GroupMemberDisclosure disclosure =
        grantService.listGroupMembers(library, group, 0, 50, callerOf(manager));

    assertThat(disclosure.protectedGroup()).isTrue();
    assertThat(disclosure.name()).isNull();
    assertThat(disclosure.activeMemberCount()).isNull();
    assertThat(disclosure.members()).isEmpty();
    assertThat(disclosure.responsible()).containsExactly("Andrea Vogt");
  }

  @Test
  void aCallerBelowManagerAndAnUnknownGroupBothGetNothing() {
    UUID manager = createUser();
    UUID viewer = createUser();
    UUID group = createReleasedGroup("Referat 50", createUser());
    UUID library = createLibrary(manager);
    grantTo(library, group, manager);
    grantService.upsertGrant(
        library,
        new AssetGrantUpsert(PermissionSubjectType.USER, viewer, AssetRole.VIEWER, null),
        callerOf(manager));

    assertThatThrownBy(() -> grantService.listGroupMembers(library, group, 0, 50, callerOf(viewer)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                grantService.listGroupMembers(library, UUID.randomUUID(), 0, 50, callerOf(manager)))
        .isInstanceOf(NotFoundException.class);
  }

  /**
   * The page is a window into the whole group: the cap is the LIMIT of the query, the total says
   * how far the paging reaches, and the name order keeps two pages from overlapping.
   */
  @Test
  void theListIsPagedWhileTheCountNamesTheWhole() {
    UUID manager = createUser();
    UUID anna = createUser("Anna Bauer");
    UUID bert = createUser("Bert Conrad");
    UUID clara = createUser("Clara Dorn");
    UUID group = createReleasedGroup("Referat 50", clara, anna, bert);
    UUID library = createLibrary(manager);
    grantTo(library, group, manager);

    GroupMemberDisclosure first =
        grantService.listGroupMembers(library, group, 0, 2, callerOf(manager));
    GroupMemberDisclosure second =
        grantService.listGroupMembers(library, group, 2, 2, callerOf(manager));

    assertThat(first.activeMemberCount()).isEqualTo(3);
    assertThat(first.members())
        .extracting(DisclosedGroupMember::displayName)
        .containsExactly("Anna Bauer", "Bert Conrad");
    assertThat(second.members())
        .extracting(DisclosedGroupMember::displayName)
        .containsExactly("Clara Dorn");
    // A nonsensical window is clamped rather than refused: a limit below one would otherwise turn
    // into a query that can never answer anything, a negative offset into a database error.
    assertThat(grantService.listGroupMembers(library, group, -5, 0, callerOf(manager)).members())
        .hasSize(1);
  }

  /** A locked account keeps its membership but is no longer part of the reach (#1818). */
  @Test
  void aLockedAccountIsNoPartOfTheReachAndNoPartOfTheList() {
    UUID manager = createUser();
    UUID active = createUser("Anna Bauer");
    UUID locked = createUser("Bert Conrad");
    UUID group = createReleasedGroup("Referat 50", active, locked);
    User lockedUser = userRepository.findById(locked).orElseThrow();
    lockedUser.lockFromDirectory(Instant.now());
    userRepository.save(lockedUser);
    UUID library = createLibrary(manager);
    grantTo(library, group, manager);

    GroupMemberDisclosure disclosure =
        grantService.listGroupMembers(library, group, 0, 50, callerOf(manager));

    assertThat(disclosure.activeMemberCount()).isEqualTo(1);
    assertThat(disclosure.members())
        .extracting(DisclosedGroupMember::displayName)
        .containsExactly("Anna Bauer");
  }

  /**
   * ADR-0036, Entscheidung 9 records the retrieval for the system administration alone (#1821). The
   * grant giver reads the group they brought into their own object; who did that and when is
   * already on the grant.
   */
  @Test
  void theRetrievalByAGrantGiverIsNoAuditEvent() {
    UUID manager = createUser();
    UUID group = createReleasedGroup("Referat 50", createUser());
    UUID library = createLibrary(manager);
    grantTo(library, group, manager);

    grantService.listGroupMembers(library, group, 0, 50, callerOf(manager));

    Integer events =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log WHERE organization_id = ?"
                + " AND event_type = 'GROUP_MEMBERS_READ'",
            Integer.class,
            organization);
    assertThat(events).isZero();
  }

  // -------------------------------------------------------------------------------------------
  // Die Mitgliedschaft in einem Space
  // -------------------------------------------------------------------------------------------

  @Test
  void theOwnerOfASpaceSeesTheMembersOfAGroupItAdmitted() {
    UUID owner = createUser();
    UUID anna = createUser("Anna Bauer");
    UUID group = createReleasedGroup("Referat 50", anna);
    Space space = createSpace(owner);
    spaceService.addMember(
        space.getId(),
        PermissionSubject.group(group, organization),
        SpaceRole.MEMBER,
        callerOf(owner));

    GroupMemberDisclosure disclosure =
        spaceService.listGroupMembers(space.getId(), group, 0, 50, callerOf(owner));

    assertThat(disclosure.name()).isEqualTo("Referat 50");
    assertThat(disclosure.members())
        .extracting(DisclosedGroupMember::displayName)
        .containsExactly("Anna Bauer");
  }

  @Test
  void aGroupThatIsNoMemberOfThisSpaceDisclosesNothing() {
    UUID owner = createUser();
    UUID group = createReleasedGroup("Referat 50", createUser());
    Space space = createSpace(owner);

    assertThatThrownBy(
            () -> spaceService.listGroupMembers(space.getId(), group, 0, 50, callerOf(owner)))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Gruppe nicht gefunden");
  }

  /** Behind the same bar as the member list itself: a MEMBER never reads it (#144). */
  @Test
  void aPlainMemberOfTheSpaceGetsNothing() {
    UUID owner = createUser();
    UUID person = createUser();
    UUID group = createReleasedGroup("Referat 50", createUser());
    Space space = createSpace(owner);
    spaceService.addMember(
        space.getId(),
        PermissionSubject.group(group, organization),
        SpaceRole.MEMBER,
        callerOf(owner));
    spaceService.addMember(
        space.getId(),
        PermissionSubject.user(person, organization),
        SpaceRole.MEMBER,
        callerOf(owner));

    assertThatThrownBy(
            () -> spaceService.listGroupMembers(space.getId(), group, 0, 50, callerOf(person)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void aProtectedGroupInASpaceAnswersWithItsStewardsThereToo() {
    UUID owner = createUser();
    UUID steward = createUser("Andrea Vogt");
    UUID group = createReleasedGroup("Personalrat", createUser());
    Space space = createSpace(owner);
    spaceService.addMember(
        space.getId(),
        PermissionSubject.group(group, organization),
        SpaceRole.MEMBER,
        callerOf(owner));
    stewardRepository.save(new GroupSteward(group, steward, organization, steward));
    Group loaded = groupRepository.findById(group).orElseThrow();
    loaded.markProtected(true);
    groupRepository.save(loaded);

    GroupMemberDisclosure disclosure =
        spaceService.listGroupMembers(space.getId(), group, 0, 50, callerOf(owner));

    assertThat(disclosure.name()).isNull();
    assertThat(disclosure.members()).isEmpty();
    assertThat(disclosure.responsible()).containsExactly("Andrea Vogt");
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private UUID createReleasedGroup(String name, UUID... memberIds) {
    Group group = Group.internal(organization, name, null, null);
    group.release(true);
    for (UUID memberId : memberIds) {
      group.addMembership(new GroupMembership(memberId, organization));
    }
    UUID id = groupRepository.save(group).getId();
    membershipResolver.invalidateUsers(List.of(memberIds));
    return id;
  }

  private UUID createLibrary(UUID ownerId) {
    return libraryService
        .createLibrary(
            libraryCreation("Bibliothek", DocumentSourceType.UPLOAD)
                .ownerType(LibraryOwnerType.USER)
                .ownerId(ownerId)
                .build(),
            callerOf(ownerId))
        .library()
        .getId();
  }

  private void grantTo(UUID libraryId, UUID groupId, UUID caller) {
    grantService.upsertGrant(
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, groupId, AssetRole.VIEWER, null),
        callerOf(caller));
  }

  private Space createSpace(UUID owner) {
    return spaceService.createSpace(
        new SpaceCreation("Team", "Team docs", owner, SpaceVisibility.PRIVATE, List.of(), null),
        callerOf(owner));
  }

  private UUID createUser() {
    return createUser("Test User");
  }

  private UUID createUser(String displayName) {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", displayName);
    user.setOrganizationId(organization);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  private CurrentUser callerOf(UUID userId) {
    User user = userRepository.findById(userId).orElseThrow();
    return CurrentUser.of(
        user.getId(),
        user.getOrganizationId(),
        user.getSystemRole(),
        user.getDisplayName(),
        user.getEmail());
  }
}
