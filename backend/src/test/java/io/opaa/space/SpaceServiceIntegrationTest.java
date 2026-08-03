package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.TestcontainersConfiguration;
import io.opaa.api.dto.SpaceListResponse;
import io.opaa.api.dto.SpaceMemberRequest;
import io.opaa.api.dto.SpaceRequest;
import io.opaa.api.dto.SpaceResponse;
import io.opaa.api.dto.SpaceUpdateRequest;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs against a real Postgres database with the real, versioned Liquibase schema applied ({@code
 * spring.liquibase.enabled=true}, {@code ddl-auto=none}), not Hibernate-generated DDL - see #288.
 * {@code Space.ownerId}, {@code SpaceMembership.userId} and every {@code organizationId} are plain
 * {@code UUID} columns without {@code @ManyToOne}; Hibernate does not create foreign keys for
 * those, Liquibase does ({@code fk_spaces_owner}, {@code fk_space_memberships_user}, {@code
 * fk_spaces_organization}, {@code fk_space_memberships_organization}). Every test therefore creates
 * real {@link Organization} and {@link User} rows instead of using bare random UUIDs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "basic"})
@TestPropertySource(
    properties = "OPAA_AUTH_BASIC_SECRET=test-only-secret-not-used-for-anything-sensitive-1234")
@Testcontainers(disabledWithoutDocker = true)
class SpaceServiceIntegrationTest {

  @Autowired private SpaceService spaceService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private SpaceMembershipRepository membershipRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;

  private UUID organizationA;
  private UUID organizationB;

  @BeforeEach
  void cleanUp() {
    // Deliberately does not delete all organizations: Organization.DEFAULT_ID is seeded once by
    // Liquibase and other tests sharing this Spring context (e.g.
    // UserServicePersonalSpaceIntegrationTest) rely on that row existing (fk_users_organization).
    // Each test creates its own throwaway organizations instead, scoped by random ids, and removes
    // them again in tearDown() - see tearDown() below.
    membershipRepository.deleteAll();
    spaceRepository.deleteAll();
    // #201: fk_knowledge_libraries_owner_user also references users now, not just fk_spaces_owner
    // - a leftover personal library from another test class sharing this context (e.g.
    // UserServicePersonalSpaceIntegrationTest, which has no @AfterEach) would otherwise block
    // userRepository.deleteAll() below with a RESTRICT violation on that unrelated user. Never
    // touches the one seeded SYSTEM library.
    libraryRepository.deleteAll(
        libraryRepository.findAll().stream().filter(l -> !l.isSystemLibrary()).toList());
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
    membershipRepository.deleteAll();
    spaceRepository.deleteAll();
    libraryRepository.deleteAll(
        libraryRepository.findAll().stream().filter(l -> !l.isSystemLibrary()).toList());
    userRepository.deleteAll();
    organizationRepository.deleteAllById(List.of(organizationA, organizationB));
  }

  private UUID createUser(UUID organizationId) {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }

  @Test
  void systemAdminCanCreateTeamSpace() {
    UUID adminUserId = createUser(organizationA);
    UUID ownerId = createUser(organizationA);
    UUID curatorId = createUser(organizationA);
    SpaceRequest request =
        new SpaceRequest("Engineering", SpaceKind.TEAM)
            .description("Engineering docs")
            .ownerId(ownerId)
            .initialMembers(List.of(new SpaceMemberRequest(curatorId, SpaceRole.CURATOR)));

    SpaceResponse created = spaceService.createSpace(request, adminUserId, true);

    assertThat(created.getKind()).isEqualTo(SpaceKind.TEAM);
    assertThat(created.getName()).isEqualTo("Engineering");
    assertThat(created.getOwnerId()).isEqualTo(ownerId);
    assertThat(created.getMemberCount()).isEqualTo(2);
    assertThat(created.getRoleCounts().get("ADMIN")).isEqualTo(1);
    assertThat(created.getRoleCounts().get("CURATOR")).isEqualTo(1);
  }

