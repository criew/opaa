package io.opaa.api;

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
 * HTTP-layer coverage of {@code GET /api/v1/assets/{assetType}/{assetId}/spaces} (#1939): which
 * role learns which space. The threshold is {@code MANAGER} - below it the entry is reduced to the
 * space's id and name, and a {@code PRIVATE} space the caller is no member of is not named at all
 * but only counted in {@code hiddenCount}, keeping the promise of that visibility that only its
 * members know the space exists.
 */
@OpaaIntegrationTest
class AssetSpaceAssociationsControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;

  private final List<UUID> createdLibraryIds = new ArrayList<>();
  private final List<UUID> createdSpaceIds = new ArrayList<>();

  @BeforeEach
  void provisionCaller() throws Exception {
    createdLibraryIds.clear();
    createdSpaceIds.clear();
    mockMvc.perform(get("/api/v1/spaces").with(devUser())).andExpect(status().isOk());
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
      jdbcTemplate.update("DELETE FROM spaces WHERE id = ?", spaceId);
    }
    for (UUID libraryId : createdLibraryIds) {
      jdbcTemplate.update("DELETE FROM audit_log WHERE object_id = ?", libraryId.toString());
      jdbcTemplate.update("DELETE FROM asset_grants WHERE asset_id = ?", libraryId);
      jdbcTemplate.update("DELETE FROM asset_grant_history WHERE asset_id = ?", libraryId);
    }
    ownLibraryFixtures.removeLibraries(createdLibraryIds.toArray(new UUID[0]));
  }

  /**
   * The whole role matrix on one fixture: a library in a {@code PRIVATE} space the caller does not
   * belong to. VIEWER and EDITOR learn the count and nothing else; MANAGER learns the space.
   */
  @Test
  void aPrivateSpaceIsOnlyCountedBelowManagerAndNamedFromManagerOn() throws Exception {
    String library = createLibrary(devAdmin());
    String space = createSpace(devAdmin(), "Disziplinarverfahren 2026", null);
    associate(space, library);
    UUID callerId = userIdOf("dev-user@opaa.local");

    for (String role : List.of("VIEWER", "EDITOR")) {
      grantToUser(library, callerId, role);
      mockMvc
          .perform(get(spacesRoute(library)).with(devUser()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items.length()").value(0))
          .andExpect(jsonPath("$.hiddenCount").value(1));
    }

    grantToUser(library, callerId, "MANAGER");
    mockMvc
        .perform(get(spacesRoute(library)).with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hiddenCount").value(0))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].spaceName").value("Disziplinarverfahren 2026"))
        .andExpect(jsonPath("$.items[0].narrowerReaderCircle").exists());
  }

  /**
   * A space that stands in the space directory anyway is named to a reader - but still without the
   * management detail, which stays at MANAGER.
   */
  @Test
  void aDiscoverableSpaceIsNamedToAReaderWithoutAnyManagementDetail() throws Exception {
    String library = createLibrary(devAdmin());
    String space = createSpace(devAdmin(), "Fachbereich Soziales", "DISCOVERABLE");
    associate(space, library);
    grantToUser(library, userIdOf("dev-user@opaa.local"), "VIEWER");

    mockMvc
        .perform(get(spacesRoute(library)).with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hiddenCount").value(0))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].spaceName").value("Fachbereich Soziales"))
        .andExpect(jsonPath("$.items[0].narrowerReaderCircle").doesNotExist())
        .andExpect(jsonPath("$.items[0].createdByUserId").doesNotExist())
        .andExpect(jsonPath("$.items[0].createdAt").doesNotExist());
  }

  /** A member of the PRIVATE space already knows it exists - the count stays at zero for them. */
  @Test
  void aReaderWhoBelongsToThePrivateSpaceSeesItByName() throws Exception {
    String library = createLibrary(devAdmin());
    String space = createSpace(devAdmin(), "Projektgruppe Ost", null);
    associate(space, library);
    UUID callerId = userIdOf("dev-user@opaa.local");
    grantToUser(library, callerId, "VIEWER");
    mockMvc
        .perform(
            post("/api/v1/spaces/" + space + "/members")
                .with(devAdmin())
                .content(
                    "{\"subjectType\":\"USER\",\"subjectId\":\""
                        + callerId
                        + "\",\"role\":\"MEMBER\"}"))
        .andExpect(status().isCreated());

    mockMvc
        .perform(get(spacesRoute(library)).with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hiddenCount").value(0))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].spaceName").value("Projektgruppe Ost"));
  }

  /** Below VIEWER the asset is not there at all - the same 404 as for an unknown id (#436). */
  @Test
  void aCallerWithoutAnyRoleOnTheAssetGets404() throws Exception {
    String library = createLibrary(devAdmin());

    mockMvc.perform(get(spacesRoute(library)).with(devUser())).andExpect(status().isNotFound());
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  private static String spacesRoute(String libraryId) {
    return "/api/v1/assets/KNOWLEDGE_LIBRARY/" + libraryId + "/spaces";
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
                    .content("{ \"name\": \"Zuordnungen Test\", \"sourceType\": \"UPLOAD\" }"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String id = JsonPath.read(response, "$.id");
    createdLibraryIds.add(UUID.fromString(id));
    return id;
  }

  private String createSpace(RequestPostProcessor caller, String name, String visibility)
      throws Exception {
    String body =
        visibility == null
            ? "{ \"name\": \"" + name + "\" }"
            : "{ \"name\": \"" + name + "\", \"visibility\": \"" + visibility + "\" }";
    String response =
        mockMvc
            .perform(post("/api/v1/spaces").with(caller).content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String id = JsonPath.read(response, "$.id");
    createdSpaceIds.add(UUID.fromString(id));
    return id;
  }

  private void associate(String spaceId, String libraryId) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/spaces/" + spaceId + "/assets")
                .with(devAdmin())
                .content("{\"assetType\":\"KNOWLEDGE_LIBRARY\",\"assetId\":\"" + libraryId + "\"}"))
        .andExpect(status().isCreated());
  }

  private void grantToUser(String libraryId, UUID userId, String role) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/assets/KNOWLEDGE_LIBRARY/" + libraryId + "/grants")
                .with(devAdmin())
                .content(
                    "{\"subjectType\":\"USER\",\"subjectId\":\""
                        + userId
                        + "\",\"role\":\""
                        + role
                        + "\"}"))
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
