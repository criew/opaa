package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.chat.Chat;
import io.opaa.chat.ChatRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.library.AssetGrantHistoryRepository;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs against a real Postgres database with the real, versioned Liquibase schema applied ({@code
 * spring.liquibase.enabled=true}, {@code ddl-auto=none}), not Hibernate-generated DDL - see #288.
 * {@code Space.ownerId}, {@code SpaceMembership.userId} and every {@code organizationId} are plain
 * {@code UUID} columns without {@code @ManyToOne}; Hibernate does not create foreign keys for
 * those, Liquibase does ({@code fk_spaces_owner}, {@code fk_space_memberships_user}, {@code
 * fk_spaces_organization}, {@code fk_space_memberships_organization}). Every test therefore creates
 * real {@link Organization} and {@link User} rows instead of using bare random UUIDs.
 */
@OpaaIntegrationTest
class SpaceServiceIntegrationTest {

  @Autowired private SpaceService spaceService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private SpaceMembershipRepository membershipRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private ChatRepository chatRepository;
  @Autowired private SpaceAssetAssociationRepository associationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationA;
  private UUID organizationB;

  @BeforeEach
  void cleanUp() {
    // Deliberately does not delete all organizations: Organization.DEFAULT_ID is seeded once by
    // Liquibase and other tests sharing this Spring context (e.g.
    // UserServicePersonalSpaceIntegrationTest) rely on that row existing (fk_users_organization).
    // Each test creates its own throwaway organizations instead, scoped by random ids, and removes
    // them again in tearDown() - see tearDown() below.
    // #525: fk_chats_space is ON DELETE RESTRICT (chats survive their space being deleted, see
    // migration 032), so a leftover chat from this class's own previous test would otherwise block
    // spaceRepository.deleteAll() below.
    chatRepository.deleteAll();
    associationRepository.deleteAll();
    membershipRepository.deleteAll();
    spaceRepository.deleteAll();
    // #201: fk_knowledge_libraries_owner_user also references users now, not just fk_spaces_owner
    // - a leftover personal library from another test class sharing this context (e.g.
    // UserServicePersonalSpaceIntegrationTest, which has no @AfterEach) would otherwise block
    // userRepository.deleteAll() below with a RESTRICT violation on that unrelated user.
    libraryRepository.deleteAll();
    // #238 code review, finding 2+4: the same leftover-history risk as the library cleanup above -
    // asset_grant_history.subject_user_id/group_membership_history.user_id are ON DELETE RESTRICT
    // (see 018-permission-history.yaml's "Deletion survival" comment).
    grantHistoryRepository.deleteAll();
    membershipHistoryRepository.deleteAll();
    userRepository.deleteAll();
    organizationA =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org A")).getId();
    organizationB =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org B")).getId();
  }

  @AfterEach
  void tearDown() {
    // Users created during the test still reference organizationA/organizationB
    // (fk_users_organization) - delete them first, then remove only the two organizations this
    // test created (by id), never Organization.DEFAULT_ID or organizations created by other tests
    // sharing this context.
    chatRepository.deleteAll();
    associationRepository.deleteAll();
    membershipRepository.deleteAll();
    spaceRepository.deleteAll();
    libraryRepository.deleteAll();
    grantHistoryRepository.deleteAll();
    membershipHistoryRepository.deleteAll();
    userRepository.deleteAll();
    // #392: SpaceService now also writes audit_log rows (fk_audit_log_organization is ON DELETE
    // RESTRICT, migration 017) - purged via JdbcTemplate, same reasoning as
    // AuditLogServiceIntegrationTest#tearDown.
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE organization_id IN (?, ?)", organizationA, organizationB);
    organizationRepository.deleteAllById(List.of(organizationA, organizationB));
  }

  private UUID createUser(UUID organizationId) {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }

