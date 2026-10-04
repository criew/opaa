package io.opaa.indexing.source.googledrive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.indexing.source.ServiceAccountKeyFixture;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
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
 * Google Drive through a profile (#2220): the profile holds the key and names the client id from
 * it, a library on it carries neither key nor imitated account, and the profile requirement can be
 * switched on for the type. Saving contacts Google nowhere.
 */
@OpaaIntegrationTest
class GoogleDriveProfileIntegrationTest {

  private static final String TYPE = "GOOGLE_DRIVE";
  private static final String PROFILES = "/api/v1/admin/connection-profiles";
  private static final String REQUIREMENT =
      "/api/v1/admin/connector-types/" + TYPE + "/profile-requirement";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures libraryFixtures;

  private final ServiceAccountKeyFixture key = new ServiceAccountKeyFixture();
  private final List<UUID> libraries = new ArrayList<>();
  private UUID profile;

  @BeforeEach
  void createProfile() throws Exception {
    jdbc.update("DELETE FROM connector_type_policies WHERE source_type = ?", TYPE);
    String created =
        mockMvc
            .perform(
                as(post(PROFILES))
                    .content(
                        """
                        {"name": "Zugang Drive %s", "sourceType": "GOOGLE_DRIVE", "serverUrl": "",
                         "authMethod": "SERVICE_ACCOUNT_KEY", "ownership": "LIBRARY",
                         "clientId": "wird-ignoriert", "clientSecret": %s,
                         "connectorSettings": {"subject": "fach@example.org"}}
                        """
                            .formatted(UUID.randomUUID(), JSON.writeValueAsString(key.json()))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.serverUrl").value("https://www.googleapis.com"))
            .andExpect(jsonPath("$.clientId").value(ServiceAccountKeyFixture.CLIENT_EMAIL))
            .andExpect(jsonPath("$.clientSecretSet").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(created).doesNotContain(key.privateKeyMarker());
    profile = UUID.fromString(JsonPath.read(created, "$.id"));
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + profile);
  }

  @AfterEach
  void tearDown() {
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
    jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
    jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    jdbc.update("DELETE FROM connector_type_policies WHERE source_type = ?", TYPE);
  }

  @Test
  void aLibraryOnTheProfileCarriesNeitherKeyNorImitatedAccount() throws Exception {
    String body =
        mockMvc
            .perform(
                as(post("/api/v1/libraries"))
                    .content(
                        """
                        {"name": "Drive über Zugang %s", "sourceType": "GOOGLE_DRIVE",
                         "connectionProfileId": "%s",
                         "sourceSettings": {"scopes": [{"drive": "d1"}]}}
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
                "SELECT source_settings FROM knowledge_libraries WHERE id = ?",
                String.class,
                library))
        .doesNotContain("subject");
    mockMvc
        .perform(
            as(post("/api/v1/libraries"))
                .content(
                    """
                    {"name": "Drive mit Konto %s", "sourceType": "GOOGLE_DRIVE",
                     "connectionProfileId": "%s",
                     "sourceSettings": {"scopes": [{"drive": "d1"}], "subject": "chef@example.org"}}
                    """
                        .formatted(UUID.randomUUID(), profile)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("subject")));
    mockMvc
        .perform(
            as(post("/api/v1/libraries"))
                .content(
                    """
                    {"name": "Drive mit Schlüssel %s", "sourceType": "GOOGLE_DRIVE",
                     "connectionProfileId": "%s", "sourceCredentials": %s,
                     "sourceSettings": {"scopes": [{"drive": "d1"}]}}
                    """
                        .formatted(
                            UUID.randomUUID(), profile, JSON.writeValueAsString(key.json()))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void theProfileRequirementCanBeSwitchedOnForGoogleDrive() throws Exception {
    mockMvc
        .perform(
            as(put(REQUIREMENT)).content("{\"required\": true, \"ownAddressStock\": \"RUNS\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.profileSupport").value("OPTIONAL"))
        .andExpect(jsonPath("$.profileRequired").value(true));
  }

  private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, "dev-admin")
        .contentType(MediaType.APPLICATION_JSON);
  }
}
