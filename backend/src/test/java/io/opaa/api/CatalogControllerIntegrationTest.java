package io.opaa.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.test.OpaaIntegrationTest;
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
 * HTTP-layer coverage of {@code GET /api/v1/catalog}: the entry the specification promises, a
 * listed asset findable but not accessible for a system administrator without a grant, and the
 * parameters the operation refuses itself.
 */
@OpaaIntegrationTest
class CatalogControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final List<UUID> createdLibraryIds = new ArrayList<>();

  @BeforeEach
  void provisionCallers() throws Exception {
    createdLibraryIds.clear();
    mockMvc.perform(get("/api/v1/spaces").with(devUser())).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/spaces").with(devAdmin())).andExpect(status().isOk());
  }

  @AfterEach
  void removeOwnRows() {
    for (UUID id : createdLibraryIds) {
      jdbcTemplate.update("DELETE FROM assets WHERE id = ?", id);
      jdbcTemplate.update("DELETE FROM asset_grant_history WHERE asset_id = ?", id);
      jdbcTemplate.update("DELETE FROM asset_visibility_history WHERE asset_id = ?", id);
      jdbcTemplate.update("DELETE FROM asset_ownership_history WHERE asset_id = ?", id);
      jdbcTemplate.update("DELETE FROM audit_log WHERE object_id = ?", id.toString());
    }
  }

  @Test
  void aListedLibraryIsFoundButNotAccessibleWithoutAGrantAndItsDetailStaysClosed()
      throws Exception {
    String name = "Katalog " + UUID.randomUUID();
    String id =
        createPromptLibrary(
            "{\"name\":\"" + name + "\",\"description\":\"Vorlagen\",\"listed\":true}");

    mockMvc
        .perform(
            get("/api/v1/catalog")
                .param("type", "PROMPT_LIBRARY")
                .param("q", name)
                .with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(50))
        .andExpect(jsonPath("$.entries[0].assetType").value("PROMPT_LIBRARY"))
        .andExpect(jsonPath("$.entries[0].assetId").value(id))
        .andExpect(jsonPath("$.entries[0].name").value(name))
        .andExpect(jsonPath("$.entries[0].description").value("Vorlagen"))
        .andExpect(jsonPath("$.entries[0].ownerType").value("USER"))
        .andExpect(jsonPath("$.entries[0].ownerLabel").value("Dev User"))
        .andExpect(jsonPath("$.entries[0].origin").value("LOCAL"))
        .andExpect(jsonPath("$.entries[0].listed").value(true))
        .andExpect(jsonPath("$.entries[0].accessible").value(false))
        .andExpect(jsonPath("$.entries[0].succession").doesNotExist());
    mockMvc
        .perform(get("/api/v1/prompt-libraries/" + id + "/prompts").with(devAdmin()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/v1/catalog")
                .param("type", "KNOWLEDGE_LIBRARY")
                .param("q", name)
                .with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    mockMvc
        .perform(get("/api/v1/catalog").param("q", name).with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries[0].accessible").value(true));
  }

  @Test
  void anUnlistedLibraryWithoutAGrantIsNotInTheCatalog() throws Exception {
    String name = "Unauffindbar " + UUID.randomUUID();
    createPromptLibrary("{\"name\":\"" + name + "\"}");

    mockMvc
        .perform(get("/api/v1/catalog").param("q", name).with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0))
        .andExpect(jsonPath("$.entries").isEmpty());
  }

  @Test
  void theCatalogRefusesParametersOutsideItsBounds() throws Exception {
    mockMvc
        .perform(get("/api/v1/catalog").param("size", "0").with(devUser()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/v1/catalog").param("page", "-1").with(devUser()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/v1/catalog").param("q", "x".repeat(201)).with(devUser()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/v1/catalog").param("type", "SPACE").with(devUser()))
        .andExpect(status().isBadRequest());
  }

  private String createPromptLibrary(String body) throws Exception {
    String response =
        mockMvc
            .perform(post("/api/v1/prompt-libraries").with(devUser()).content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String id = JsonPath.read(response, "$.id");
    createdLibraryIds.add(UUID.fromString(id));
    return id;
  }

  private RequestPostProcessor devUser() {
    return as("dev-user");
  }

  private RequestPostProcessor devAdmin() {
    return as("dev-admin");
  }

  private static RequestPostProcessor as(String subject) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }
}
