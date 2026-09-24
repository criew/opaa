package io.opaa.group;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetGrantUpsert;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.library.KnowledgeLibrary;
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
import io.opaa.test.OwnOrganizationFixtures;
import io.opaa.test.ProviderFixtures;
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
 * limit of {@code GroupMemberDisclosureDirectory}'s rule is exercised here, as is the audit event a
 * retrieval by the system role leaves behind (Auflage A6).
 */
@OpaaIntegrationTest
class GrantedGroupMembersIntegrationTest {

  @Autowired private AssetGrantService grantService;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private SpaceService spaceService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupStewardRepository stewardRepository;
  @Autowired private GroupContactRepository contactRepository;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private UserRepository userRepository;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organization;
  private final List<UUID> createdProviderIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    createdProviderIds.clear();
    organization = organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();
  }

  @AfterEach
  void tearDown() {
    // Groups are deliberately not the shared helper's business (see its Javadoc), and they are held
    // by what references them with RESTRICT - so those two tables go first, the group tables next,
    // and the helper does everything else including the organization itself.
    for (String table :
        List.of(
            "asset_grants",
            "space_memberships",
            "group_contacts",
            "group_memberships",
            "group_stewards",
            "groups")) {
      jdbcTemplate.update("DELETE FROM " + table + " WHERE organization_id = ?", organization);
    }
    ownOrganizationFixtures.removeOrganizations(organization);
    // fk_groups_provider is RESTRICT: the provider can only go once its groups are gone.
    for (UUID providerId : createdProviderIds) {
      providerRepository.deleteById(providerId);
    }
  }

  // -------------------------------------------------------------------------------------------
  // Die Berechtigung an einer Bibliothek
  // -------------------------------------------------------------------------------------------

  @Test
  void theManagerOfALibrarySeesTheMembersOfAGroupItGrantedARightTo() {
    UUID manager = createUser();
    UUID group = createReleasedGroup("Referat 50", fiveNamedMembers());
    UUID library = createLibrary(manager);
    grantTo(library, group, manager);

    GroupMemberDisclosure disclosure =
        grantService.listGroupMembers(
            KnowledgeLibrary.ASSET_TYPE, library, group, 0, 50, callerOf(manager));

    assertThat(disclosure.name()).isEqualTo("Referat 50");
    assertThat(disclosure.protectedGroup()).isFalse();
    assertThat(disclosure.smallGroup()).isFalse();
    assertThat(disclosure.activeMemberCount()).isEqualTo(5);
    assertThat(disclosure.members())
        .extracting(DisclosedGroupMember::displayName)
        .containsExactly("Anna Bauer", "Bert Conrad", "Clara Dorn", "Dora Erle", "Emil Fried");
    assertThat(disclosure.members()).extracting(DisclosedGroupMember::userId).doesNotContainNull();
    assertThat(disclosure.responsible())
        .as("an unprotected group answers with its members, not with people to ask")
        .isEmpty();
  }

  /** Limit (a): the list lasts exactly as long as the right the group holds here. */
  @Test
  void afterTheGrantIsRevokedTheMembersAreOutOfReachAgain() {
    UUID manager = createUser();
    UUID group = createReleasedGroup("Referat 50", fiveNamedMembers());
    UUID library = createLibrary(manager);
    grantTo(library, group, manager);
    UUID grantId =
        grantService.listGrants(KnowledgeLibrary.ASSET_TYPE, library, callerOf(manager)).stream()
            .filter(view -> view.grant().getSubjectType() == PermissionSubjectType.GROUP)
            .map(view -> view.grant().getId())
            .findFirst()
            .orElseThrow();

    grantService.revokeGrant(KnowledgeLibrary.ASSET_TYPE, library, grantId, callerOf(manager));

    assertThatThrownBy(
            () ->
                grantService.listGroupMembers(
                    KnowledgeLibrary.ASSET_TYPE, library, group, 0, 50, callerOf(manager)))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Gruppe nicht gefunden");
  }

  /** Limit (a) again: an expired grant holds nothing, so it discloses nothing. */
  @Test
  void anExpiredGrantDisclosesNothing() {
    UUID manager = createUser();
    UUID group = createReleasedGroup("Referat 50", fiveNamedMembers());
    UUID library = createLibrary(manager);
    grantService.upsertGrant(
        KnowledgeLibrary.ASSET_TYPE,
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
            () ->
                grantService.listGroupMembers(
                    KnowledgeLibrary.ASSET_TYPE, library, group, 0, 50, callerOf(manager)))
        .isInstanceOf(NotFoundException.class);
  }

  /** Limits (b) and (c): taking the release back takes the member list with it. */
  @Test
  void aGroupWhoseReleaseWasTakenBackDisclosesNothing() {
    UUID manager = createUser();
    UUID group = createReleasedGroup("Referat 50", fiveNamedMembers());
    UUID library = createLibrary(manager);
    grantTo(library, group, manager);
    assertThat(
            grantService
                .listGroupMembers(
                    KnowledgeLibrary.ASSET_TYPE, library, group, 0, 50, callerOf(manager))
                .members())
        .hasSize(5);

    Group loaded = groupRepository.findById(group).orElseThrow();
    loaded.release(false);
    groupRepository.save(loaded);

    assertThatThrownBy(
            () ->
                grantService.listGroupMembers(
                    KnowledgeLibrary.ASSET_TYPE, library, group, 0, 50, callerOf(manager)))
        .isInstanceOf(NotFoundException.class);
  }

  /** Limit (d): a protected group hands over the people to ask - no name, no size, no members. */
  @Test
  void aProtectedGroupAnswersWithItsStewardsInsteadOfItsMembers() {
    UUID manager = createUser();
    UUID steward = createUser("Andrea Vogt");
    UUID group = createReleasedGroup("Personalrat", fiveNamedMembers());
    stewardRepository.save(new GroupSteward(group, steward, organization, steward));
    markProtected(group);
    UUID library = createLibrary(manager);
    grantTo(library, group, manager);

    GroupMemberDisclosure disclosure =
        grantService.listGroupMembers(
            KnowledgeLibrary.ASSET_TYPE, library, group, 0, 50, callerOf(manager));

    assertThat(disclosure.protectedGroup()).isTrue();
    assertThat(disclosure.name()).isNull();
    assertThat(disclosure.activeMemberCount()).isNull();
    assertThat(disclosure.members()).isEmpty();
    assertThat(disclosure.responsible()).containsExactly("Andrea Vogt");
  }

  /**
   * Limit (d) for a provider group, which has no stewards: there the contact points the system
   * administration named take their place (#1875).
   */
  @Test
  void aProtectedProviderGroupAnswersWithItsContactPoints() {
    UUID manager = createUser();
    UUID contact = createUser("Ansprechstelle Nord");
    UUID group = createProviderGroup("Personalvertretung", contact);
    markProtected(group);
    contactRepository.save(new GroupContact(group, contact, organization, manager));
    UUID library = createLibrary(manager);
    grantTo(library, group, manager);

    GroupMemberDisclosure disclosure =
        grantService.listGroupMembers(
            KnowledgeLibrary.ASSET_TYPE, library, group, 0, 50, callerOf(manager));

    assertThat(disclosure.protectedGroup()).isTrue();
    assertThat(disclosure.name()).isNull();
    assertThat(disclosure.members()).isEmpty();
    assertThat(disclosure.responsible()).containsExactly("Ansprechstelle Nord");
  }

  /**
   * Limit (e), Auflage A2: below the Mindestgruppengröße neither the names nor the figure are
   * handed out - otherwise the same row would say "kleine Gruppe" beside its growth signal and name
   * four people right next to it.
   */
  @Test
  void agroupBelowTheMinimumSizeDisclosesNeitherNamesNorFigure() {
    UUID manager = createUser();
    UUID[] members = fiveNamedMembers();
    UUID ofFourId =
        createReleasedGroup("Kleine Runde", members[0], members[1], members[2], members[3]);
    UUID ofFiveId = createReleasedGroup("Referat 50", fiveNamedMembers());
    UUID library = createLibrary(manager);
    grantTo(library, ofFourId, manager);
    grantTo(library, ofFiveId, manager);

    GroupMemberDisclosure ofFour =
        grantService.listGroupMembers(
            KnowledgeLibrary.ASSET_TYPE, library, ofFourId, 0, 50, callerOf(manager));

    assertThat(ofFour.smallGroup()).isTrue();
    assertThat(ofFour.activeMemberCount()).isNull();
    assertThat(ofFour.members()).isEmpty();
    assertThat(ofFour.name()).as("the group is named in the grant list anyway").isNotNull();

    GroupMemberDisclosure ofFive =
        grantService.listGroupMembers(
            KnowledgeLibrary.ASSET_TYPE, library, ofFiveId, 0, 50, callerOf(manager));

    assertThat(ofFive.smallGroup()).isFalse();
    assertThat(ofFive.activeMemberCount()).isEqualTo(5);
    assertThat(ofFive.members()).hasSize(5);
  }

  @Test
  void aCallerBelowManagerAndAnUnknownGroupBothGetNothing() {
    UUID manager = createUser();
    UUID viewer = createUser();
    UUID group = createReleasedGroup("Referat 50", fiveNamedMembers());
    UUID library = createLibrary(manager);
    grantTo(library, group, manager);
    grantService.upsertGrant(
        KnowledgeLibrary.ASSET_TYPE,
        library,
        new AssetGrantUpsert(PermissionSubjectType.USER, viewer, AssetRole.VIEWER, null),
        callerOf(manager));

    assertThatThrownBy(
            () ->
                grantService.listGroupMembers(
                    KnowledgeLibrary.ASSET_TYPE, library, group, 0, 50, callerOf(viewer)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                grantService.listGroupMembers(
                    KnowledgeLibrary.ASSET_TYPE,
                    library,
                    UUID.randomUUID(),
                    0,
                    50,
                    callerOf(manager)))
        .isInstanceOf(NotFoundException.class);
  }

  /**
   * The page is a window into the whole group: the cap is the LIMIT of the query, the total says
   * how far the paging reaches, and the name order keeps two pages from overlapping.
   */
  @Test
  void theListIsPagedWhileTheCountNamesTheWhole() {
    UUID manager = createUser();
    UUID group = createReleasedGroup("Referat 50", fiveNamedMembers());
    UUID library = createLibrary(manager);
    grantTo(library, group, manager);

    GroupMemberDisclosure first =
        grantService.listGroupMembers(
            KnowledgeLibrary.ASSET_TYPE, library, group, 0, 2, callerOf(manager));
    GroupMemberDisclosure second =
        grantService.listGroupMembers(
            KnowledgeLibrary.ASSET_TYPE, library, group, 2, 2, callerOf(manager));

    assertThat(first.activeMemberCount()).isEqualTo(5);
    assertThat(first.members())
        .extracting(DisclosedGroupMember::displayName)
        .containsExactly("Anna Bauer", "Bert Conrad");
    assertThat(second.members())
        .extracting(DisclosedGroupMember::displayName)
        .containsExactly("Clara Dorn", "Dora Erle");
    // A nonsensical window is clamped rather than refused: a limit below one would otherwise turn
    // into a query that can never answer anything, a negative offset into a database error.
    assertThat(
            grantService
                .listGroupMembers(
                    KnowledgeLibrary.ASSET_TYPE, library, group, -5, 0, callerOf(manager))
                .members())
        .hasSize(1);
  }

  /** A locked account keeps its membership but is no longer part of the reach (#1818). */
  @Test
  void aLockedAccountIsNoPartOfTheReachAndNoPartOfTheList() {
    UUID manager = createUser();
    UUID[] members = fiveNamedMembers();
    UUID sixth = createUser("Frida Gast");
    UUID group =
        createReleasedGroup(
            "Referat 50", members[0], members[1], members[2], members[3], members[4], sixth);
    User lockedUser = userRepository.findById(sixth).orElseThrow();
    lockedUser.lockFromDirectory(Instant.now());
    userRepository.save(lockedUser);
    UUID library = createLibrary(manager);
    grantTo(library, group, manager);

    GroupMemberDisclosure disclosure =
        grantService.listGroupMembers(
            KnowledgeLibrary.ASSET_TYPE, library, group, 0, 50, callerOf(manager));

    assertThat(disclosure.activeMemberCount()).isEqualTo(5);
    assertThat(disclosure.members())
        .extracting(DisclosedGroupMember::displayName)
        .doesNotContain("Frida Gast");
  }

  // -------------------------------------------------------------------------------------------
  // Der Nachweiseintrag der Systemverwaltung (Auflage A6)
  // -------------------------------------------------------------------------------------------

  /**
   * The event hangs on the caller, not on the endpoint: a system administrator passes the MANAGER
   * bar at every library through their role alone, and ADR-0036, Entscheidung 9 records that they
   * looked.
   */
  @Test
  void theRetrievalOverALibraryIsAnAuditEventForASystemAdministratorAndNoneForAGrantGiver() {
    UUID manager = createUser();
    UUID systemAdmin = createSystemAdmin();
    UUID group = createReleasedGroup("Referat 50", fiveNamedMembers());
    UUID library = createLibrary(manager);
    grantTo(library, group, manager);

    grantService.listGroupMembers(
        KnowledgeLibrary.ASSET_TYPE, library, group, 0, 50, callerOf(manager));

    assertThat(memberReadEvents(group)).as("the grant giver writes nothing").isZero();

    grantService.listGroupMembers(
        KnowledgeLibrary.ASSET_TYPE, library, group, 0, 50, callerOf(systemAdmin));

    assertThat(memberReadEvents(group)).isOne();
  }

  @Test
  void theRetrievalOverASpaceIsAnAuditEventForASystemAdministratorAndNoneForTheOwner() {
    UUID owner = createUser();
    UUID systemAdmin = createSystemAdmin();
    UUID group = createReleasedGroup("Referat 50", fiveNamedMembers());
    Space space = createSpace(owner);
    admitGroup(space, group, owner);

    spaceService.listGroupMembers(space.getId(), group, 0, 50, callerOf(owner));

    assertThat(memberReadEvents(group))
        .as("the owner who admitted the group writes nothing")
        .isZero();

    spaceService.listGroupMembers(space.getId(), group, 0, 50, callerOf(systemAdmin));

    assertThat(memberReadEvents(group)).isOne();
  }

  /** A steward reading their own group leaves no record, whichever way in they took. */
  @Test
  void aStewardWithASystemRoleReadingTheirOwnGroupWritesNothing() {
    UUID systemAdmin = createSystemAdmin();
    UUID group = createReleasedGroup("Referat 50", fiveNamedMembers());
    stewardRepository.save(new GroupSteward(group, systemAdmin, organization, systemAdmin));
    UUID library = createLibrary(systemAdmin);
    grantTo(library, group, systemAdmin);

    grantService.listGroupMembers(
        KnowledgeLibrary.ASSET_TYPE, library, group, 0, 50, callerOf(systemAdmin));

    assertThat(memberReadEvents(group)).isZero();
  }

  // -------------------------------------------------------------------------------------------
  // Die Mitgliedschaft in einem Space
  // -------------------------------------------------------------------------------------------

  @Test
  void theOwnerOfASpaceSeesTheMembersOfAGroupItAdmitted() {
    UUID owner = createUser();
    UUID group = createReleasedGroup("Referat 50", fiveNamedMembers());
    Space space = createSpace(owner);
    admitGroup(space, group, owner);

    GroupMemberDisclosure disclosure =
        spaceService.listGroupMembers(space.getId(), group, 0, 50, callerOf(owner));

    assertThat(disclosure.name()).isEqualTo("Referat 50");
    assertThat(disclosure.members())
        .extracting(DisclosedGroupMember::displayName)
        .contains("Anna Bauer", "Emil Fried");
  }

  @Test
  void aGroupThatIsNoMemberOfThisSpaceDisclosesNothing() {
    UUID owner = createUser();
    UUID group = createReleasedGroup("Referat 50", fiveNamedMembers());
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
    UUID group = createReleasedGroup("Referat 50", fiveNamedMembers());
    Space space = createSpace(owner);
    admitGroup(space, group, owner);
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
    UUID group = createReleasedGroup("Personalrat", fiveNamedMembers());
    Space space = createSpace(owner);
    admitGroup(space, group, owner);
    stewardRepository.save(new GroupSteward(group, steward, organization, steward));
    markProtected(group);

    GroupMemberDisclosure disclosure =
        spaceService.listGroupMembers(space.getId(), group, 0, 50, callerOf(owner));

    assertThat(disclosure.name()).isNull();
    assertThat(disclosure.members()).isEmpty();
    assertThat(disclosure.responsible()).containsExactly("Andrea Vogt");
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  /** Five accounts whose names sort in this order - the order the paged list must keep. */
  private UUID[] fiveNamedMembers() {
    return new UUID[] {
      createUser("Anna Bauer"),
      createUser("Bert Conrad"),
      createUser("Clara Dorn"),
      createUser("Dora Erle"),
      createUser("Emil Fried")
    };
  }

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

  private UUID createProviderGroup(String name, UUID... memberIds) {
    UUID provider = ProviderFixtures.tokenProvider(providerRepository).getId();
    createdProviderIds.add(provider);
    Group group =
        new Group(
            organization,
            GroupKind.ORG_UNIT,
            name,
            null,
            provider,
            UUID.randomUUID().toString(),
            null,
            null);
    for (UUID memberId : memberIds) {
      group.addMembership(new GroupMembership(memberId, organization));
    }
    UUID id = groupRepository.save(group).getId();
    membershipResolver.invalidateUsers(List.of(memberIds));
    return id;
  }

  private void markProtected(UUID groupId) {
    Group group = groupRepository.findById(groupId).orElseThrow();
    group.markProtected(true);
    groupRepository.save(group);
  }

  private UUID createLibrary(UUID ownerId) {
    return libraryService
        .createLibrary(
            libraryCreation("Bibliothek", DocumentSourceType.UPLOAD)
                .ownerType(AssetOwnerType.USER)
                .ownerId(ownerId)
                .build(),
            callerOf(ownerId))
        .library()
        .getId();
  }

  private void grantTo(UUID libraryId, UUID groupId, UUID caller) {
    grantService.upsertGrant(
        KnowledgeLibrary.ASSET_TYPE,
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.GROUP, groupId, AssetRole.VIEWER, null),
        callerOf(caller));
  }

  private Space createSpace(UUID owner) {
    return spaceService.createSpace(
        new SpaceCreation("Team", "Team docs", owner, SpaceVisibility.PRIVATE, List.of(), null),
        callerOf(owner));
  }

  private void admitGroup(Space space, UUID groupId, UUID caller) {
    spaceService.addMember(
        space.getId(),
        PermissionSubject.group(groupId, organization),
        SpaceRole.MEMBER,
        callerOf(caller));
  }

  private int memberReadEvents(UUID groupId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log WHERE organization_id = ?"
                + " AND event_type = 'GROUP_MEMBERS_READ' AND object_id = ?",
            Integer.class,
            organization,
            groupId.toString());
    return count == null ? 0 : count;
  }

  private UUID createUser() {
    return createUser("Test User");
  }

  private UUID createUser(String displayName) {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", displayName);
    user.setOrganizationId(organization);
    return userRepository.save(user).getId();
  }

  private UUID createSystemAdmin() {
    UUID id = createUser("System Verwaltung");
    User user = userRepository.findById(id).orElseThrow();
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    userRepository.save(user);
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
