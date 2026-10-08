package io.opaa.succession.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.test.OpaaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The endpoint of the operational list over HTTP (#1819): who may read it and what a tab answers.
 */
@OpaaIntegrationTest
class SuccessionControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;

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
