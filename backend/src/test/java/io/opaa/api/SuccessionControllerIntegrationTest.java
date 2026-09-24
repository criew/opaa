package io.opaa.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.test.OpaaIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The two endpoints of the operational list over HTTP (#1819): who may read it, what a tab answers,
 * and the Sichtungsvermerk against an open case.
 */
@OpaaIntegrationTest
class SuccessionControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID caseId;

  @AfterEach
  void removeWhatWasCreated() {
    if (caseId != null) {
      jdbcTemplate.update("DELETE FROM succession_reviews WHERE case_id = ?", caseId);
      jdbcTemplate.update("DELETE FROM succession_cases WHERE id = ?", caseId);
      caseId = null;
    }
  }

  @Test
  void onlyTheSystemAdministrationReadsTheList() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/succession").param("kind", "OPEN_SUCCESSION").with(devUser()))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(get("/api/v1/admin/succession").param("kind", "OPEN_SUCCESSION").with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(50))
        .andExpect(jsonPath("$.entries").isArray());
  }

  /** The tab is the one thing the list needs; without it there is no list to answer. */
  @Test
  void aListWithoutATabIsNoRequest() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/succession").with(devAdmin()))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(get("/api/v1/admin/succession").param("kind", "KEIN_REITER").with(devAdmin()))
        .andExpect(status().isBadRequest());
  }

  /**
   * The declared bounds are refused, never silently corrected: an answer for page 0 to a request
   * for page -1, or 200 entries to a request for 500, would look like the answer that was asked for
   * - the same reasoning the 5000-entry bound carries.
   */
  @Test
  void aPageOutsideTheDeclaredBoundsIsRefusedInsteadOfCorrected() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/succession")
                .param("kind", "OPEN_SUCCESSION")
                .param("size", "500")
                .with(devAdmin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Seitengröße")));

    mockMvc
        .perform(
            get("/api/v1/admin/succession")
                .param("kind", "OPEN_SUCCESSION")
                .param("page", "-1")
                .with(devAdmin()))
        .andExpect(status().isBadRequest());

    // Die Grenze selbst bleibt erlaubt.
    mockMvc
        .perform(
            get("/api/v1/admin/succession")
                .param("kind", "OPEN_SUCCESSION")
                .param("size", "200")
                .with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(200));
  }

  @Test
  void aSichtungsvermerkIsWrittenAgainstAnOpenCase() throws Exception {
    caseId = openCase();

    mockMvc
        .perform(
            post("/api/v1/admin/succession/" + caseId + "/reviews")
                .with(devAdmin())
                .content("{\"reason\":\"geprüft, Nachfolge in Vorbereitung\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.caseId").value(caseId.toString()))
        .andExpect(jsonPath("$.reason").value("geprüft, Nachfolge in Vorbereitung"))
        .andExpect(jsonPath("$.reviewedAt").isNotEmpty());
  }

  @Test
  void aSichtungsvermerkNeedsACaseAndAReason() throws Exception {
    caseId = openCase();

    mockMvc
        .perform(
            post("/api/v1/admin/succession/" + UUID.randomUUID() + "/reviews")
                .with(devAdmin())
                .content("{\"reason\":\"weiterhin offen\"}"))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(
            post("/api/v1/admin/succession/" + caseId + "/reviews")
                .with(devAdmin())
                .content("{\"reason\":\"   \"}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/v1/admin/succession/" + caseId + "/reviews")
                .with(devUser())
                .content("{\"reason\":\"weiterhin offen\"}"))
        .andExpect(status().isForbidden());
  }

  /**
   * A record of the administration's own organization about an object that need not exist - the
   * object column carries no foreign key (ADR-0016), and the Sichtungsvermerk is about the record.
   */
  private UUID openCase() throws Exception {
    // The dev account reaches the users table with its first request, and the case belongs to its
    // organization - a case of another one is none of its business.
    mockMvc
        .perform(get("/api/v1/admin/succession").param("kind", "OPEN_SUCCESSION").with(devAdmin()))
        .andExpect(status().isOk());
    UUID id = UUID.randomUUID();
    UUID organizationId =
        jdbcTemplate.queryForObject(
            "SELECT organization_id FROM users WHERE subject = 'dev-admin'", UUID.class);
    jdbcTemplate.update(
        "INSERT INTO succession_cases (id, organization_id, kind, object_type, asset_type,"
            + " object_id, first_seen_at, last_seen_at, created_at) VALUES (?, ?,"
            + " 'OPEN_SUCCESSION', 'ASSET', 'KNOWLEDGE_LIBRARY', ?, now(), now(), now())",
        id,
        organizationId,
        UUID.randomUUID());
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
