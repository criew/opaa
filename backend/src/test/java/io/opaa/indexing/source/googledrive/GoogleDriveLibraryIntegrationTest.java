package io.opaa.indexing.source.googledrive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.indexing.source.ServiceAccountKeyFixture;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Google Drive library through the HTTP API (ADR-0040): listed with its sign-in kind, created
 * with the fixed address and the key stored in its reduced form, a changed imitated account without
 * the key refused, a proxy change keeping it. Saving contacts Google nowhere.
 */
@OpaaIntegrationTest
class GoogleDriveLibraryIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private MockMvc mockMvc;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private JdbcTemplate jdbc;

  private final ServiceAccountKeyFixture key = new ServiceAccountKeyFixture();
  private String libraryId;

  /** Google Drive did not ship with the release of #2161, so it is off until released. */
  @BeforeEach
  void release() {
    ConnectorReleases.releaseToAllAccounts(jdbc, "TYPE:GOOGLE_DRIVE");
  }

  @AfterEach
  void deleteLibrary() throws Exception {
    if (libraryId != null) {
      mockMvc
          .perform(as(delete("/api/v1/libraries/" + libraryId)))
          .andExpect(status().isNoContent());
    }
    ConnectorReleases.withdraw(jdbc, "TYPE:GOOGLE_DRIVE");
  }

  @Test
  void theSourceTypeIsListed() throws Exception {
    mockMvc
        .perform(as(get("/api/v1/source-types")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.type == 'GOOGLE_DRIVE')].displayName").value("Google Drive"))
        .andExpect(jsonPath("$[?(@.type == 'GOOGLE_DRIVE')].browsable").value(true));
  }

  @Test
  void aLibraryIsCreatedWithTheFixedAddressAndTheSubjectRuleHolds() throws Exception {
    String created =
        mockMvc
            .perform(
                as(post("/api/v1/libraries"))
                    .content(
                        """
                        {"name": "Drive", "sourceType": "GOOGLE_DRIVE",
                         "sourceCredentials": %s,
                         "sourceSettings": {"scopes": [{"folder": "abc_DEF-1", "name": "Akten"}],
                                            "subject": "fach@example.org"}}
                        """
                            .formatted(JSON.writeValueAsString(key.json()))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sourceUrl").value("https://www.googleapis.com"))
            .andExpect(jsonPath("$.sourceCredentialsSet").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(created).doesNotContain(key.privateKeyMarker());
    libraryId = created.replaceAll("(?s).*\"id\":\"([0-9a-f-]{36})\".*", "$1");
    String stored = storedCredentials();
    assertThat(stored).doesNotContain("token_uri");

    mockMvc
        .perform(
            as(put("/api/v1/libraries/" + libraryId))
                .content(
                    """
                    {"name": "Drive",
                     "sourceSettings": {"scopes": [{"folder": "abc_DEF-1"}],
                                        "subject": "chef@example.org"}}
                    """))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            as(put("/api/v1/libraries/" + libraryId))
                .content("{\"name\": \"Drive\", \"sourceProxy\": \"proxy.example.org:8080\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceCredentialsSet").value(true));

    assertThat(storedCredentials()).isEqualTo(stored);
  }

  @Test
  void anotherAddressAndASwitchedOffCertificateCheckAreRefused() throws Exception {
    String body =
        """
        {"name": "Drive", "sourceType": "GOOGLE_DRIVE", "sourceCredentials": %s,
         "sourceUrl": "%s", "sourceInsecureSsl": %s,
         "sourceSettings": {"scopes": [{"drive": "d1"}]}}
        """;

    mockMvc
        .perform(
            as(post("/api/v1/libraries"))
                .content(
                    body.formatted(
                        JSON.writeValueAsString(key.json()), "https://evil.example.org", false)))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            as(post("/api/v1/libraries"))
                .content(
                    body.formatted(
                        JSON.writeValueAsString(key.json()), "https://www.googleapis.com", true)))
        .andExpect(status().isBadRequest());
  }

  private String storedCredentials() {
    return libraryRepository
        .findById(UUID.fromString(libraryId))
        .orElseThrow()
        .getSourceCredentials();
  }

  private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, "dev-user")
        .contentType(MediaType.APPLICATION_JSON);
  }
}
