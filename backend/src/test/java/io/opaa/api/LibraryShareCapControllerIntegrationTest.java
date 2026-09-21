package io.opaa.api;

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
 * narrows. The per-source-type/clamp behaviour itself is pinned at the service level by {@code
 * io.opaa.library.KnowledgeLibraryServiceShareCapTest}; this class only pins the wiring around it -
 * same shape as {@code LibraryControllerCredentialsIntegrationTest}, which explains why HTTP-layer
 * coverage for this controller lives in small dedicated classes on the shared {@link
 * OpaaIntegrationTest} context rather than one large one.
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

  @Test
  void thenRefusesAnOwnerRaisingVisibilityAboveTheNewCapWith409() throws Exception {
    String libraryId = createFilesystemLibrary(devAdmin());
    mockMvc
        .perform(
            put("/api/v1/libraries/" + libraryId + "/share-cap")
                .with(devAdmin())
                .content("{\"visibilityCap\":\"PRIVATE\",\"listedCap\":false}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            put("/api/v1/libraries/" + libraryId)
                .with(devAdmin())
                .content("{\"name\":\"Freigabe-Obergrenze Test\",\"visibility\":\"ORGANIZATION\"}"))
        .andExpect(status().isConflict());
  }
}
