package io.opaa.indexing.source.confluence;

import static io.opaa.test.ProfileLibraries.ADMIN;
import static io.opaa.test.ProfileLibraries.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.ProfileLibraries;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A {@code CONFLUENCE} library through a profile ("Zugang") whose default is the edition: created
 * without naming it, refused when it names another, run with the library's own token, and the
 * edition stays permanent - a profile change or a connection with another edition is refused with a
 * German reason, and releasing the library writes the edition into it.
 */
@OpaaIntegrationTest
class ConfluenceProfileIntegrationTest {

  private static final String PROFILES = "/api/v1/admin/connection-profiles";
  private static final String TOKEN = "pat-geheim";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures fixtures;

  private ProfileLibraries api;
  private FakeConfluenceServer dataCenter;
  private FakeConfluenceServer cloud;

  @BeforeEach
  void setUp() throws IOException {
    api = new ProfileLibraries(mockMvc, jdbc, fixtures);
    dataCenter = new FakeConfluenceServer(ConfluenceEdition.DATA_CENTER);
    dataCenter.addSpace("100", "HR", "Personal");
    dataCenter.addPage(
        "1001",
        "HR",
        "Urlaub",
        null,
        "<p>Der Urlaubsantrag geht an die Leitung.</p>",
        Instant.now());
    dataCenter.addToken("dienst@example.org", TOKEN, null);
    cloud = new FakeConfluenceServer(ConfluenceEdition.CLOUD);
    cloud.addSpace("200", "OPS", "Betrieb");
    cloud.addToken("dienst@example.org", "cloud-token", null);
  }

  @AfterEach
  void tearDown() {
    api.cleanUp();
    dataCenter.close();
    cloud.close();
  }

  @Test
  void aLibraryLeavesTheEditionToTheProfileAndRunsWithItsOwnToken() throws Exception {
    UUID profile = api.createProfile(profileJson("DATA_CENTER"));

    api.postLibrary(libraryJson(profile, "\"edition\": \"CLOUD\", "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("sourceSettings.edition")));
    UUID library = api.createLibrary(libraryJson(profile, ""));
    assertThat(api.stored(library, "source_settings")).contains("HR").doesNotContain("edition");
    mockMvc
        .perform(as(ADMIN, get("/api/v1/libraries/" + library)))
        .andExpect(jsonPath("$.sourceSettings.edition").value("DATA_CENTER"))
        .andExpect(jsonPath("$.sourceSettings.spaces[0].key").value("HR"));
    mockMvc
        .perform(
            as(ADMIN, put("/api/v1/libraries/" + library))
                .content(
                    """
                    {"name": "Wiki", "sourceSettings": {"spaces": [{"key": "HR", "name": "Personal"}]}}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceSettings.edition").value("DATA_CENTER"))
        .andExpect(jsonPath("$.sourceSettings.spaces[0].key").value("HR"));

    String status = api.run(library);

    assertThat(JsonPath.<String>read(status, "$.status")).as(status).isEqualTo("COMPLETED");
    assertThat(JsonPath.<Integer>read(status, "$.documentsIndexedTotal")).as(status).isEqualTo(1);
    assertThat(dataCenter.authorizations()).contains("Bearer " + TOKEN);
  }

  /** The edition is permanent: a profile changing it is refused with the connector's reason. */
  @Test
  void aProfileChangingTheEditionOfConnectedLibrariesIsRefused() throws Exception {
    UUID profile = api.createProfile(profileJson("DATA_CENTER"));
    UUID library = api.createLibrary(libraryJson(profile, ""));
    String change =
        """
        {"name": "Zugang Wiki", "serverUrl": "%s", "authMethod": "PERSONAL_SECRET",
         "ownership": "LIBRARY", "connectorSettings": {"edition": "CLOUD"}}
        """
            .formatted(dataCenter.baseUrl());

    mockMvc
        .perform(as(ADMIN, post(PROFILES + "/" + profile + "/impact")).content(change))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rejections[0].libraryId").value(library.toString()))
        .andExpect(jsonPath("$.rejections[0].category").value("SETTINGS"))
        .andExpect(
            jsonPath("$.rejections[0].message")
                .value(Matchers.containsString("bleibt bei Confluence Data Center")));
    mockMvc
        .perform(as(ADMIN, put(PROFILES + "/" + profile)).content(change))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONNECTION_PROFILE_CHANGE_REJECTED"));
  }

  @Test
  void aLibraryOfAnotherEditionIsNotConnectedAndReleasingKeepsTheEdition() throws Exception {
    UUID profile = api.createProfile(profileJson("DATA_CENTER"));
    UUID own =
        api.createLibrary(
            """
            {"name": "Cloud-Wiki %s", "sourceType": "CONFLUENCE", "sourceUrl": "%s",
             "sourceCredentials": "dienst@example.org:cloud-token",
             "sourceSettings": {"edition": "CLOUD", "spaces": [{"key": "OPS"}]}}
            """
                .formatted(UUID.randomUUID(), cloud.baseUrl()));

    mockMvc
        .perform(
            as(ADMIN, put("/api/v1/libraries/" + own + "/connection-profile"))
                .content(
                    "{\"profileId\": \""
                        + profile
                        + "\", \"sourceUrl\": \""
                        + dataCenter.baseUrl()
                        + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("sourceSettings.edition")));

    UUID connected = api.createLibrary(libraryJson(profile, ""));
    mockMvc
        .perform(as(ADMIN, delete("/api/v1/libraries/" + connected + "/connection-profile")))
        .andExpect(status().isOk());
    assertThat(api.stored(connected, "source_settings")).contains("DATA_CENTER");
  }

  private String profileJson(String edition) {
    return """
        {"name": "Zugang Wiki %s", "sourceType": "CONFLUENCE", "serverUrl": "%s",
         "authMethod": "PERSONAL_SECRET", "ownership": "LIBRARY",
         "connectorSettings": {"edition": "%s"}}
        """
        .formatted(UUID.randomUUID(), dataCenter.baseUrl(), edition);
  }

  private String libraryJson(UUID profile, String edition) {
    return """
        {"name": "Wiki %s", "sourceType": "CONFLUENCE", "sourceUrl": "%s",
         "sourceCredentials": "%s", "connectionProfileId": "%s",
         "sourceSettings": {%s"spaces": [{"key": "HR", "name": "Personal"}]}}
        """
        .formatted(UUID.randomUUID(), dataCenter.baseUrl(), TOKEN, profile, edition);
  }
}
