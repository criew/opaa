package io.opaa.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * HTTP-layer coverage of the prompt library (#1901): the routes the specification promises, the
 * statuses the services decide, and rights given through the shell's generic routes under {@code
 * /api/v1/assets/PROMPT_LIBRARY/...} - there is no prompt-library route of its own for them.
 */
@OpaaIntegrationTest
class PromptLibraryControllerIntegrationTest {

  private static final String PROMPT =
      "{\"name\":\"anhoerung\",\"title\":\"Anhörung\",\"text\":\"Anhörung zu {{aktenzeichen}},"
          + " Stand {{CURRENT_DATE}}\",\"variables\":[{\"name\":\"aktenzeichen\","
          + "\"label\":\"Aktenzeichen\",\"type\":\"SELECT\",\"required\":true,"
          + "\"defaultValue\":\"A-1\",\"options\":[\"A-1\",\"B-2\"]}],\"sortOrder\":3}";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final List<UUID> createdLibraryIds = new ArrayList<>();
  private UUID devAdminId;

  @BeforeEach
  void provisionCallers() throws Exception {
    createdLibraryIds.clear();
    mockMvc.perform(get("/api/v1/spaces").with(devUser())).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/spaces").with(devAdmin())).andExpect(status().isOk());
    devAdminId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE email = ?", UUID.class, "admin@opaa.local");
  }

  @AfterEach
  void removeOwnRows() {
    for (UUID id : createdLibraryIds) {
      jdbcTemplate.update("DELETE FROM assets WHERE id = ?", id);
      jdbcTemplate.update("DELETE FROM asset_grant_history WHERE asset_id = ?", id);
      jdbcTemplate.update("DELETE FROM asset_visibility_history WHERE asset_id = ?", id);
      jdbcTemplate.update("DELETE FROM asset_ownership_history WHERE asset_id = ?", id);
      jdbcTemplate.update("DELETE FROM audit_log WHERE object_id = ?", id.toString());
      jdbcTemplate.update(
          "DELETE FROM audit_log WHERE object_type = 'PROMPT'"
              + " AND (coalesce(before, '') || coalesce(after, '')) LIKE ?",
          "%" + id + "%");
    }
  }

  @Test
  void aPersonCreatesALibraryFillsItAndGivesItAwayThroughTheShell() throws Exception {
    String library = createLibrary(devUser());

    mockMvc
        .perform(get("/api/v1/prompt-libraries/" + library).with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.listed").value(false))
        .andExpect(jsonPath("$.reach.allAccounts").value(false))
        .andExpect(jsonPath("$.reach.groupCount").value(0))
        .andExpect(jsonPath("$.reach.userCount").value(1))
        .andExpect(jsonPath("$.ownerType").value("USER"))
        .andExpect(jsonPath("$.myRole").value("OWNER"))
        .andExpect(jsonPath("$.promptCount").value(0));

    String prompt =
        JsonPath.read(
            mockMvc
                .perform(
                    post("/api/v1/prompt-libraries/" + library + "/prompts")
                        .with(devUser())
                        .content(PROMPT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.promptLibraryId").value(library))
                .andExpect(jsonPath("$.variables[0].type").value("SELECT"))
                .andExpect(jsonPath("$.variables[0].options[1]").value("B-2"))
                .andExpect(jsonPath("$.sortOrder").value(3))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8),
            "$.id");

    mockMvc
        .perform(get("/api/v1/prompt-libraries/" + library + "/prompts").with(devAdmin()))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            post("/api/v1/assets/PROMPT_LIBRARY/" + library + "/grants")
                .with(devUser())
                .content(
                    "{\"subjectType\":\"USER\",\"subjectId\":\""
                        + devAdminId
                        + "\",\"role\":\"VIEWER\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subjectId").value(devAdminId.toString()));

    mockMvc
        .perform(get("/api/v1/prompt-libraries/" + library + "/prompts/" + prompt).with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("anhoerung"));
    mockMvc
        .perform(
            get("/api/v1/assets/PROMPT_LIBRARY/" + library + "/access-derivation").with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assetType").value("PROMPT_LIBRARY"));
    mockMvc
        .perform(get("/api/v1/assets/KNOWLEDGE_LIBRARY/" + library + "/grants").with(devUser()))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(
            put("/api/v1/prompt-libraries/" + library)
                .with(devUser())
                .content("{\"name\":\"Vorlagen\",\"listed\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.listed").value(true))
        // #1931: the reach follows the grants - the second one above widened it to two people.
        .andExpect(jsonPath("$.reach.allAccounts").value(false))
        .andExpect(jsonPath("$.reach.userCount").value(2))
        .andExpect(jsonPath("$.promptCount").value(1));

    mockMvc
        .perform(
            delete("/api/v1/prompt-libraries/" + library + "/prompts/" + prompt).with(devUser()))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(delete("/api/v1/prompt-libraries/" + library).with(devUser()))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/v1/prompt-libraries/" + library).with(devUser()))
        .andExpect(status().isNotFound());
  }

  @Test
  void thePromptRoutesAnswerTheirOwnRefusals() throws Exception {
    String library = createLibrary(devUser());
    String prompts = "/api/v1/prompt-libraries/" + library + "/prompts";

    mockMvc
        .perform(post(prompts).with(devUser()).content(PROMPT.replace("anhoerung", "Anhörung")))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post(prompts)
                .with(devUser())
                .content("{\"name\":\"ohne\",\"title\":\"Ohne\",\"text\":\"Zu {{aktenzeichen}}\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error").value(org.hamcrest.Matchers.containsString("nicht definiert")));
    mockMvc.perform(post(prompts).with(devUser()).content(PROMPT)).andExpect(status().isCreated());
    mockMvc.perform(post(prompts).with(devUser()).content(PROMPT)).andExpect(status().isConflict());
    mockMvc
        .perform(get(prompts + "/" + UUID.randomUUID()).with(devUser()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/v1/prompt-libraries/" + UUID.randomUUID()).with(devUser()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/v1/prompt-libraries").with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + library + "')].promptCount").value(1));
  }

  private String createLibrary(RequestPostProcessor caller) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/prompt-libraries")
                    .with(caller)
                    .content("{\"name\":\"Formulierungshilfen\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String id = JsonPath.read(response, "$.id");
    createdLibraryIds.add(UUID.fromString(id));
    return id;
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