  @Test
  void nonSystemAdminCannotCreateTeamSpace() {
    UUID userId = createUser(organizationA);
    SpaceRequest request =
        new SpaceRequest("Engineering", SpaceKind.TEAM)
            .description("Engineering docs")
            .initialMembers(List.of());

    assertThatThrownBy(() -> spaceService.createSpace(request, userId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void anyUserCanCreateAProjectSpace() {
    UUID userId = createUser(organizationA);
    SpaceRequest request =
        new SpaceRequest("Phoenix", SpaceKind.PROJECT)
            .description("My project")
            .initialMembers(List.of());

    SpaceResponse created = spaceService.createSpace(request, userId, false);

    assertThat(created.getKind()).isEqualTo(SpaceKind.PROJECT);
    assertThat(created.getOwnerId()).isEqualTo(userId);
  }

  @Test
  void twoUsersCanEachOwnAProjectSpaceWithTheSameName() {
    UUID userA = createUser(organizationA);
    UUID userB = createUser(organizationA);
    SpaceRequest requestA =
        new SpaceRequest("Phoenix", SpaceKind.PROJECT).initialMembers(List.of());
    SpaceRequest requestB =
        new SpaceRequest("Phoenix", SpaceKind.PROJECT).initialMembers(List.of());

    SpaceResponse createdA = spaceService.createSpace(requestA, userA, false);
    SpaceResponse createdB = spaceService.createSpace(requestB, userB, false);

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
            SpaceKind.TEAM,
            SpaceVisibility.PRIVATE,
            userA,
            organizationA);
    eng.addMembership(new SpaceMembership(userA, SpaceRole.ADMIN, organizationA));
    eng.addMembership(new SpaceMembership(userB, SpaceRole.CURATOR, organizationA));
    Space hr =
        new Space("HR", "HR docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, userB, organizationA);
    hr.addMembership(new SpaceMembership(userB, SpaceRole.ADMIN, organizationA));
    spaceRepository.saveAll(List.of(eng, hr));

    List<SpaceListResponse> userASpaces = spaceService.listSpaces(userA);

    assertThat(userASpaces).hasSize(1);
    assertThat(userASpaces.getFirst().getName()).isEqualTo("Engineering");
    assertThat(userASpaces.getFirst().getUserRole()).isEqualTo(SpaceRole.ADMIN);
  }

  @Test
  void detailsIncludeMemberCountAndCurrentUsersRole() {
    UUID owner = createUser(organizationA);
    UUID member = createUser(organizationA);
    Space space =
        new Space(
            "Phoenix",
            "Project docs",
            SpaceKind.PROJECT,
            SpaceVisibility.PRIVATE,
            owner,
            organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(member, SpaceRole.MEMBER, organizationA));
    Space saved = spaceRepository.save(space);

    SpaceResponse response = spaceService.getSpace(saved.getId(), member, false);

    assertThat(response.getMemberCount()).isEqualTo(2);
    assertThat(response.getUserRole()).isEqualTo(SpaceRole.MEMBER);
    assertThat(response.getRoleCounts().get("ADMIN")).isEqualTo(1);
    assertThat(response.getRoleCounts().get("MEMBER")).isEqualTo(1);
  }

  @Test
  void deletingSpaceRemovesMemberships() {
    UUID owner = createUser(organizationA);
    UUID curator = createUser(organizationA);
    Space space =
        new Space(
            "Company",
            "Company docs",
            SpaceKind.TEAM,
            SpaceVisibility.PRIVATE,
            owner,
            organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(curator, SpaceRole.CURATOR, organizationA));
    Space saved = spaceRepository.save(space);

    spaceService.deleteSpace(saved.getId(), owner, false);

    assertThat(spaceRepository.findById(saved.getId())).isEmpty();
    assertThat(membershipRepository.findBySpaceId(saved.getId())).isEmpty();
  }

  @Test
  void deletingPersonalSpaceReturnsBadRequest() {
    UUID owner = createUser(organizationA);
    Space personal =
        new Space(
            "My Documents",
            "Private docs",
            SpaceKind.PERSONAL,
            SpaceVisibility.PRIVATE,
            owner,
            organizationA);
    personal.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(personal);

    assertThatThrownBy(() -> spaceService.deleteSpace(saved.getId(), owner, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void adminCanAddMemberAndCurator() {
    UUID owner = createUser(organizationA);
    UUID admin = createUser(organizationA);
    UUID member = createUser(organizationA);
    UUID curator = createUser(organizationA);
    Space space =
        new Space(
            "Team", "Team docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, owner, organizationA);
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
  void cannotAddMembersToPersonalSpace() {
    UUID owner = createUser(organizationA);
    Space personal =
        new Space(
            "My Documents",
            "Private",
            SpaceKind.PERSONAL,
            SpaceVisibility.PRIVATE,
            owner,
            organizationA);
    personal.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(personal);

    assertThatThrownBy(
            () ->
                spaceService.addMember(
                    saved.getId(), createUser(organizationA), SpaceRole.MEMBER, owner))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void adminCannotRemoveOwner() {
    UUID owner = createUser(organizationA);
    UUID admin = createUser(organizationA);
    Space space =
        new Space(
            "Team", "Team docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(admin, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(() -> spaceService.removeMember(saved.getId(), owner, admin))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void ownerCanTransferOwnership() {
    UUID owner = createUser(organizationA);
    UUID admin = createUser(organizationA);
    Space space =
        new Space(
            "Team", "Team docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, owner, organizationA);
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
        new Space(
            "Team", "Team docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(admin, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(() -> spaceService.transferOwnership(saved.getId(), owner, admin, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void roleChangesAreImmediatelyEffective() {
    UUID owner = createUser(organizationA);
    UUID admin = createUser(organizationA);
    UUID member = createUser(organizationA);
    Space space =
        new Space(
            "Team", "Team docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(admin, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(member, SpaceRole.MEMBER, organizationA));
    Space saved = spaceRepository.save(space);

    spaceService.updateMemberRole(saved.getId(), member, SpaceRole.CURATOR, admin);
    SpaceResponse details = spaceService.getSpace(saved.getId(), member, false);

    assertThat(details.getUserRole()).isEqualTo(SpaceRole.CURATOR);
  }

  @Test
  void memberCannotChangeRoles() {
    UUID owner = createUser(organizationA);
    UUID member = createUser(organizationA);
    UUID target = createUser(organizationA);
    Space space =
        new Space(
            "Team", "Team docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(member, SpaceRole.MEMBER, organizationA));
    space.addMembership(new SpaceMembership(target, SpaceRole.CURATOR, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(
            () -> spaceService.updateMemberRole(saved.getId(), target, SpaceRole.MEMBER, member))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void requestCrossingOrganizationBoundaryIsRejected() {
    UUID owner = createUser(organizationA);
    UUID outsider = createUser(organizationB);
    Space space =
        new Space(
            "Team", "Team docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(() -> spaceService.getSpace(saved.getId(), outsider, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void requestCrossingOrganizationBoundaryIsRejectedEvenForSystemAdmin() {
    UUID owner = createUser(organizationA);
    UUID otherOrgAdmin = createUser(organizationB);
    Space space =
        new Space(
            "Team", "Team docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(() -> spaceService.getSpace(saved.getId(), otherOrgAdmin, true))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void ensurePersonalSpaceValidatesLikeCreateSpace() {
    UUID userId = createUser(organizationA);

    spaceService.ensurePersonalSpace(userId, organizationA);

    List<Space> spaces = spaceRepository.findDistinctByMembershipsUserId(userId);
    assertThat(spaces).hasSize(1);
    Space personal = spaces.getFirst();
    assertThat(personal.getKind()).isEqualTo(SpaceKind.PERSONAL);
    assertThat(personal.getOrganizationId()).isEqualTo(organizationA);
    assertThat(personal.getOwnerId()).isEqualTo(userId);
  }

  @Test
  void ensurePersonalSpaceIsIdempotent() {
    UUID userId = createUser(organizationA);

    spaceService.ensurePersonalSpace(userId, organizationA);
    spaceService.ensurePersonalSpace(userId, organizationA);

    List<Space> spaces = spaceRepository.findDistinctByMembershipsUserId(userId);
    assertThat(spaces).hasSize(1);
  }

  @Test
  void addMemberRejectsAUserFromAnotherOrganization() {
    UUID owner = createUser(organizationA);
    UUID outsider = createUser(organizationB);
    Space space =
        new Space(
            "Team", "Team docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(
            () -> spaceService.addMember(saved.getId(), outsider, SpaceRole.MEMBER, owner))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
    assertThat(membershipRepository.findBySpaceId(saved.getId())).hasSize(1);
  }

  @Test
  void addMemberRejectsANonExistentUserWithNotFoundInsteadOfAServerError() {
    UUID owner = createUser(organizationA);
    Space space =
        new Space(
            "Team", "Team docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(
            () -> spaceService.addMember(saved.getId(), UUID.randomUUID(), SpaceRole.MEMBER, owner))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void createSpaceRejectsAnOwnerFromAnotherOrganizationEvenForSystemAdmin() {
    UUID admin = createUser(organizationA);
    UUID outsider = createUser(organizationB);
    SpaceRequest request =
        new SpaceRequest("Engineering", SpaceKind.TEAM).ownerId(outsider).initialMembers(List.of());

    assertThatThrownBy(() -> spaceService.createSpace(request, admin, true))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
    assertThat(spaceRepository.findAll()).isEmpty();
  }

  @Test
  void createSpaceRejectsAnInitialMemberFromAnotherOrganization() {
    UUID admin = createUser(organizationA);
    UUID outsider = createUser(organizationB);
    SpaceRequest request =
        new SpaceRequest("Engineering", SpaceKind.TEAM)
            .initialMembers(List.of(new SpaceMemberRequest(outsider, SpaceRole.MEMBER)));

    assertThatThrownBy(() -> spaceService.createSpace(request, admin, true))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
    assertThat(spaceRepository.findAll()).isEmpty();
  }

  @Test
  void adminCannotChangeTheOwnersRoleAwayFromAdmin() {
    UUID owner = createUser(organizationA);
    UUID admin = createUser(organizationA);
    Space space =
        new Space(
            "Team", "Team docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    space.addMembership(new SpaceMembership(admin, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(
            () -> spaceService.updateMemberRole(saved.getId(), owner, SpaceRole.MEMBER, admin))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));

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
        new Space(
            "Team", "Team docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    assertThat(spaceService.listMembers(saved.getId(), admin, true)).hasSize(1);
  }

  @Test
  void nonMemberCannotListMembers() {
    UUID owner = createUser(organizationA);
    UUID outsider = createUser(organizationA);
    Space space =
        new Space(
            "Team", "Team docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    assertThatThrownBy(() -> spaceService.listMembers(saved.getId(), outsider, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void updateSpaceAppliesVisibilityInsteadOfSilentlyIgnoringIt() {
    UUID owner = createUser(organizationA);
    Space space =
        new Space(
            "Team", "Team docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, owner, organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    SpaceUpdateRequest request =
        new SpaceUpdateRequest("Team").description("Team docs").visibility(SpaceVisibility.OPEN);
    SpaceResponse response = spaceService.updateSpace(saved.getId(), request, owner, false);

    assertThat(response.getVisibility()).isEqualTo(SpaceVisibility.OPEN);
    Space reloaded = spaceRepository.findById(saved.getId()).orElseThrow();
    assertThat(reloaded.getVisibility()).isEqualTo(SpaceVisibility.OPEN);
  }

  @Test
  void updateSpaceKeepsVisibilityWhenNotProvided() {
    UUID owner = createUser(organizationA);
    Space space =
        new Space(
            "Team",
            "Team docs",
            SpaceKind.TEAM,
            SpaceVisibility.DISCOVERABLE,
            owner,
            organizationA);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, organizationA));
    Space saved = spaceRepository.save(space);

    SpaceUpdateRequest request = new SpaceUpdateRequest("Team").description("Team docs");
    spaceService.updateSpace(saved.getId(), request, owner, false);

    Space reloaded = spaceRepository.findById(saved.getId()).orElseThrow();
    assertThat(reloaded.getVisibility()).isEqualTo(SpaceVisibility.DISCOVERABLE);
  }

  @Test
  void createSpaceRejectsNameLongerThanTheAllowedLength() {
    UUID userId = createUser(organizationA);
    SpaceRequest request =
        new SpaceRequest("x".repeat(256), SpaceKind.PROJECT).initialMembers(List.of());

    assertThatThrownBy(() -> spaceService.createSpace(request, userId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }
}
