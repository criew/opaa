package io.opaa.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.dto.GroupListResponse;
import io.opaa.api.dto.GroupRequest;
import io.opaa.api.dto.GroupResponse;
import io.opaa.api.dto.GroupUpdateRequest;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@code @Transactional(NOT_SUPPORTED)} disables the transaction Spring Test would otherwise wrap
 * around each test method. Without it, every {@code GroupService} call below would join that single
 * outer test transaction instead of committing on its own, and {@code
 * GroupService#invalidateAfterCommit} would only fire once - when the outer transaction rolls back
 * at the end of the test, not when each individual service call actually completes. That would
 * silently defeat the very assertions this class makes about cache invalidation timing. Cleanup
 * that a rolled-back transaction would otherwise have given us for free is done explicitly in
 * {@link #cleanUp()} instead.
 */
@DataJpaTest
@Import({GroupService.class, GroupMembershipResolver.class})
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(
    properties = {"spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GroupServiceIntegrationTest {

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  @DynamicPropertySource
  static void configureDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupMembershipRepository membershipRepository;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private UserRepository userRepository;

  private UUID organizationA;
  private UUID organizationB;

  @BeforeEach
  void cleanUp() {
    membershipRepository.deleteAll();
    groupRepository.deleteAll();
    userRepository.deleteAll();
    organizationA = UUID.randomUUID();
    organizationB = UUID.randomUUID();
  }

  private UUID createUser(UUID organizationId) {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }

  @Test
  void createsAnAdHocGroup() {
    UUID admin = createUser(organizationA);
    GroupRequest request = new GroupRequest("Projektbeteiligte Phoenix").description("Ad hoc");

    GroupResponse created = groupService.createGroup(request, admin);

    assertThat(created.getKind()).isEqualTo(GroupKind.AD_HOC);
    assertThat(created.getName()).isEqualTo("Projektbeteiligte Phoenix");
    assertThat(created.getMemberCount()).isEqualTo(0);
  }

  @Test
  void renamesAGroup() {
    UUID admin = createUser(organizationA);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Old name", null, null, null);
    Group saved = groupRepository.save(group);

    GroupUpdateRequest request = new GroupUpdateRequest("New name").description("Updated");
    GroupResponse updated = groupService.updateGroup(saved.getId(), request, admin);

    assertThat(updated.getName()).isEqualTo("New name");
    assertThat(updated.getDescription()).isEqualTo("Updated");
  }

  @Test
  void cannotRenameAnOrgUnitGroup() {
    UUID admin = createUser(organizationA);
    Group group =
        new Group(organizationA, GroupKind.ORG_UNIT, "Referat 50", null, "directory-guid", null);
    Group saved = groupRepository.save(group);

    GroupUpdateRequest request = new GroupUpdateRequest("Renamed");
    assertThatThrownBy(() -> groupService.updateGroup(saved.getId(), request, admin))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void deletesAGroupAndRemovesMemberships() {
    UUID admin = createUser(organizationA);
    UUID member = createUser(organizationA);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Team", null, null, null);
    group.addMembership(new GroupMembership(member, organizationA));
    Group saved = groupRepository.save(group);

    groupService.deleteGroup(saved.getId(), admin);

    assertThat(groupRepository.findById(saved.getId())).isEmpty();
    assertThat(membershipRepository.findByGroupId(saved.getId())).isEmpty();
  }

  @Test
  void cannotDeleteAnOrgUnitGroup() {
    UUID admin = createUser(organizationA);
    Group group =
        new Group(organizationA, GroupKind.ORG_UNIT, "Referat 50", null, "directory-guid", null);
    Group saved = groupRepository.save(group);

    assertThatThrownBy(() -> groupService.deleteGroup(saved.getId(), admin))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
    assertThat(groupRepository.findById(saved.getId())).isPresent();
  }

  @Test
  void addsAndRemovesAMember() {
    UUID admin = createUser(organizationA);
    UUID member = createUser(organizationA);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Team", null, null, null);
    Group saved = groupRepository.save(group);

    groupService.addMember(saved.getId(), member, admin);
    Group afterAdd = groupRepository.findByIdWithMemberships(saved.getId()).orElseThrow();
    assertThat(afterAdd.getMemberships()).hasSize(1);

    groupService.removeMember(saved.getId(), member, admin);
    Group afterRemove = groupRepository.findByIdWithMemberships(saved.getId()).orElseThrow();
    assertThat(afterRemove.getMemberships()).isEmpty();
  }

  @Test
  void addingTheSameMemberTwiceIsRejectedWithConflict() {
    UUID admin = createUser(organizationA);
    UUID member = createUser(organizationA);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Team", null, null, null);
    Group saved = groupRepository.save(group);
    groupService.addMember(saved.getId(), member, admin);

    assertThatThrownBy(() -> groupService.addMember(saved.getId(), member, admin))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void addMemberRejectsAUserFromAnotherOrganization() {
    UUID admin = createUser(organizationA);
    UUID outsider = createUser(organizationB);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Team", null, null, null);
    Group saved = groupRepository.save(group);

    assertThatThrownBy(() -> groupService.addMember(saved.getId(), outsider, admin))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
    assertThat(membershipRepository.findByGroupId(saved.getId())).isEmpty();
  }

  @Test
  void groupsNeverCrossAnOrganizationBoundaryEvenForTheAdminOfAnotherOrganization() {
    UUID owner = createUser(organizationA);
    UUID adminOfOtherOrganization = createUser(organizationB);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Team", null, null, null);
    Group saved = groupRepository.save(group);

    assertThatThrownBy(() -> groupService.getGroup(saved.getId(), adminOfOtherOrganization))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));

    assertThat(owner).isNotNull();
  }

  @Test
  void listGroupsReturnsOnlyGroupsOfTheCallersOrganization() {
    UUID adminA = createUser(organizationA);
    createUser(organizationB);
    groupRepository.save(new Group(organizationA, GroupKind.AD_HOC, "Team A", null, null, null));
    groupRepository.save(new Group(organizationB, GroupKind.AD_HOC, "Team B", null, null, null));

    List<GroupListResponse> groups = groupService.listGroups(adminA);

    assertThat(groups).extracting(GroupListResponse::getName).containsExactly("Team A");
  }

  @Test
  void resolvingTheGroupsOfAUserIsCachedAndInvalidatedOnMembershipChange() {
    UUID admin = createUser(organizationA);
    UUID member = createUser(organizationA);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Team", null, null, null);
    Group saved = groupRepository.save(group);

    assertThat(membershipResolver.groupIdsForUser(member)).isEmpty();

    groupService.addMember(saved.getId(), member, admin);
    // Without invalidation, this would still return the empty set cached above.
    assertThat(membershipResolver.groupIdsForUser(member)).containsExactly(saved.getId());

    groupService.removeMember(saved.getId(), member, admin);
    // Without invalidation, this would still return the membership added above.
    assertThat(membershipResolver.groupIdsForUser(member)).isEmpty();
  }

  @Test
  void deletingAGroupInvalidatesTheCacheForItsFormerMembers() {
    UUID admin = createUser(organizationA);
    UUID member = createUser(organizationA);
    Group group = new Group(organizationA, GroupKind.AD_HOC, "Team", null, null, null);
    group.addMembership(new GroupMembership(member, organizationA));
    Group saved = groupRepository.save(group);
    assertThat(membershipResolver.groupIdsForUser(member)).containsExactly(saved.getId());

    groupService.deleteGroup(saved.getId(), admin);

    assertThat(membershipResolver.groupIdsForUser(member)).isEmpty();
  }
}
