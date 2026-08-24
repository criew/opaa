package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.TestcontainersConfiguration;
import io.opaa.api.dto.GroupListResponse;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupCreation;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.group.GroupRepository;
import io.opaa.group.GroupService;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises {@link GroupResponseMapper#toListResponses} the same way the controller does: called on
 * the result of {@link GroupService#listGroups}/{@link GroupService#listMyGroups} after those
 * methods (and their {@code @Transactional} boundary) have already returned - {@code open-in-view:
 * false} means no Hibernate session is left open past that point. If either service method returned
 * {@link Group} entities whose {@code memberships} collection was not eagerly fetched, reading it
 * here throws {@code LazyInitializationException} instead of returning a response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
class GroupResponseMapperIntegrationTest {

  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private final List<UUID> createdUserIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    createdUserIds.clear();
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();
  }

  @AfterEach
  void tearDown() {
    groupRepository.deleteAll(
        groupRepository.findAll().stream()
            .filter(g -> g.getOrganizationId().equals(organizationId))
            .toList());
    // #238: group_membership_history.user_id is ON DELETE RESTRICT (migration 018) - addMember
    // writes one row per added member, so those rows must be purged before the users below can go
    // (mirrors GroupServiceIntegrationTest#tearDown).
    membershipHistoryRepository.deleteByUserIdIn(createdUserIds);
    userRepository.deleteAllById(createdUserIds);
    // #392: GroupService writes audit_log rows for GROUP_CREATED/GROUP_MEMBER_ADDED, and
    // fk_audit_log_organization is ON DELETE RESTRICT (migration 017) - mirrors
    // GroupServiceIntegrationTest#tearDown.
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  private CurrentUser currentUserOf(UUID userId) {
    User user = userRepository.findById(userId).orElseThrow();
    return new CurrentUser(
        user.getId(), user.getOrganizationId(), user.getSystemRole(), user.getDisplayName());
  }

  @Test
  void listGroupsFollowedByTheMapperDoesNotThrowAndReflectsTheMemberCount() {
    UUID admin = createUser();
    UUID member = createUser();
    CurrentUser adminCaller = currentUserOf(admin);
    Group created = groupService.createGroup(new GroupCreation("Team", null), adminCaller).group();
    groupService.addMember(created.getId(), member, adminCaller);

    List<Group> groups = groupService.listGroups(adminCaller);
    List<GroupListResponse> responses = GroupResponseMapper.toListResponses(groups);

    assertThat(responses).extracting(GroupListResponse::getName).containsExactly("Team");
    assertThat(responses.get(0).getMemberCount()).isEqualTo(1);
  }

  @Test
  void listMyGroupsFollowedByTheMapperDoesNotThrowAndReflectsTheMemberCount() {
    UUID admin = createUser();
    UUID member = createUser();
    CurrentUser adminCaller = currentUserOf(admin);
    Group created = groupService.createGroup(new GroupCreation("Team", null), adminCaller).group();
    groupService.addMember(created.getId(), member, adminCaller);
    groupService.addMember(created.getId(), admin, adminCaller);

    List<Group> groups = groupService.listMyGroups(adminCaller);
    List<GroupListResponse> responses = GroupResponseMapper.toListResponses(groups);

    assertThat(responses).extracting(GroupListResponse::getName).containsExactly("Team");
    assertThat(responses.get(0).getMemberCount()).isEqualTo(2);
  }
}
