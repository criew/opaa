package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@TestPropertySource(
    properties = {"spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
class SpaceRepositoryTest {

  private static final UUID ORG = UUID.randomUUID();

  @Autowired private SpaceRepository spaceRepository;
  @Autowired private SpaceMembershipRepository spaceMembershipRepository;

  @Test
  void findDistinctByMembershipsUserIdReturnsUserSpaces() {
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();

    Space eng =
        new Space(
            "Engineering", "Engineering docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, userA, ORG);
    eng.addMembership(new SpaceMembership(userA, SpaceRole.ADMIN, ORG));
    eng.addMembership(new SpaceMembership(userB, SpaceRole.CURATOR, ORG));

    Space hr = new Space("HR", "HR docs", SpaceKind.TEAM, SpaceVisibility.PRIVATE, userB, ORG);
    hr.addMembership(new SpaceMembership(userB, SpaceRole.ADMIN, ORG));

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
    UUID owner = UUID.randomUUID();
    UUID curator = UUID.randomUUID();
    Space space =
        new Space(
            "Phoenix", "Project space", SpaceKind.PROJECT, SpaceVisibility.PRIVATE, owner, ORG);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, ORG));
    space.addMembership(new SpaceMembership(curator, SpaceRole.CURATOR, ORG));

    Space savedSpace = spaceRepository.save(space);

    List<SpaceMembership> members = spaceMembershipRepository.findBySpaceId(savedSpace.getId());

    assertThat(members).hasSize(2);
    assertThat(members)
        .extracting(SpaceMembership::getRole)
        .containsExactlyInAnyOrder(SpaceRole.ADMIN, SpaceRole.CURATOR);
  }

  @Test
  void deletingSpaceRemovesMemberships() {
    UUID owner = UUID.randomUUID();
    Space space =
        new Space(
            "Company", "Company-wide space", SpaceKind.TEAM, SpaceVisibility.PRIVATE, owner, ORG);
    space.addMembership(new SpaceMembership(owner, SpaceRole.ADMIN, ORG));

    Space savedSpace = spaceRepository.save(space);
    UUID spaceId = savedSpace.getId();

    assertThat(spaceMembershipRepository.findBySpaceId(spaceId)).hasSize(1);

    spaceRepository.delete(savedSpace);

    assertThat(spaceRepository.findById(spaceId)).isEmpty();
    assertThat(spaceMembershipRepository.findBySpaceId(spaceId)).isEmpty();
  }

  @Test
  void twoUsersCanEachOwnAProjectSpaceWithTheSameName() {
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    Space projectA =
        new Space(
            "Phoenix", "User A's project", SpaceKind.PROJECT, SpaceVisibility.PRIVATE, userA, ORG);
    projectA.addMembership(new SpaceMembership(userA, SpaceRole.ADMIN, ORG));
    Space projectB =
        new Space(
            "Phoenix", "User B's project", SpaceKind.PROJECT, SpaceVisibility.PRIVATE, userB, ORG);
    projectB.addMembership(new SpaceMembership(userB, SpaceRole.ADMIN, ORG));

    List<Space> saved = spaceRepository.saveAll(List.of(projectA, projectB));

    assertThat(saved).hasSize(2);
    assertThat(spaceRepository.findAll())
        .filteredOn(space -> space.getName().equals("Phoenix"))
        .hasSize(2);
  }
}
