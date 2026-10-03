package io.opaa.space.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
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
 * HTTP-layer coverage of creating a space with associations of every asset type in one call, and of
 * the rule that an association the caller cannot read leaves no trace in the space's answers -
 * whatever the caller's role there (ADR-0039, Entscheidung 2).
 */
@OpaaIntegrationTest
class SpaceCreationWithAssetsControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private UserRepository userRepository;
  @Autowired private GroupRepository groupRepository;

  private final List<UUID> createdLibraryIds = new ArrayList<>();
  private final List<UUID> createdPromptLibraryIds = new ArrayList<>();
  private final List<UUID> createdSpaceIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<UUID> createdGroupIds = new ArrayList<>();

  @BeforeEach
  void provisionCallers() throws Exception {
    createdLibraryIds.clear();
    createdPromptLibraryIds.clear();
    createdSpaceIds.clear();
    createdUserIds.clear();
    createdGroupIds.clear();
    mockMvc.perform(get("/api/v1/spaces").with(devUser())).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/spaces").with(devAdmin())).andExpect(status().isOk());
  }

  @AfterEach
  void removeOwnRows() {
    for (UUID spaceId : createdSpaceIds) {
      jdbcTemplate.update("DELETE FROM notifications WHERE object_id = ?", spaceId);
      jdbcTemplate.update("DELETE FROM space_asset_associations WHERE space_id = ?", spaceId);
      jdbcTemplate.update("DELETE FROM space_membership_history WHERE space_id = ?", spaceId);
      jdbcTemplate.update("DELETE FROM space_memberships WHERE space_id = ?", spaceId);
      jdbcTemplate.update(
          "DELETE FROM asset_ownership_history WHERE asset_type = 'SPACE' AND asset_id = ?",
          spaceId);
      jdbcTemplate.update("DELETE FROM audit_log WHERE object_id = ?", spaceId.toString());
      jdbcTemplate.update("DELETE FROM spaces WHERE id = ?", spaceId);
    }
    List<UUID> assets = new ArrayList<>(createdLibraryIds);
    assets.addAll(createdPromptLibraryIds);
    for (UUID assetId : assets) {
      jdbcTemplate.update("DELETE FROM notifications WHERE object_id = ?", assetId);
      jdbcTemplate.update("DELETE FROM audit_log WHERE object_id = ?", assetId.toString());
      jdbcTemplate.update("DELETE FROM asset_grants WHERE asset_id = ?", assetId);
      jdbcTemplate.update("DELETE FROM asset_grant_history WHERE asset_id = ?", assetId);
    }
    for (UUID promptLibraryId : createdPromptLibraryIds) {
      jdbcTemplate.update("DELETE FROM assets WHERE id = ?", promptLibraryId);
      jdbcTemplate.update(
          "DELETE FROM asset_visibility_history WHERE asset_id = ?", promptLibraryId);
      jdbcTemplate.update(
          "DELETE FROM asset_ownership_history WHERE asset_id = ?", promptLibraryId);
    }
    ownLibraryFixtures.removeLibraries(createdLibraryIds.toArray(new UUID[0]));
    for (UUID groupId : createdGroupIds) {
      jdbcTemplate.update("DELETE FROM groups WHERE id = ?", groupId);
    }
    for (UUID userId : createdUserIds) {
      jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }
  }

  @Test
  void aSpaceIsCreatedWithKnowledgeAndPromptsInOneCall() throws Exception {
    String library = createLibrary(devUser(), "Rechtsquellen " + UUID.randomUUID());
    String prompts = createPromptLibrary(devUser(), "Vorlagen " + UUID.randomUUID());

    String space =
        createSpace(
            devUser(),
            "{\"name\":\"Widerspruchsstelle\",\"assets\":["
                + assetJson("KNOWLEDGE_LIBRARY", library)
                + ","
                + assetJson("PROMPT_LIBRARY", prompts)
                + "]}");

    mockMvc
        .perform(get("/api/v1/spaces/" + space + "/assets").with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.hasUnreadableAssociations").value(false))
        .andExpect(jsonPath("$.items[?(@.assetType == 'PROMPT_LIBRARY')].assetId").value(prompts))
        .andExpect(
            jsonPath("$.items[?(@.assetType == 'KNOWLEDGE_LIBRARY')].assetId").value(library));
  }

  /**
   * A space ADMIN who cannot read an associated library learns neither its id nor its name nor a
   * number - only the count-free hint - from the association list and from the space overview.
   */
  @Test
  void anUnreadableAssociationLeavesNoNameIdOrNumberEvenForASpaceAdmin() throws Exception {
    String secretName = "Personalakte " + UUID.randomUUID();
    String library = createLibrary(devAdmin(), secretName);
    UUID devUserId = userIdOf("dev-user@opaa.local");
    String space =
        createSpace(
            devAdmin(),
            "{\"name\":\"Gemischt\",\"initialMembers\":[{\"subjectType\":\"USER\",\"subjectId\":\""
                + devUserId
                + "\",\"role\":\"ADMIN\"}],\"assets\":["
                + assetJson("KNOWLEDGE_LIBRARY", library)
                + "]}");

    String associations =
        mockMvc
            .perform(get("/api/v1/spaces/" + space + "/assets").with(devUser()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.hasAssociations").value(true))
            .andExpect(jsonPath("$.hasUnreadableAssociations").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(associations).doesNotContain(library).doesNotContain(secretName);

    String overview =
        mockMvc
            .perform(get("/api/v1/spaces").with(devUser()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    List<Integer> counts = JsonPath.read(overview, "$[?(@.id == '" + space + "')].libraryCount");
    assertThat(counts).allMatch(count -> count == 0);
    assertThat(overview).doesNotContain(library).doesNotContain(secretName);
  }

  /**
   * The wizard's one call: a space with an initial member who cannot read what is associated. The
   * owner of both assets is told once, naming both, and the member taken in is audited like one
   * added later.
   */
  @Test
  void creatingAMixedSpaceInOneCallAuditsTheMemberAndNotifiesTheOwnerOnce() throws Exception {
    String libraryName = "Rechtsquellen " + UUID.randomUUID();
    String promptsName = "Vorlagen " + UUID.randomUUID();
    String library = createLibrary(devAdmin(), libraryName);
    String prompts = createPromptLibrary(devAdmin(), promptsName);
    UUID devUserId = userIdOf("dev-user@opaa.local");
    grantViewer("KNOWLEDGE_LIBRARY", library, devUserId);
    grantViewer("PROMPT_LIBRARY", prompts, devUserId);
    UUID memberWithoutAccess = createUserInOrganizationOf(devUserId);

    String space =
        createSpace(
            devUser(),
            "{\"name\":\"Gemischt aus dem Assistenten\",\"initialMembers\":[{\"subjectType\":\"USER\",\"subjectId\":\""
                + memberWithoutAccess
                + "\",\"role\":\"MEMBER\"}],\"assets\":["
                + assetJson("KNOWLEDGE_LIBRARY", library)
                + ","
                + assetJson("PROMPT_LIBRARY", prompts)
                + "]}");

    UUID devAdminId = userIdOf("admin@opaa.local");
    List<java.util.Map<String, Object>> notifications =
        jdbcTemplate.queryForList(
            "SELECT type, object_type, object_id, body FROM notifications"
                + " WHERE recipient_user_id = ? AND (object_id = ? OR object_id = ? OR object_id = ?)",
            devAdminId,
            UUID.fromString(space),
            UUID.fromString(library),
            UUID.fromString(prompts));
    assertThat(notifications).hasSize(1);
    assertThat(notifications.getFirst())
        .containsEntry("type", "ASSET_ASSOCIATED_TO_MIXED_SPACE")
        .containsEntry("object_type", "SPACE")
        .containsEntry("object_id", UUID.fromString(space));
    assertThat((String) notifications.getFirst().get("body")).contains(libraryName, promptsName);

    List<String> memberAdded =
        jdbcTemplate.queryForList(
            "SELECT subject_kind FROM audit_log"
                + " WHERE event_type = 'SPACE_MEMBER_ADDED' AND object_id = ?",
            String.class,
            space);
    assertThat(memberAdded).containsExactly("USER");
  }

  /** The wizard admits a group in the same call, audited as a group like one added later. */
  @Test
  void aGroupIsAdmittedOnCreation() throws Exception {
    UUID group = createReleasedGroupInOrganizationOf(userIdOf("dev-user@opaa.local"));

    String space =
        createSpace(
            devUser(),
            "{\"name\":\"Mit Gruppe\",\"initialMembers\":[{\"subjectType\":\"GROUP\","
                + "\"subjectId\":\""
                + group
                + "\",\"role\":\"CURATOR\"}]}");

    mockMvc
        .perform(get("/api/v1/spaces/" + space + "/members").with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.subjectId == '" + group + "')].subjectType").value("GROUP"))
        .andExpect(jsonPath("$[?(@.subjectId == '" + group + "')].role").value("CURATOR"));
    List<String> memberAdded =
        jdbcTemplate.queryForList(
            "SELECT subject_kind FROM audit_log"
                + " WHERE event_type = 'SPACE_MEMBER_ADDED' AND object_id = ?",
            String.class,
            space);
    assertThat(memberAdded).containsExactly("GROUP");
  }

  /** A space that does not exist yet still names the installation's cleanup periods. */
  @Test
  void theCleanupPeriodsAreReadableWithoutASpace() throws Exception {
    mockMvc
        .perform(get("/api/v1/spaces/chat-auto-cleanup").with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archiveAfterDays").value(90))
        .andExpect(jsonPath("$.deleteAfterDays").value(365));
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private static String assetJson(String assetType, String assetId) {
    return "{\"assetType\":\"" + assetType + "\",\"assetId\":\"" + assetId + "\"}";
  }

  private UUID createReleasedGroupInOrganizationOf(UUID colleague) {
    UUID organizationId =
        jdbcTemplate.queryForObject(
            "SELECT organization_id FROM users WHERE id = ?", UUID.class, colleague);
    Group group = Group.internal(organizationId, "Referat " + UUID.randomUUID(), null, null);
    group.release(true);
    UUID id = groupRepository.save(group).getId();
    createdGroupIds.add(id);
    return id;
  }

  private UUID createUserInOrganizationOf(UUID colleague) {
    UUID organizationId =
        jdbcTemplate.queryForObject(
            "SELECT organization_id FROM users WHERE id = ?", UUID.class, colleague);
    User user =
        new User(
            UUID.randomUUID().toString(),
            "test-issuer",
            "ohne-zugriff-" + UUID.randomUUID() + "@example.com",
            "Ohne");
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  private void grantViewer(String assetType, String assetId, UUID userId) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/assets/" + assetType + "/" + assetId + "/grants")
                .with(devAdmin())
                .content(
                    "{\"subjectType\":\"USER\",\"subjectId\":\""
                        + userId
                        + "\",\"role\":\"VIEWER\"}"))
        .andExpect(status().isOk());
  }

  private UUID userIdOf(String email) {
    return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
  }

  private String createLibrary(RequestPostProcessor caller, String name) throws Exception {
    String id =
        idOf(
            mockMvc
                .perform(
                    post("/api/v1/libraries")
                        .with(caller)
                        .content("{\"name\":\"" + name + "\",\"sourceType\":\"UPLOAD\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    createdLibraryIds.add(UUID.fromString(id));
    return id;
  }

  private String createPromptLibrary(RequestPostProcessor caller, String name) throws Exception {
    String id =
        idOf(
            mockMvc
                .perform(
                    post("/api/v1/prompt-libraries")
                        .with(caller)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    createdPromptLibraryIds.add(UUID.fromString(id));
    return id;
  }

  private String createSpace(RequestPostProcessor caller, String body) throws Exception {
    String id =
        idOf(
            mockMvc
                .perform(post("/api/v1/spaces").with(caller).content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    createdSpaceIds.add(UUID.fromString(id));
    return id;
  }

  private static String idOf(String response) {
    return JsonPath.read(response, "$.id");
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
