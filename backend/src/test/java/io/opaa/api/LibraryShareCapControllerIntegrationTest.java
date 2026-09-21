package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * HTTP-layer coverage of {@code PUT /api/v1/libraries/{libraryId}/share-cap} (#797): SYSTEM_ADMIN
 * only, rejected for UPLOAD, and the immediate clamp of a wider visibility/listed once the cap
 * narrows - through the real {@link io.opaa.audit.AuditListener}, unlike the mocked-event-publisher
 * unit coverage in {@code io.opaa.library.KnowledgeLibraryServiceShareCapTest}.
 */
@OpaaIntegrationTest
class LibraryShareCapControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;

  private List<UUID> foreignLibraryIds = List.of();

  @BeforeEach
  void rememberForeignLibraries() {
    foreignLibraryIds = libraryIds();
  }

  @AfterEach
  void removeCreatedLibraries() {
    List<UUID> own = new ArrayList<>(libraryIds());
    own.removeAll(foreignLibraryIds);
    ownLibraryFixtures.removeLibraries(own.toArray(new UUID[0]));
  }

  private List<UUID> libraryIds() {
    return jdbcTemplate.queryForList("SELECT id FROM knowledge_libraries", UUID.class);
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

  private String createFilesystemLibrary(RequestPostProcessor caller) throws Exception {
    String body =
        """
        { "name": "Freigabe-Obergrenze Test", "sourceType": "FILESYSTEM",
          "sourcePath": "/data/dokumente", "visibility": "ORGANIZATION", "listed": true }
        """;
    String response =
        mockMvc
            .perform(post("/api/v1/libraries").with(caller).content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return JsonPath.read(response, "$.id");
  }

  @Test
  void isRefusedWithoutSystemAdmin() throws Exception {
    String libraryId = createFilesystemLibrary(devAdmin());

    mockMvc
        .perform(
            put("/api/v1/libraries/" + libraryId + "/share-cap")
                .with(devUser())
                .content("{\"visibilityCap\":\"PRIVATE\",\"listedCap\":false}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void succeedsForSystemAdminAndClampsAWiderVisibilityImmediately() throws Exception {
    String libraryId = createFilesystemLibrary(devAdmin());

    mockMvc
        .perform(
            put("/api/v1/libraries/" + libraryId + "/share-cap")
                .with(devAdmin())
                .content("{\"visibilityCap\":\"PRIVATE\",\"listedCap\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.visibilityCap").value("PRIVATE"))
        .andExpect(jsonPath("$.listedCap").value(false))
        // the library carried ORGANIZATION/listed=true - the new cap clamps it down at once
        .andExpect(jsonPath("$.visibility").value("PRIVATE"))
        .andExpect(jsonPath("$.listed").value(false));

    // #1870 review, finding 3: the real AuditListener (no mocked eventPublisher here, unlike the
    // service-level unit test) must write both entries - the governance act and the clamp it
    // triggered are two entries, not one, and both carry the acting system administrator.
    List<Map<String, Object>> events = auditEventsFor(libraryId);
    assertThat(events)
        .extracting(row -> row.get("event_type"))
        .containsExactlyInAnyOrder(
            "CONNECTOR_LIBRARY_SHARE_LIMIT_CHANGED", "ASSET_VISIBILITY_CHANGED");
    assertThat(events).extracting(row -> row.get("actor_ref")).doesNotContainNull();
    assertThat(events.stream().map(row -> row.get("actor_ref")).distinct().count())
        .as("both entries carry the same actor")
        .isEqualTo(1);
  }

  /**
   * Only the two share-cap events - {@code LIBRARY_CREATED}/{@code ASSET_GRANT_GRANTED} fire too.
   */
  private List<Map<String, Object>> auditEventsFor(String libraryId) {
    return jdbcTemplate.queryForList(
        "SELECT event_type, actor_ref FROM audit_log WHERE object_type = 'KNOWLEDGE_LIBRARY' AND"
            + " object_id = ? AND event_type IN ('CONNECTOR_LIBRARY_SHARE_LIMIT_CHANGED',"
            + " 'ASSET_VISIBILITY_CHANGED')",
        libraryId);
  }

  @Test
  void isRejectedForAnUploadLibrary() throws Exception {
    String body = "{ \"name\": \"Uploads\", \"sourceType\": \"UPLOAD\" }";
    String response =
        mockMvc
            .perform(post("/api/v1/libraries").with(devAdmin()).content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String libraryId = JsonPath.read(response, "$.id");

    mockMvc
        .perform(
            put("/api/v1/libraries/" + libraryId + "/share-cap")
                .with(devAdmin())
                .content("{\"visibilityCap\":\"PRIVATE\",\"listedCap\":false}"))
        .andExpect(status().isBadRequest());
  }

  /**
   * #1870 review, finding "Testname behauptet mehr als er prüft": creates and updates as the actual
   * owner ({@code dev-user}, no system role) rather than {@code devAdmin()} - only the cap itself
   * is set by the system administration, matching what the name promises.
   */
  @Test
  void refusesTheRealOwnerRaisingVisibilityAboveTheNewCapWith409() throws Exception {
    String libraryId = createFilesystemLibrary(devUser());
    mockMvc
        .perform(
            put("/api/v1/libraries/" + libraryId + "/share-cap")
                .with(devAdmin())
                .content("{\"visibilityCap\":\"PRIVATE\",\"listedCap\":false}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            put("/api/v1/libraries/" + libraryId)
                .with(devUser())
                .content("{\"name\":\"Freigabe-Obergrenze Test\",\"visibility\":\"ORGANIZATION\"}"))
        .andExpect(status().isConflict());
  }
}
