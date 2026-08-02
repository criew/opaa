package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.TestcontainersConfiguration;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs against the real, versioned Liquibase schema ({@code spring.liquibase.enabled=true}, {@code
 * ddl-auto=none}), not Hibernate-generated DDL - see #288. {@code Space.ownerId} and {@code
 * SpaceMembership.userId} are plain {@code UUID} columns without {@code @ManyToOne}; Hibernate does
 * not create a foreign key for those (Liquibase's {@code fk_spaces_owner} / {@code
 * fk_space_memberships_user} do), so every owner/member id used here must be a real, persisted
 * {@link User}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "basic"})
@TestPropertySource(
    properties = "OPAA_AUTH_BASIC_SECRET=test-only-secret-not-used-for-anything-sensitive-1234")
@Testcontainers(disabledWithoutDocker = true)
class SpaceRepositoryTest {

  @Autowired private SpaceRepository spaceRepository;
  @Autowired private SpaceMembershipRepository spaceMembershipRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;

  private UUID org;

  @BeforeEach
  void cleanUp() {
    // Deliberately does not delete all organizations: Organization.DEFAULT_ID is seeded once by
    // Liquibase and other tests sharing this Spring context (e.g.
    // UserServicePersonalSpaceIntegrationTest) rely on that row existing (fk_users_organization).
    // Each test creates its own throwaway organization instead, scoped by a random id.
    spaceMembershipRepository.deleteAll();
    spaceRepository.deleteAll();
    userRepository.deleteAll();
    org = organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(org);
    return userRepository.save(user).getId();
  }

  @Test
  void findDistinctByMembershipsUserIdReturnsUserSpaces() {
    UUID userA = createUser();
    UUID userB = createUser();

    Space eng =
        new Space(
            "Engineering", "Engineering docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, userA, org);
    eng.addMembership(new SpaceMembership(userA, SpaceRole.ADMIN, org));
    eng.addMembership(new SpaceMembership(userB, SpaceRole.CURATOR, org));

    Space hr = new Space("HR", "HR docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, userB, org);
    hr.addMembership(new SpaceMembership(userB, SpaceRole.ADMIN, org));

    spaceRepository.saveAll(List.of(eng, hr));

    List<Space> userASpaces = spaceRepository.findDistinctByMembershipsUserId(userA);
    List<Space> userBSpaces = spaceRepository.findDistinctByMembershipsUserId(userB);

    assertThat(userASpaces).extracting(Space::getName).containsExactly("Engineering");
    assertThat(userBSpaces)
        .extracting(Space::getName)
        .containsExactlyInAnyOrder("Engineering", "HR");
  }

  @Test
  void findBySpaceIdReturnsMembersForSpace() {
    UUID owner = createUser();
    UUID curator = createUser();
    Space space =
        new Space(
            "Phoenix", "Project space", SpaceKind.PROJECT, SpaceVisibility.PRIVATE, owner, org);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, org));
    space.addMembership(new SpaceMembership(curator, SpaceRole.CURATOR, org));

    Space savedSpace = spaceRepository.save(space);

    List<SpaceMembership> members = spaceMembershipRepository.findBySpaceId(savedSpace.getId());

    assertThat(members).hasSize(2);
    assertThat(members)
        .extracting(SpaceMembership::getRole)
        .containsExactlyInAnyOrder(SpaceRole.ADMIN, SpaceRole.CURATOR);
  }

  @Test
  void deletingSpaceRemovesMemberships() {
    UUID owner = createUser();
    Space space =
        new Space(
            "Company", "Company-wide space", SpaceKind.TEAM, SpaceVisibility.PRIVATE, owner, org);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, org));

    Space savedSpace = spaceRepository.save(space);
    UUID spaceId = savedSpace.getId();

    assertThat(spaceMembershipRepository.findBySpaceId(spaceId)).hasSize(1);

    spaceRepository.delete(savedSpace);

    assertThat(spaceRepository.findById(spaceId)).isEmpty();
    assertThat(spaceMembershipRepository.findBySpaceId(spaceId)).isEmpty();
  }

  @Test
  void twoUsersCanEachOwnAProjectSpaceWithTheSameName() {
    UUID userA = createUser();
    UUID userB = createUser();
    Space projectA =
        new Space(
            "Phoenix", "User A's project", SpaceKind.PROJECT, SpaceVisibility.PRIVATE, userA, org);
    projectA.addMembership(new SpaceMembership(userA, SpaceRole.ADMIN, org));
    Space projectB =
        new Space(
            "Phoenix", "User B's project", SpaceKind.PROJECT, SpaceVisibility.PRIVATE, userB, org);
    projectB.addMembership(new SpaceMembership(userB, SpaceRole.ADMIN, org));

    List<Space> saved = spaceRepository.saveAll(List.of(projectA, projectB));

    assertThat(saved).hasSize(2);
    assertThat(spaceRepository.findAll())
        .filteredOn(space -> space.getName().equals("Phoenix"))
        .hasSize(2);
  }
}
