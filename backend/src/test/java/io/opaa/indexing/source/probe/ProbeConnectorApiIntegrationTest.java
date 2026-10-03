package io.opaa.indexing.source.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The pluggability proof of ADR-0038: {@link ProbeSourceConnector} and {@link
 * ProbeRunSourceConnector} live only in test code, and no production class names them - yet the API
 * lists their types, creates, reads, changes, tests, lists and runs libraries of them, and each
 * connector's own descriptor and settings rule decide what is accepted.
 */
@OpaaIntegrationTest
class ProbeConnectorApiIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;

  /** A connector the installation did not ship with is off until released. */
  @BeforeEach
  void releaseTheTestConnectors() {
    ConnectorReleases.releaseToAllAccounts(jdbc, "TYPE:PROBE");
    ConnectorReleases.releaseToAllAccounts(jdbc, "TYPE:PROBE_RUN");
  }

  @AfterEach
  void withdrawTheRelease() {
    ConnectorReleases.withdraw(jdbc, "TYPE:PROBE");
    ConnectorReleases.withdraw(jdbc, "TYPE:PROBE_RUN");
  }

  @Test
  void aConnectorOnlyTheTestCodeKnowsIsListedAndServesALibraryThroughTheApi() throws Exception {
    mockMvc
        .perform(as(get("/api/v1/source-types")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.type == 'PROBE')].displayName").value("Testquelle"))
        .andExpect(jsonPath("$[?(@.type == 'PROBE')].indexingRun").value(false));

    String created =
        mockMvc
            .perform(
                as(post("/api/v1/libraries"))
                    .content(
                        """
                        {"name": "Probe", "sourceType": "PROBE", "sourceSettings": {"topic": "Wetter"}}
                        """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sourceType").value("PROBE"))
            .andExpect(jsonPath("$.sourceSettings.topic").value("Wetter"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String libraryId = created.replaceAll("(?s).*\"id\":\"([0-9a-f-]{36})\".*", "$1");
    try {
      mockMvc
          .perform(
              as(put("/api/v1/libraries/" + libraryId))
                  .content("{\"name\": \"Probe\", \"sourceSettings\": {\"topic\": \"Klima\"}}"))
          .andExpect(status().isOk());
      mockMvc
          .perform(as(get("/api/v1/libraries/" + libraryId)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.sourceSettings.topic").value("Klima"));

      mockMvc
          .perform(
              as(post("/api/v1/libraries/source-test"))
                  .content(
                      "{\"sourceType\": \"PROBE\", \"sourceSettings\": {\"topic\": \"Wetter\"}}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.reachable").value(true))
          .andExpect(jsonPath("$.details.topic").value("Wetter"));
      // the connector's own rule, not the API, refuses a field it does not know
      String refused =
          mockMvc
              .perform(
                  as(post("/api/v1/libraries"))
                      .content(
                          """
                          {"name": "Probe", "sourceType": "PROBE", "sourceSettings": {"spaces": []}}
                          """))
              .andExpect(status().isBadRequest())
              .andReturn()
              .getResponse()
              .getContentAsString(StandardCharsets.UTF_8);
      assertThat(refused).contains("das Feld spaces ist nicht vorgesehen");

      // a connector without a run is no upload library: only UPLOAD accepts uploads
      mockMvc
          .perform(
              multipart("/api/v1/libraries/" + libraryId + "/documents")
                  .file(
                      new MockMultipartFile(
                          "file",
                          "notiz.txt",
                          "text/plain",
                          "Inhalt".getBytes(StandardCharsets.UTF_8)))
                  .header(DevAuthFilter.DEV_USER_HEADER, "dev-user"))
          .andExpect(status().isConflict())
          .andExpect(
              jsonPath("$.error")
                  .value(org.hamcrest.Matchers.containsString("keine manuellen Uploads")));
    } finally {
      mockMvc
          .perform(as(delete("/api/v1/libraries/" + libraryId)))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void aRunBasedConnectorOnlyTheTestCodeKnowsIsListedBrowsedScheduledAndRun() throws Exception {
    mockMvc
        .perform(as(get("/api/v1/source-types")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.type == 'PROBE_RUN')].indexingRun").value(true))
        .andExpect(jsonPath("$[?(@.type == 'PROBE_RUN')].browsable").value(true))
        .andExpect(jsonPath("$[?(@.type == 'PROBE_RUN')].uploads").value(false));
    mockMvc
        .perform(
            as(post("/api/v1/source-types/PROBE_RUN/browse"))
                .content("{\"sourceUrl\": \"https://quelle.example.org\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries[0].key").value("A"))
        .andExpect(jsonPath("$.entries[0].name").value("Alpha"));

    String created =
        mockMvc
            .perform(
                as(post("/api/v1/libraries"))
                    .content(
                        """
                        {"name": "Probe mit Lauf", "sourceType": "PROBE_RUN",
                         "sourceUrl": "https://quelle.example.org"}
                        """))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String libraryId = created.replaceAll("(?s).*\"id\":\"([0-9a-f-]{36})\".*", "$1");
    try {
      mockMvc
          .perform(
              as(put("/api/v1/libraries/" + libraryId))
                  .content(
                      "{\"name\": \"Probe mit Lauf\", \"schedule\": {\"frequency\": \"DAILY\","
                          + " \"hour\": 3, \"minute\": 0}}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.schedule.frequency").value("DAILY"));

      mockMvc
          .perform(as(post("/api/v1/libraries/" + libraryId + "/indexing")))
          .andExpect(status().isAccepted());
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () ->
                  mockMvc
                      .perform(as(get("/api/v1/libraries/" + libraryId + "/indexing/status")))
                      .andExpect(status().isOk())
                      .andExpect(jsonPath("$.status").value("COMPLETED")));
    } finally {
      mockMvc
          .perform(as(delete("/api/v1/libraries/" + libraryId)))
          .andExpect(status().isNoContent());
    }
  }

  private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, "dev-user")
        .contentType(MediaType.APPLICATION_JSON);
  }
}