  private UUID createReadableLibrary(UUID organizationId, UUID ownerId) {
    io.opaa.library.KnowledgeLibrary library =
        io.opaa.library.KnowledgeLibrary.ownedByUser(
            organizationId,
            "Bibliothek",
            null,
            ownerId,
            io.opaa.library.LibraryVisibility.PRIVATE,
            false);
    UUID libraryId = libraryRepository.save(library).getId();
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
            + " subject_user_id, role, created_at, updated_at)"
            + " VALUES (?, ?, ?, 'USER', ?, 'OWNER', now(), now())",
        UUID.randomUUID(),
        libraryId,
        organizationId,
        ownerId);
    return libraryId;
  }

  private Map<SpaceRole, Long> roleCounts(Space space) {
    return space.getMemberships().stream()
        .collect(Collectors.groupingBy(SpaceMembership::getRole, Collectors.counting()));
  }

  private SpaceRole roleOf(Space space, UUID userId) {
    return space.getMemberships().stream()
        .filter(m -> m.getUserId().equals(userId))
        .findFirst()
        .orElseThrow()
        .getRole();
  }

  @Test
  void systemAdminCanCreateTeamSpace() {
    UUID adminUserId = createUser(organizationA);
    UUID ownerId = createUser(organizationA);
    UUID curatorId = createUser(organizationA);
    SpaceCreation creation =
        new SpaceCreation(
            "Engineering",
            "Engineering docs",
            ownerId,
            null,
            List.of(new SpaceMemberSeed(curatorId, SpaceRole.CURATOR)),
            null);

    Space created = spaceService.createSpace(creation, adminUserId, true);

    assertThat(created.isDefault()).isFalse();
    assertThat(created.getName()).isEqualTo("Engineering");
    assertThat(created.getOwnerId()).isEqualTo(ownerId);
    assertThat(created.getMemberships()).hasSize(2);
    assertThat(roleCounts(created).get(SpaceRole.ADMIN)).isEqualTo(1L);
    assertThat(roleCounts(created).get(SpaceRole.CURATOR)).isEqualTo(1L);
  }

  @Test
  void anyUserCanCreateSeveralSpacesTheyWorkInAlone() {
    // #333: SpaceKind is gone, so there is no TEAM kind reserved for system admins and no
    // one-personal-space-per-user rule. Five small initiatives may have five rooms.
    UUID userId = createUser(organizationA);

    Space first =
        spaceService.createSpace(
            new SpaceCreation("Vorhaben A", null, null, null, null, null), userId, false);
    Space second =
        spaceService.createSpace(
            new SpaceCreation("Vorhaben B", null, null, null, null, null), userId, false);

    assertThat(first.isDefault()).isFalse();
    assertThat(second.isDefault()).isFalse();
    assertThat(first.getMemberships()).hasSize(1);
    assertThat(second.getMemberships()).hasSize(1);
  }

  @Test
  void anyUserCanCreateAProjectSpace() {
    UUID userId = createUser(organizationA);
    SpaceCreation creation =
        new SpaceCreation("Phoenix", "My project", null, null, List.of(), null);

    Space created = spaceService.createSpace(creation, userId, false);

    assertThat(created.isDefault()).isFalse();
    assertThat(created.getOwnerId()).isEqualTo(userId);
  }

  @Test
  void twoUsersCanEachOwnAProjectSpaceWithTheSameName() {
    UUID userA = createUser(organizationA);
    UUID userB = createUser(organizationA);
    SpaceCreation requestA = new SpaceCreation("Phoenix", null, null, null, List.of(), null);
    SpaceCreation requestB = new SpaceCreation("Phoenix", null, null, null, List.of(), null);

    Space createdA = spaceService.createSpace(requestA, userA, false);
    Space createdB = spaceService.createSpace(requestB, userB, false);

    assertThat(createdA.getName()).isEqualTo("Phoenix");
    assertThat(createdB.getName()).isEqualTo("Phoenix");
    assertThat(createdA.getId()).isNotEqualTo(createdB.getId());
  }

  @Test
  void listReturnsOnlyMembershipSpaces() {
    UUID userA = createUser(organizationA);
    UUID userB = createUser(organizationA);
    Space eng =
        new Space(
            "Engineering",
            "Engineering docs",
            false,
            SpaceVisibility.PRIVATE,
            userA,
            organizationA);
    eng.addMembership(new SpaceMembership(userA, SpaceRole.ADMIN, organizationA));
    eng.addMembership(new SpaceMembership(userB, SpaceRole.CURATOR, organizationA));
    Space hr = new Space("HR", "HR docs", false, SpaceVisibility.PRIVATE, userB, organizationA);
    hr.addMembership(new SpaceMembership(userB, SpaceRole.ADMIN, organizationA));
    spaceRepository.saveAll(List.of(eng, hr));

    List<SpaceOverview> userASpaces = spaceService.listSpaces(userA, false);

    assertThat(userASpaces).hasSize(1);
    assertThat(userASpaces.getFirst().space().getName()).isEqualTo("Engineering");
    assertThat(roleOf(userASpaces.getFirst().space(), userA)).isEqualTo(SpaceRole.ADMIN);
  }

  @Test
  void listCountsAssignedLibrariesAndOnlyTheCallersOwnChats() {
    // #682: the overview card's figures line. Chats count only the caller's own (#525: chats are
    // private to their author, so another member's chats must neither show up in the figure nor
    // leak through it). Libraries follow listForSpace's rule: the ADMIN sees every association,
    // the plain MEMBER only the libraries they may read - the figure must not give away how many
    // are withheld (spaces-and-assets.md: "darf keine Anzahlen nennen").
    UUID userA = createUser(organizationA);
    UUID userB = createUser(organizationA);
    Space eng =
        new Space(
            "Engineering",
            "Engineering docs",
            false,
            SpaceVisibility.PRIVATE,
            userA,
            organizationA);
    eng.addMembership(new SpaceMembership(userA, SpaceRole.ADMIN, organizationA));
    eng.addMembership(new SpaceMembership(userB, SpaceRole.MEMBER, organizationA));
    Space empty =
        new Space(
            "Leer", "Noch ohne Inhalte", false, SpaceVisibility.PRIVATE, userA, organizationA);
    empty.addMembership(new SpaceMembership(userA, SpaceRole.ADMIN, organizationA));
    spaceRepository.saveAll(List.of(eng, empty));
    UUID libraryOne = createReadableLibrary(organizationA, userA);
    UUID libraryTwo = createReadableLibrary(organizationA, userB);
    associationRepository.saveAll(
        List.of(
            new SpaceAssetAssociation(eng.getId(), libraryOne, organizationA, userA),
            new SpaceAssetAssociation(eng.getId(), libraryTwo, organizationA, userA)));
    chatRepository.saveAll(
        List.of(
            new Chat(eng.getId(), userA, organizationA, "Erste Frage", true, Set.of()),
            new Chat(eng.getId(), userA, organizationA, "Zweite Frage", true, Set.of()),
            new Chat(eng.getId(), userA, organizationA, "Dritte Frage", true, Set.of()),
            new Chat(eng.getId(), userB, organizationA, "Fremde Frage", true, Set.of())));

    List<SpaceOverview> spaces = spaceService.listSpaces(userA, false);

    SpaceOverview engineering =
        spaces.stream()
            .filter(s -> s.space().getName().equals("Engineering"))
            .findFirst()
            .orElseThrow();
    assertThat(engineering.libraryCount()).isEqualTo(2);
    assertThat(engineering.chatCount()).isEqualTo(3);
    SpaceOverview leer =
        spaces.stream().filter(s -> s.space().getName().equals("Leer")).findFirst().orElseThrow();
    assertThat(leer.libraryCount()).isZero();
    assertThat(leer.chatCount()).isZero();

    List<SpaceOverview> userBSpaces = spaceService.listSpaces(userB, false);
    assertThat(userBSpaces).hasSize(1);
    assertThat(userBSpaces.getFirst().libraryCount())
        .as("MEMBER userB may read only the library they own")
        .isEqualTo(1);
    assertThat(userBSpaces.getFirst().chatCount()).isEqualTo(1);
  }

  @Test
  void detailsIncludeMemberCountAndCurrentUsersRole() {
    UUID owner = createUser(organizationA);
    UUID member = createUser(organizationA);
    Space space =
        new Space("Phoenix", "Project docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(member, SpaceRole.MEMBER, organizationA));
    Space saved = spaceRepository.save(space);

    Space response = spaceService.getSpace(saved.getId(), member, false);

    assertThat(response.getMemberships()).hasSize(2);
    assertThat(roleOf(response, member)).isEqualTo(SpaceRole.MEMBER);
    assertThat(roleCounts(response).get(SpaceRole.ADMIN)).isEqualTo(1L);
    assertThat(roleCounts(response).get(SpaceRole.MEMBER)).isEqualTo(1L);
  }

  @Test
  void deletingSpaceRemovesMemberships() {
    UUID owner = createUser(organizationA);
    UUID curator = createUser(organizationA);
    Space space =
        new Space("Company", "Company docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(curator, SpaceRole.CURATOR, organizationA));
    Space saved = spaceRepository.save(space);

    spaceService.deleteSpace(saved.getId(), owner, false);

    assertThat(spaceRepository.findById(saved.getId())).isEmpty();
    assertThat(membershipRepository.findBySpaceId(saved.getId())).isEmpty();
  }

  @Test
  void spaceWithAChatCannotBeDeleted() {
    // #525 review, finding 5: docs/features/spaces-and-assets.md#chats-sind-vor-fremder-
    // löschung-geschützt - a chat must survive its space being deleted, so the space itself must
    // not be deletable while it still contains one.
    UUID owner = createUser(organizationA);
    Space space =
        new Space("Company", "Company docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);
    chatRepository.save(
        new Chat(saved.getId(), owner, organizationA, "Meine Frage", true, Set.of()));

    assertThatThrownBy(() -> spaceService.deleteSpace(saved.getId(), owner, false))
        .isInstanceOf(ConflictException.class);
    assertThat(spaceRepository.findById(saved.getId())).isPresent();
  }

  @Test
  void deletingPersonalSpaceReturnsBadRequest() {
    UUID owner = createUser(organizationA);
    Space personal =
        new Space(
            "My Documents", "Private docs", true, SpaceVisibility.PRIVATE, owner, organizationA);
    personal.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(personal);

    assertThatThrownBy(() -> spaceService.deleteSpace(saved.getId(), owner, false))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void adminCanAddMemberAndCurator() {
    UUID owner = createUser(organizationA);
    UUID admin = createUser(organizationA);
    UUID member = createUser(organizationA);
    UUID curator = createUser(organizationA);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(admin, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    spaceService.addMember(saved.getId(), member, SpaceRole.MEMBER, admin);
    spaceService.addMember(saved.getId(), curator, SpaceRole.CURATOR, admin);

    Space reloaded = spaceRepository.findByIdWithMemberships(saved.getId()).orElseThrow();
    assertThat(reloaded.getMemberships()).hasSize(4);
    assertThat(reloaded.getMemberships())
        .filteredOn(m -> m.getUserId().equals(member))
        .extracting(SpaceMembership::getRole)
        .containsExactly(SpaceRole.MEMBER);
    assertThat(reloaded.getMemberships())
        .filteredOn(m -> m.getUserId().equals(curator))
        .extracting(SpaceMembership::getRole)
        .containsExactly(SpaceRole.CURATOR);
  }

  @Test
  void membersCanBeAddedToTheDefaultSpace() {
    // #333 removed the "no members in a personal space" rule along with SpaceKind. The default
    // space is an ordinary space in every respect but deletion: what protects the owner is not the
    // room, it is that private content stays private regardless of who else is a member.
    UUID owner = createUser(organizationA);
    Space defaultSpace =
        new Space("My Documents", "Private", true, SpaceVisibility.PRIVATE, owner, organizationA);
    defaultSpace.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(defaultSpace);

    spaceService.addMember(saved.getId(), createUser(organizationA), SpaceRole.MEMBER, owner);

    assertThat(spaceService.getSpace(saved.getId(), owner, false).getMemberships()).hasSize(2);
  }

  @Test
  void theDefaultSpaceCannotBeDeleted() {
    UUID owner = createUser(organizationA);
    Space defaultSpace =
        new Space("My Documents", "Private", true, SpaceVisibility.PRIVATE, owner, organizationA);
    defaultSpace.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(defaultSpace);

    assertThatThrownBy(() -> spaceService.deleteSpace(saved.getId(), owner, false))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void adminCannotRemoveOwner() {
    UUID owner = createUser(organizationA);
    UUID admin = createUser(organizationA);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(admin, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(() -> spaceService.removeMember(saved.getId(), owner, admin))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void ownerCanTransferOwnership() {
    UUID owner = createUser(organizationA);
    UUID admin = createUser(organizationA);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(admin, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    spaceService.transferOwnership(saved.getId(), admin, owner, false);

    Space reloaded = spaceRepository.findByIdWithMemberships(saved.getId()).orElseThrow();
    assertThat(reloaded.getOwnerId()).isEqualTo(admin);
  }

  @Test
  void nonOwnerCannotTransferOwnership() {
    UUID owner = createUser(organizationA);
    UUID admin = createUser(organizationA);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(admin, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(() -> spaceService.transferOwnership(saved.getId(), owner, admin, false))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void roleChangesAreImmediatelyEffective() {
    UUID owner = createUser(organizationA);
    UUID admin = createUser(organizationA);
    UUID member = createUser(organizationA);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(admin, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(member, SpaceRole.MEMBER, organizationA));
    Space saved = spaceRepository.save(space);

    spaceService.updateMemberRole(saved.getId(), member, SpaceRole.CURATOR, admin);
    Space details = spaceService.getSpace(saved.getId(), member, false);

    assertThat(roleOf(details, member)).isEqualTo(SpaceRole.CURATOR);
  }

  @Test
  void memberCannotChangeRoles() {
    UUID owner = createUser(organizationA);
    UUID member = createUser(organizationA);
    UUID target = createUser(organizationA);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(member, SpaceRole.MEMBER, organizationA));
    space.addMembership(new SpaceMembership(target, SpaceRole.CURATOR, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(
            () -> spaceService.updateMemberRole(saved.getId(), target, SpaceRole.MEMBER, member))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void requestCrossingOrganizationBoundaryIsRejected() {
    UUID owner = createUser(organizationA);
    UUID outsider = createUser(organizationB);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(() -> spaceService.getSpace(saved.getId(), outsider, false))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void requestCrossingOrganizationBoundaryIsRejectedEvenForSystemAdmin() {
    UUID owner = createUser(organizationA);
    UUID otherOrgAdmin = createUser(organizationB);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(() -> spaceService.getSpace(saved.getId(), otherOrgAdmin, true))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void ensureDefaultSpaceValidatesLikeCreateSpace() {
    UUID userId = createUser(organizationA);

    spaceService.ensureDefaultSpace(userId, organizationA);

    List<Space> spaces = spaceRepository.findDistinctByMembershipsUserId(userId);
    assertThat(spaces).hasSize(1);
    Space defaultSpace = spaces.getFirst();
    assertThat(defaultSpace.isDefault()).isTrue();
    assertThat(defaultSpace.getOrganizationId()).isEqualTo(organizationA);
    assertThat(defaultSpace.getOwnerId()).isEqualTo(userId);
  }

  @Test
  void ensureDefaultSpaceIsIdempotent() {
    UUID userId = createUser(organizationA);

    spaceService.ensureDefaultSpace(userId, organizationA);
    spaceService.ensureDefaultSpace(userId, organizationA);

    List<Space> spaces = spaceRepository.findDistinctByMembershipsUserId(userId);
    assertThat(spaces).hasSize(1);
  }

  @Test
  void addMemberRejectsAUserFromAnotherOrganization() {
    UUID owner = createUser(organizationA);
    UUID outsider = createUser(organizationB);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(
            () -> spaceService.addMember(saved.getId(), outsider, SpaceRole.MEMBER, owner))
        .isInstanceOf(NotFoundException.class);
    assertThat(membershipRepository.findBySpaceId(saved.getId())).hasSize(1);
  }

  @Test
  void addMemberRejectsANonExistentUserWithNotFoundInsteadOfAServerError() {
    UUID owner = createUser(organizationA);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(
            () -> spaceService.addMember(saved.getId(), UUID.randomUUID(), SpaceRole.MEMBER, owner))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void addMemberRejectsAddingToAnArchivedSpace() {
    // #613 review, finding 2: an archived space accepts no new content, and a new member counts
    // as new content in the specification's sense.
    UUID owner = createUser(organizationA);
    UUID newMember = createUser(organizationA);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);
    spaceService.archiveSpace(saved.getId(), owner, false);

    assertThatThrownBy(
            () -> spaceService.addMember(saved.getId(), newMember, SpaceRole.MEMBER, owner))
        .isInstanceOf(ConflictException.class);
    assertThat(membershipRepository.findBySpaceId(saved.getId())).hasSize(1);
  }

  @Test
  void createSpaceRejectsAnOwnerFromAnotherOrganizationEvenForSystemAdmin() {
    UUID admin = createUser(organizationA);
    UUID outsider = createUser(organizationB);
    SpaceCreation request = new SpaceCreation("Engineering", null, outsider, null, List.of(), null);

    assertThatThrownBy(() -> spaceService.createSpace(request, admin, true))
        .isInstanceOf(NotFoundException.class);
    assertThat(spaceRepository.findAll()).isEmpty();
  }

  @Test
  void createSpaceRejectsAnInitialMemberFromAnotherOrganization() {
    UUID admin = createUser(organizationA);
    UUID outsider = createUser(organizationB);
    SpaceCreation request =
        new SpaceCreation(
            "Engineering",
            null,
            null,
            null,
            List.of(new SpaceMemberSeed(outsider, SpaceRole.MEMBER)),
            null);

    assertThatThrownBy(() -> spaceService.createSpace(request, admin, true))
        .isInstanceOf(NotFoundException.class);
    assertThat(spaceRepository.findAll()).isEmpty();
  }

  @Test
  void adminCannotChangeTheOwnersRoleAwayFromAdmin() {
    UUID owner = createUser(organizationA);
    UUID admin = createUser(organizationA);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(admin, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(
            () -> spaceService.updateMemberRole(saved.getId(), owner, SpaceRole.MEMBER, admin))
        .isInstanceOf(ValidationException.class);

    Space reloaded = spaceRepository.findByIdWithMemberships(saved.getId()).orElseThrow();
    assertThat(reloaded.getMemberships())
        .filteredOn(m -> m.getUserId().equals(owner))
        .extracting(SpaceMembership::getRole)
        .containsExactly(SpaceRole.ADMIN);
  }

  @Test
  void systemAdminCanListMembersWithoutBeingAMember() {
    UUID owner = createUser(organizationA);
    UUID admin = createUser(organizationA);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    assertThat(spaceService.listMembers(saved.getId(), admin, true)).hasSize(1);
  }

  @Test
  void nonMemberCannotListMembers() {
    UUID owner = createUser(organizationA);
    UUID outsider = createUser(organizationA);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(() -> spaceService.listMembers(saved.getId(), outsider, false))
        .isInstanceOf(AccessDeniedException.class);
  }

  // #144: the full member list (identities and display names) is restricted to ADMIN, the owner
  // and system admins - a MEMBER or CURATOR must not be able to enumerate their fellow members via
  // either getSpace or listMembers, even though they already know they themselves are a member of
  // this space.

  @Test
  void ownerCanListMembersIncludingDisplayNames() {
    UUID owner = createUser(organizationA);
    UUID member = createUser(organizationA);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(member, SpaceRole.MEMBER, organizationA));
    Space saved = spaceRepository.save(space);

    List<SpaceMemberView> members = spaceService.listMembers(saved.getId(), owner, false);

    assertThat(members).hasSize(2);
    assertThat(members).extracting(SpaceMemberView::displayName).containsOnly("Test User");
  }

  @Test
  void memberCannotListMembers() {
    UUID owner = createUser(organizationA);
    UUID member = createUser(organizationA);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(member, SpaceRole.MEMBER, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(() -> spaceService.listMembers(saved.getId(), member, false))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void curatorCannotListMembers() {
    UUID owner = createUser(organizationA);
    UUID curator = createUser(organizationA);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(curator, SpaceRole.CURATOR, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(() -> spaceService.listMembers(saved.getId(), curator, false))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void newOwnerCanListMembersEvenWithoutAnAdminMembership() {
    // #674 review, blocker 2: transferOwnership only reassigns Space.ownerId - it never touches
    // the new owner's own SpaceMembership role. A space can therefore genuinely have an owner
    // whose own membership is MEMBER, and requireMemberListViewer must check the owner explicitly
    // rather than assume "owner implies ADMIN".
    UUID owner = createUser(organizationA);
    UUID newOwner = createUser(organizationA);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(newOwner, SpaceRole.MEMBER, organizationA));
    Space saved = spaceRepository.save(space);

    spaceService.transferOwnership(saved.getId(), newOwner, owner, false);

    assertThat(spaceService.listMembers(saved.getId(), newOwner, false)).hasSize(2);
  }

  @Test
  void getSpaceReportsAccurateRoleCountsRegardlessOfCallersRole() {
    // #144: the aggregated roleCounts derived from the returned Space are correct for every
    // caller - identities and display names are only reachable via listMembers, restricted to
    // ADMIN, owner and system admins.
    UUID owner = createUser(organizationA);
    UUID member = createUser(organizationA);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(member, SpaceRole.MEMBER, organizationA));
    Space saved = spaceRepository.save(space);

    Space asMember = spaceService.getSpace(saved.getId(), member, false);
    Space asOwner = spaceService.getSpace(saved.getId(), owner, false);

    assertThat(roleCounts(asMember).get(SpaceRole.ADMIN)).isEqualTo(1L);
    assertThat(roleCounts(asMember).get(SpaceRole.MEMBER)).isEqualTo(1L);
    assertThat(roleCounts(asOwner).get(SpaceRole.ADMIN)).isEqualTo(1L);
    assertThat(roleCounts(asOwner).get(SpaceRole.MEMBER)).isEqualTo(1L);
  }

  @Test
  void updateSpaceAppliesVisibilityInsteadOfSilentlyIgnoringIt() {
    UUID owner = createUser(organizationA);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    SpaceUpdate update = new SpaceUpdate("Team", "Team docs", SpaceVisibility.OPEN);
    Space response = spaceService.updateSpace(saved.getId(), update, owner, false);

    assertThat(response.getVisibility()).isEqualTo(SpaceVisibility.OPEN);
    Space reloaded = spaceRepository.findById(saved.getId()).orElseThrow();
    assertThat(reloaded.getVisibility()).isEqualTo(SpaceVisibility.OPEN);
  }

  @Test
  void updateSpaceKeepsVisibilityWhenNotProvided() {
    UUID owner = createUser(organizationA);
    Space space =
        new Space("Team", "Team docs", false, SpaceVisibility.DISCOVERABLE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    SpaceUpdate update = new SpaceUpdate("Team", "Team docs", null);
    spaceService.updateSpace(saved.getId(), update, owner, false);

    Space reloaded = spaceRepository.findById(saved.getId()).orElseThrow();
    assertThat(reloaded.getVisibility()).isEqualTo(SpaceVisibility.DISCOVERABLE);
  }

  @Test
  void createSpaceRejectsNameLongerThanTheAllowedLength() {
    UUID userId = createUser(organizationA);
    SpaceCreation request = new SpaceCreation("x".repeat(256), null, null, null, List.of(), null);

    assertThatThrownBy(() -> spaceService.createSpace(request, userId, false))
        .isInstanceOf(ValidationException.class);
  }

  // #543: archiving a space with a foreign chat - a Space mit fremden privaten Chats ist dauerhaft
  // unlöschbar.

  @Test
  void deletingSpaceWithAForeignChatMentionsArchivingInTheConflictMessage() {
    UUID owner = createUser(organizationA);
    UUID otherMember = createUser(organizationA);
    Space space =
        new Space("Company", "Company docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(otherMember, SpaceRole.MEMBER, organizationA));
    Space saved = spaceRepository.save(space);
    // The owner cannot see, let alone remove, a chat authored by a fellow member - the exact
    // situation that makes the space permanently undeletable without archiving.
    chatRepository.save(
        new Chat(saved.getId(), otherMember, organizationA, "Fremde Frage", true, Set.of()));

    assertThatThrownBy(() -> spaceService.deleteSpace(saved.getId(), owner, false))
        .isInstanceOf(ConflictException.class)
        .satisfies(
            ex -> {
              assertThat(ex.getMessage()).contains("Archivieren");
            });
  }

  @Test
  void ownerCanArchiveASpaceThatCannotBeDeletedBecauseOfAForeignChat() {
    UUID owner = createUser(organizationA);
    UUID otherMember = createUser(organizationA);
    Space space =
        new Space("Company", "Company docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(otherMember, SpaceRole.MEMBER, organizationA));
    Space saved = spaceRepository.save(space);
    Chat foreignChat =
        chatRepository.save(
            new Chat(saved.getId(), otherMember, organizationA, "Fremde Frage", true, Set.of()));

    Space archived = spaceService.archiveSpace(saved.getId(), owner, false);

    assertThat(archived.isArchived()).isTrue();
    assertThat(spaceRepository.findById(saved.getId())).isPresent();
    // The foreign chat is untouched - archiving never deletes content.
    assertThat(chatRepository.findById(foreignChat.getId())).isPresent();
  }

  @Test
  void archivingAnAlreadyArchivedSpaceIsIdempotent() {
    UUID owner = createUser(organizationA);
    Space space =
        new Space("Company", "Company docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);
    spaceService.archiveSpace(saved.getId(), owner, false);

    Space secondCall = spaceService.archiveSpace(saved.getId(), owner, false);

    assertThat(secondCall.isArchived()).isTrue();
  }

  @Test
  void onlyOwnerOrSystemAdminCanArchiveASpace() {
    UUID owner = createUser(organizationA);
    UUID otherMember = createUser(organizationA);
    Space space =
        new Space("Company", "Company docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(otherMember, SpaceRole.MEMBER, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(() -> spaceService.archiveSpace(saved.getId(), otherMember, false))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void theDefaultSpaceCannotBeArchived() {
    UUID owner = createUser(organizationA);
    Space defaultSpace =
        new Space("My Documents", "Private", true, SpaceVisibility.PRIVATE, owner, organizationA);
    defaultSpace.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(defaultSpace);

    assertThatThrownBy(() -> spaceService.archiveSpace(saved.getId(), owner, false))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void listSpacesHidesAnArchivedSpaceFromAMemberWithoutAChatOfTheirOwnInIt() {
    UUID owner = createUser(organizationA);
    UUID otherMember = createUser(organizationA);
    Space space =
        new Space("Company", "Company docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(otherMember, SpaceRole.MEMBER, organizationA));
    Space saved = spaceRepository.save(space);
    spaceService.archiveSpace(saved.getId(), owner, false);

    assertThat(spaceService.listSpaces(otherMember, false)).isEmpty();
  }

  @Test
  void listSpacesKeepsAnArchivedSpaceVisibleForAMemberWithAChatOfTheirOwnInIt() {
    // The whole point of archiving (#543): the space stays reachable for the author of a chat
    // nobody else - not even the owner - can see or remove.
    UUID owner = createUser(organizationA);
    UUID otherMember = createUser(organizationA);
    Space space =
        new Space("Company", "Company docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(otherMember, SpaceRole.MEMBER, organizationA));
    Space saved = spaceRepository.save(space);
    chatRepository.save(
        new Chat(saved.getId(), otherMember, organizationA, "Fremde Frage", true, Set.of()));
    spaceService.archiveSpace(saved.getId(), owner, false);

    List<SpaceOverview> visibleToAuthor = spaceService.listSpaces(otherMember, false);

    assertThat(visibleToAuthor).hasSize(1);
    assertThat(visibleToAuthor.getFirst().space().isArchived()).isTrue();
  }

  @Test
  void listSpacesKeepsAnArchivedSpaceVisibleForItsOwnerEvenWithoutAChatOfTheirOwnInIt() {
    // #613 review, finding 3: the typical #543 case is exactly this - the owner archives a space
    // because of a foreign chat they cannot see, and has no chat of their own in it either. There
    // is no unarchive endpoint, so if the space vanished from the owner's own list here, it would
    // become unreachable except by guessing its URL.
    UUID owner = createUser(organizationA);
    UUID otherMember = createUser(organizationA);
    Space space =
        new Space("Company", "Company docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(otherMember, SpaceRole.MEMBER, organizationA));
    Space saved = spaceRepository.save(space);
    chatRepository.save(
        new Chat(saved.getId(), otherMember, organizationA, "Fremde Frage", true, Set.of()));
    spaceService.archiveSpace(saved.getId(), owner, false);

    List<SpaceOverview> visibleToOwner = spaceService.listSpaces(owner, false);

    assertThat(visibleToOwner).hasSize(1);
    assertThat(visibleToOwner.getFirst().space().isArchived()).isTrue();
  }

  @Test
  void listSpacesKeepsAnArchivedSpaceVisibleForASystemAdminMember() {
    UUID owner = createUser(organizationA);
    UUID adminMember = createUser(organizationA);
    Space space =
        new Space("Company", "Company docs", false, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(adminMember, SpaceRole.MEMBER, organizationA));
    Space saved = spaceRepository.save(space);
    spaceService.archiveSpace(saved.getId(), owner, false);

    List<SpaceOverview> visibleToSystemAdmin = spaceService.listSpaces(adminMember, true);

    assertThat(visibleToSystemAdmin).hasSize(1);
    assertThat(visibleToSystemAdmin.getFirst().space().isArchived()).isTrue();
  }

  // #706 review, finding 4: libraryIds are associated in the same transaction as the space itself
  // - a library the creator cannot associate (here: does not exist) must roll the whole creation
  // back, not leave a half-created space with only some of the requested associations.
  @Test
  void createSpaceRollsBackEntirelyWhenOneOfTheRequestedLibraryIdsCannotBeAssociated() {
    UUID creator = createUser(organizationA);
    UUID readableLibrary = createReadableLibrary(organizationA, creator);
    UUID nonExistentLibrary = UUID.randomUUID();
    SpaceCreation request =
        new SpaceCreation(
            "Datenraum", null, null, null, null, List.of(readableLibrary, nonExistentLibrary));

    assertThatThrownBy(() -> spaceService.createSpace(request, creator, false))
        .isInstanceOf(io.opaa.common.NotFoundException.class);

    assertThat(spaceRepository.findDistinctByMembershipsUserId(creator)).isEmpty();
  }

  @Test
  void createSpaceAssociatesEveryRequestedLibraryAtomicallyOnSuccess() {
    UUID creator = createUser(organizationA);
    UUID libraryOne = createReadableLibrary(organizationA, creator);
    UUID libraryTwo = createReadableLibrary(organizationA, creator);
    SpaceCreation request =
        new SpaceCreation("Datenraum", null, null, null, null, List.of(libraryOne, libraryTwo));

    Space created = spaceService.createSpace(request, creator, false);

    List<UUID> associatedLibraryIds =
        jdbcTemplate.query(
            "SELECT library_id FROM space_asset_associations WHERE space_id = ?",
            (rs, rowNum) -> (UUID) rs.getObject("library_id"),
            created.getId());
    assertThat(associatedLibraryIds).containsExactlyInAnyOrder(libraryOne, libraryTwo);
  }
}
