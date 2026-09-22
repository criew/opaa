package io.opaa.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupRepository;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * HTTP-layer coverage of the two member-list routes of #1880: that they exist at all under the
 * paths the specification promises, that {@code offset}/{@code limit} carry their declared
 * defaults, and that the service's refusals arrive as {@code 403} and {@code 404}. The rule behind
 * those refusals is exercised at the service level in {@code
 * io.opaa.group.GrantedGroupMembersIntegrationTest}.
 */
@OpaaIntegrationTest
class GrantedGroupMembersControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private UserRepository userRepository;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;

  private final List<UUID> createdLibraryIds = new ArrayList<>();
  private final List<UUID> createdSpaceIds = new ArrayList<>();
  private final List<UUID> createdGroupIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();
  private UUID organization;

  @BeforeEach
  void provisionCallerAndMembers() throws Exception {
    createdLibraryIds.clear();
    createdSpaceIds.clear();
    createdGroupIds.clear();
    createdUserIds.clear();
    // The first request provisions the dev user; its organization is the one everything below
    // lives in - the class never touches rows of another one.
    mockMvc.perform(get("/api/v1/spaces").with(devUser())).andExpect(status().isOk());
    organization =
        jdbcTemplate.queryForObject(
            "SELECT organization_id FROM users WHERE email = ?", UUID.class, "dev-user@opaa.local");
  }

  @AfterEach
  void removeOwnRows() {
    for (UUID groupId : createdGroupIds) {
      jdbcTemplate.update("DELETE FROM audit_log WHERE object_id = ?", groupId.toString());
      jdbcTemplate.update("DELETE FROM asset_grant_history WHERE subject_group_id = ?", groupId);
      jdbcTemplate.update("DELETE FROM group_membership_history WHERE group_id = ?", groupId);
    }
    for (UUID spaceId : createdSpaceIds) {
      jdbcTemplate.update("DELETE FROM space_membership_history WHERE space_id = ?", spaceId);
      jdbcTemplate.update("DELETE FROM space_memberships WHERE space_id = ?", spaceId);
      jdbcTemplate.update(
          "DELETE FROM asset_ownership_history WHERE asset_type = 'SPACE' AND asset_id = ?",
          spaceId);
      jdbcTemplate.update("DELETE FROM spaces WHERE id = ?", spaceId);
    }
    ownLibraryFixtures.removeLibraries(createdLibraryIds.toArray(new UUID[0]));
    for (UUID groupId : createdGroupIds) {
      jdbcTemplate.update("DELETE FROM group_memberships WHERE group_id = ?", groupId);
      jdbcTemplate.update("DELETE FROM groups WHERE id = ?", groupId);
    }
    for (UUID userId : createdUserIds) {
      jdbcTemplate.update("DELETE FROM group_membership_history WHERE user_id = ?", userId);
      jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }
  }

  @Test
  void theLibraryRouteAnswersTheGroupsMembersWithItsDeclaredDefaults() throws Exception {
    UUID group = createGroupOfFive();
    String library = createLibrary(devUser());
    grantTo(library, group, devUser());

    mockMvc
        .perform(
            get("/api/v1/libraries/" + library + "/grants/groups/" + group + "/members")
                .with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.groupId").value(group.toString()))
        .andExpect(jsonPath("$.protectedGroup").value(false))
        .andExpect(jsonPath("$.smallGroup").value(false))
        .andExpect(jsonPath("$.activeMemberCount").value(5))
        // No offset and no limit in the request: the declared defaults (0 and 50) carry the whole
        // group, so a missing parameter is never an empty answer.
        .andExpect(jsonPath("$.members.length()").value(5))
        .andExpect(jsonPath("$.members[0].displayName").value("Anna Bauer"));

    mockMvc
        .perform(
            get("/api/v1/libraries/" + library + "/grants/groups/" + group + "/members")
                .param("offset", "4")
                .param("limit", "2")
                .with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members.length()").value(1))
        .andExpect(jsonPath("$.members[0].displayName").value("Emil Fried"));
  }

  @Test
  void theLibraryRouteMapsTheServicesRefusalsTo403And404() throws Exception {
    UUID group = createGroupOfFive();
    // Owned by the system administrator, so the plain dev user holds exactly the VIEWER grant
    // below and nothing else - the case that is "forbidden" rather than "not found".
    String library = createLibrary(devAdmin());
    grantTo(library, group, devAdmin());
    mockMvc
        .perform(
            post("/api/v1/libraries/" + library + "/grants")
                .with(devAdmin())
                .content(
                    "{\"subjectType\":\"USER\",\"subjectId\":\""
                        + userIdOf("dev-user@opaa.local")
                        + "\",\"role\":\"VIEWER\"}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            get("/api/v1/libraries/" + library + "/grants/groups/" + group + "/members")
                .with(devUser()))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            get("/api/v1/libraries/" + library + "/grants/groups/" + UUID.randomUUID() + "/members")
                .with(devAdmin()))
        .andExpect(status().isNotFound());
  }

  @Test
  void theSpaceRouteAnswersForAGroupItAdmittedAnd404ForOneItDidNot() throws Exception {
    UUID group = createGroupOfFive();
    UUID other = createGroupOfFive();
    String space = createSpace(devUser());
    mockMvc
        .perform(
            post("/api/v1/spaces/" + space + "/members")
                .with(devUser())
                .content(
                    "{\"subjectType\":\"GROUP\",\"subjectId\":\""
                        + group
                        + "\",\"role\":\"MEMBER\"}"))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            get("/api/v1/spaces/" + space + "/members/groups/" + group + "/members")
                .with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeMemberCount").value(5))
        .andExpect(jsonPath("$.members.length()").value(5));

    mockMvc
        .perform(
            get("/api/v1/spaces/" + space + "/members/groups/" + other + "/members")
                .with(devUser()))
        .andExpect(status().isNotFound());
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private UUID createGroupOfFive() {
    Group group = Group.internal(organization, "Referat 50", null, null);
    group.release(true);
    List<UUID> memberIds =
        List.of(
            createUser("Anna Bauer"),
            createUser("Bert Conrad"),
            createUser("Clara Dorn"),
            createUser("Dora Erle"),
            createUser("Emil Fried"));
    for (UUID memberId : memberIds) {
      group.addMembership(new GroupMembership(memberId, organization));
    }
    UUID id = groupRepository.save(group).getId();
    membershipResolver.invalidateUsers(memberIds);
    createdGroupIds.add(id);
    return id;
  }

  private UUID createUser(String displayName) {
    User user =
        new User(
            UUID.randomUUID().toString(),
            "test-issuer",
            UUID.randomUUID() + "@example.com",
            displayName);
    user.setOrganizationId(organization);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  private UUID userIdOf(String email) {
    return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
  }

  private String createLibrary(RequestPostProcessor caller) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/libraries")
                    .with(caller)
                    .content("{ \"name\": \"Mitgliederliste Test\", \"sourceType\": \"UPLOAD\" }"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String id = JsonPath.read(response, "$.id");
    createdLibraryIds.add(UUID.fromString(id));
    return id;
  }

  private String createSpace(RequestPostProcessor caller) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/spaces")
                    .with(caller)
                    .content("{ \"name\": \"Mitgliederliste Test\" }"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String id = JsonPath.read(response, "$.id");
    createdSpaceIds.add(UUID.fromString(id));
    return id;
  }

  private void grantTo(String libraryId, UUID groupId, RequestPostProcessor caller)
      throws Exception {
    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/grants")
                .with(caller)
                .content(
                    "{\"subjectType\":\"GROUP\",\"subjectId\":\""
                        + groupId
                        + "\",\"role\":\"VIEWER\"}"))
        .andExpect(status().isOk());
  }

  private RequestPostProcessor devUser() {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, "dev-user");
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }

  private RequestPostProcessor devAdmin() {
    return request -> {
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }
}
