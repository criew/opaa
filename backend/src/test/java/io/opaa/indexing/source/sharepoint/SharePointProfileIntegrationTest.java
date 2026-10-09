package io.opaa.indexing.source.sharepoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.test.ConnectorReleases;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * SharePoint through a profile in the wired application: the type is offered only with one, the
 * profile carries client id, secret and tenant at the fixed Graph address, and a library on it
 * carries no secret, only its document libraries. Saving contacts neither Entra nor Graph.
 */
@OpaaIntegrationTest
class SharePointProfileIntegrationTest {

  private static final String PROFILES = "/api/v1/admin/connection-profiles";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures libraryFixtures;

  private final List<UUID> libraries = new ArrayList<>();
  private UUID profile;

  @BeforeEach
  void createProfile() throws Exception {
    String created =
        mockMvc
            .perform(
                as(post(PROFILES))
                    .content(
                        """
                        {"name": "Zugang SharePoint %s", "sourceType": "SHAREPOINT",
                         "serverUrl": "", "authMethod": "CLIENT_CREDENTIALS",
                         "ownership": "LIBRARY", "clientId": "app-registration",
                         "clientSecret": "geheim", "tenant": "contoso.onmicrosoft.com"}
                        """
                            .formatted(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.serverUrl").value("https://graph.microsoft.com"))
            .andExpect(jsonPath("$.clientSecretSet").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(created).doesNotContain("geheim");
    profile = UUID.fromString(JsonPath.read(created, "$.id"));
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + profile);
  }

  @AfterEach
  void tearDown() {
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
    jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
    jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
  }

  @Test
  void theTypeIsOfferedOnlyThroughAProfileSigningInWithClientCredentials() throws Exception {
    mockMvc
        .perform(as(get("/api/v1/source-types")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.type == 'SHAREPOINT')].profileSupport").value("REQUIRED"))
        .andExpect(jsonPath("$[?(@.type == 'SHAREPOINT')].browsable").value(true));
  }

  @Test
  void aLibraryOnTheProfileCarriesNoSecretOnlyItsDocumentLibraries() throws Exception {
    String body =
        mockMvc
            .perform(
                as(post("/api/v1/libraries"))
                    .content(
                        """
                        {"name": "SharePoint über Zugang %s", "sourceType": "SHAREPOINT",
                         "connectionProfileId": "%s",
                         "sourceSettings": {"libraries": [{"driveId": "b!drive-0",
                                                           "folders": ["01ABC"]}]}}
                        """
                            .formatted(UUID.randomUUID(), profile)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sourceCredentialsSet").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID library = UUID.fromString(JsonPath.read(body, "$.id"));
    libraries.add(library);

    assertThat(
            jdbc.queryForObject(
                "SELECT source_credentials FROM knowledge_libraries WHERE id = ?",
                String.class,
                library))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT source_url FROM knowledge_libraries WHERE id = ?", String.class, library))
        .isEqualTo("https://graph.microsoft.com");
  }

  @Test
  void aLibraryWithoutAProfileIsRefused() throws Exception {
    mockMvc
        .perform(
            as(post("/api/v1/libraries"))
                .content(
                    """
                    {"name": "SharePoint ohne Zugang %s", "sourceType": "SHAREPOINT",
                     "sourceCredentials": "eyJ0eXAiOiJKV1QifQ.token",
                     "sourceSettings": {"libraries": [{"driveId": "b!drive-0"}]}}
                    """
                        .formatted(UUID.randomUUID())))
        .andExpect(status().is4xxClientError());
  }

  private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, "dev-admin")
        .contentType(MediaType.APPLICATION_JSON);
  }
}
