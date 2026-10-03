package io.opaa.indexing.source.smb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.test.OpaaIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * An SMB library through the HTTP API against a real Samba: listed as a source type released to no
 * one by default, created by the system administration with the normalised address, indexed by a
 * run, and the credentials kept only while the server stays the same.
 */
@OpaaIntegrationTest
class SmbLibraryIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private MockMvc mockMvc;

  private String libraryId;

  @AfterEach
  void deleteLibrary() throws Exception {
    if (libraryId != null) {
      mockMvc
          .perform(as(delete("/api/v1/libraries/" + libraryId)))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void theSourceTypeIsListed() throws Exception {
    mockMvc
        .perform(as(get("/api/v1/source-types")))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$[?(@.type == 'SMB')].displayName").value("Windows-Dateifreigabe (SMB)"))
        .andExpect(jsonPath("$[?(@.type == 'SMB')].browsable").value(true))
        .andExpect(jsonPath("$[?(@.type == 'SMB')].indexingRun").value(true));
  }

  @Test
  void aNewSourceTypeIsReleasedToNoOneByDefault() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/source-types")
                .header(DevAuthFilter.DEV_USER_HEADER, "dev-user")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.type == 'SMB')].creatable").value(false));
  }

  @Test
  void aLibraryIsCreatedIndexedAndKeepsItsCredentialsOnlyOnTheSameServer() throws Exception {
    SambaFixture samba = SambaFixture.get();
    String folder = "Bibliothek " + UUID.randomUUID();
    samba.put(folder + "/Bescheid.txt", "Der Bescheid ergeht wie folgt.");
    samba.put(folder + "/Akten/Protokoll.md", "# Protokoll\n\nDie Sitzung beginnt.");
    String url = samba.url(SambaFixture.SHARE);

    String created =
        mockMvc
            .perform(
                as(post("/api/v1/libraries"))
                    .content(
                        """
                        {"name": "Freigabe", "sourceType": "SMB", "sourceUrl": %s,
                         "sourceCredentials": %s,
                         "sourceSettings": {"folders": [%s]}}
                        """
                            .formatted(
                                JSON.writeValueAsString("SMB" + url.substring(3) + "/"),
                                JSON.writeValueAsString(samba.credentials()),
                                JSON.writeValueAsString("/" + folder))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sourceUrl").value(url))
            .andExpect(jsonPath("$.sourceCredentialsSet").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(created).doesNotContain(SambaFixture.PASSWORD);
    libraryId = created.replaceAll("(?s).*\"id\":\"([0-9a-f-]{36})\".*", "$1");

    mockMvc
        .perform(as(post("/api/v1/libraries/" + libraryId + "/indexing")))
        .andExpect(status().isAccepted());
    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(
            () ->
                mockMvc
                    .perform(as(get("/api/v1/libraries/" + libraryId + "/indexing/status")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.documentsIndexedTotal").value(2)));

    mockMvc
        .perform(
            as(put("/api/v1/libraries/" + libraryId))
                .content(
                    "{\"name\": \"Freigabe\", \"sourceUrl\": "
                        + JSON.writeValueAsString(
                            "smb://localhost:" + samba.port() + "/" + SambaFixture.SHARE)
                        + "}"))
        .andExpect(status().isBadRequest());
  }

  private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, "dev-admin")
        .contentType(MediaType.APPLICATION_JSON);
  }
}
