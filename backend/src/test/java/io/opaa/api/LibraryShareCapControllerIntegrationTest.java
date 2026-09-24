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
 * HTTP-layer coverage of {@code PUT /api/v1/libraries/{libraryId}/share-cap} (#797, in the shape
 * #1931 gave it): SYSTEM_ADMIN only, rejected for UPLOAD, and what withdrawing either permission
 * takes back at once - the grant to "Alle Konten" and {@code listed}. Runs through the real {@link
 * io.opaa.audit.AuditListener}, unlike the mocked-event-publisher unit coverage in {@code
 * io.opaa.library.KnowledgeLibraryServiceShareCapTest}.
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
          "sourcePath": "/data/dokumente", "listed": true }
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

  private void grantToAllAccounts(String libraryId, RequestPostProcessor caller) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/assets/KNOWLEDGE_LIBRARY/" + libraryId + "/grants")
                .with(caller)
                .content("{\"subjectType\":\"ALL_ACCOUNTS\",\"role\":\"VIEWER\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void isRefusedWithoutSystemAdmin() throws Exception {
    String libraryId = createFilesystemLibrary(devAdmin());

    mockMvc
        .perform(
            put("/api/v1/libraries/" + libraryId + "/share-cap")
                .with(devUser())
                .content("{\"allAccountsGrantAllowed\":false,\"listedCap\":false}"))
        .andExpect(status().isForbidden());
  }

  /**
   * #1931: withdrawing the first permission revokes the grant that carried the organization-wide
   * reach; withdrawing the second clears {@code listed}. Both happen in the same request.
   */
  @Test
  void succeedsForSystemAdminAndTakesTheWiderReachBackImmediately() throws Exception {
    String libraryId = createFilesystemLibrary(devAdmin());
    grantToAllAccounts(libraryId, devAdmin());
    assertThat(allAccountsGrantCount(libraryId)).isEqualTo(1);

    mockMvc
        .perform(
            put("/api/v1/libraries/" + libraryId + "/share-cap")
                .with(devAdmin())
                .content("{\"allAccountsGrantAllowed\":false,\"listedCap\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allAccountsGrantAllowed").value(false))
        .andExpect(jsonPath("$.listedCap").value(false))
        // the library was granted to everybody and listed - the new cap takes both back at once
        .andExpect(jsonPath("$.reach.allAccounts").value(false))
        .andExpect(jsonPath("$.listed").value(false));

    assertThat(allAccountsGrantCount(libraryId))
        .as("the grant that carried the organization-wide reach is gone")
        .isZero();

    // #1870 review, finding 3: the real AuditListener (no mocked eventPublisher here, unlike the
    // service-level unit test) must write all three entries - the governance act, the revoked
    // grant and the cleared findability are separate facts, and all carry the acting system
    // administrator.
    List<Map<String, Object>> events = auditEventsFor(libraryId);
    assertThat(events)
        .extracting(row -> row.get("event_type"))
        .containsExactlyInAnyOrder(
            "CONNECTOR_LIBRARY_SHARE_LIMIT_CHANGED",
            "ASSET_GRANT_REVOKED",
            "ASSET_VISIBILITY_CHANGED");
    assertThat(events).extracting(row -> row.get("actor_ref")).doesNotContainNull();
    assertThat(events.stream().map(row -> row.get("actor_ref")).distinct().count())
        .as("all entries carry the same actor")
        .isEqualTo(1);
  }

  /** Only the share-cap events - {@code LIBRARY_CREATED}/{@code ASSET_GRANT_GRANTED} fire too. */
  private List<Map<String, Object>> auditEventsFor(String libraryId) {
    return jdbcTemplate.queryForList(
        "SELECT event_type, actor_ref FROM audit_log WHERE object_type = 'KNOWLEDGE_LIBRARY' AND"
            + " object_id = ? AND event_type IN ('CONNECTOR_LIBRARY_SHARE_LIMIT_CHANGED',"
            + " 'ASSET_GRANT_REVOKED', 'ASSET_VISIBILITY_CHANGED')",
        libraryId);
  }

  private long allAccountsGrantCount(String libraryId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM asset_grants WHERE asset_id = ?::uuid AND subject_type ="
            + " 'ALL_ACCOUNTS'",
        Long.class,
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
                .content("{\"allAccountsGrantAllowed\":false,\"listedCap\":false}"))
        .andExpect(status().isBadRequest());
  }

  /**
   * #1870 review, finding "Testname behauptet mehr als er prüft": creates and grants as the actual
   * owner ({@code dev-user}, no system role) rather than {@code devAdmin()} - only the cap itself
   * is set by the system administration, matching what the name promises. #1931 moved the refusal
   * from the update path to the grant path, which is where the wider reach is now asked for.
   */
  @Test
  void refusesTheRealOwnerGrantingToAllAccountsAboveTheNewCapWith409() throws Exception {
    String libraryId = createFilesystemLibrary(devUser());
    mockMvc
        .perform(
            put("/api/v1/libraries/" + libraryId + "/share-cap")
                .with(devAdmin())
                .content("{\"allAccountsGrantAllowed\":false,\"listedCap\":false}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/v1/assets/KNOWLEDGE_LIBRARY/" + libraryId + "/grants")
                .with(devUser())
                .content("{\"subjectType\":\"ALL_ACCOUNTS\",\"role\":\"VIEWER\"}"))
        .andExpect(status().isConflict());

    mockMvc
        .perform(
            put("/api/v1/libraries/" + libraryId)
                .with(devUser())
                .content("{\"name\":\"Freigabe-Obergrenze Test\",\"listed\":true}"))
        .andExpect(status().isConflict());
  }
}
