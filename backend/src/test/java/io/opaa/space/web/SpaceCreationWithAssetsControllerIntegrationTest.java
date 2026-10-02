package io.opaa.space.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
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
 * HTTP-layer coverage of creating a space with associations of every asset type in one call, and
 * of the rule that an association the caller cannot read leaves no trace in the space's answers -
 * whatever the caller's role there (ADR-0039, Entscheidung 2).
 */
@OpaaIntegrationTest
class SpaceCreationWithAssetsControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;

  private final List<UUID> createdLibraryIds = new ArrayList<>();
  private final List<UUID> createdPromptLibraryIds = new ArrayList<>();
  private final List<UUID> createdSpaceIds = new ArrayList<>();

  @BeforeEach
  void provisionCallers() throws Exception {
    createdLibraryIds.clear();
    createdPromptLibraryIds.clear();
    createdSpaceIds.clear();
    mockMvc.perform(get("/api/v1/spaces").with(devUser())).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/spaces").with(devAdmin())).andExpect(status().isOk());
  }

  @AfterEach
  void removeOwnRows() {
    for (UUID spaceId : createdSpaceIds) {
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
      jdbcTemplate.update("DELETE FROM asset_ownership_history WHERE asset_id = ?", promptLibraryId);
    }
    ownLibraryFixtures.removeLibraries(createdLibraryIds.toArray(new UUID[0]));
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
        .andExpect(
            jsonPath("$.items[?(@.assetType == 'PROMPT_LIBRARY')].assetId").value(prompts))
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
            "{\"name\":\"Gemischt\",\"initialMembers\":[{\"userId\":\""
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

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private static String assetJson(String assetType, String assetId) {
    return "{\"assetType\":\"" + assetType + "\",\"assetId\":\"" + assetId + "\"}";
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
