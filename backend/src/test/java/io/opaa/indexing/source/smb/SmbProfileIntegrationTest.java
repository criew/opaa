package io.opaa.indexing.source.smb;

import static io.opaa.test.ProfileLibraries.ADMIN;
import static io.opaa.test.ProfileLibraries.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.ProfileLibraries;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * An {@code SMB} library through a profile ("Zugang") against a real Samba: the profile's address
 * is an {@code smb://} server without proxy or skipped certificate check, a library on it runs with
 * its own service account, and another share asks for the credentials again.
 */
@OpaaIntegrationTest
class SmbProfileIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String PROFILES = "/api/v1/admin/connection-profiles";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures fixtures;

  private ProfileLibraries api;
  private SambaFixture samba;
  private String folder;

  @BeforeEach
  void setUp() {
    api = new ProfileLibraries(mockMvc, jdbc, fixtures);
    samba = SambaFixture.get();
    folder = "Zugang " + UUID.randomUUID();
    samba.put(folder + "/Bescheid.txt", "Der Bescheid ergeht wie folgt.");
  }

  @AfterEach
  void tearDown() {
    api.cleanUp();
  }

  @Test
  void aProfileOfAFileServerTakesNeitherProxyNorASkippedCertificateCheck() throws Exception {
    mockMvc
        .perform(
            as(ADMIN, post(PROFILES)).content(profileJson(", \"sourceProxy\": \"p.example:8080\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("http:// oder https://")));
    mockMvc
        .perform(as(ADMIN, post(PROFILES)).content(profileJson(", \"sourceInsecureSsl\": true")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("http:// oder https://")));
    mockMvc
        .perform(as(ADMIN, post(PROFILES)).content(profileJson("").replace("smb://", "https://")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("smb://")));
  }

  @Test
  void aLibraryRunsUnderTheProfileAndAnotherShareAsksForTheCredentialsAgain() throws Exception {
    UUID profile = api.createProfile(profileJson(""));
    UUID library =
        api.createLibrary(
            """
            {"name": "Freigabe %s", "sourceType": "SMB", "sourceUrl": %s,
             "sourceCredentials": %s, "connectionProfileId": "%s",
             "sourceSettings": {"folders": [%s]}}
            """
                .formatted(
                    UUID.randomUUID(),
                    JSON.writeValueAsString(samba.url(SambaFixture.SHARE)),
                    JSON.writeValueAsString(samba.credentials()),
                    profile,
                    JSON.writeValueAsString("/" + folder)));

    String status = api.run(library);

    assertThat(JsonPath.<String>read(status, "$.status")).as(status).isEqualTo("COMPLETED");
    assertThat(JsonPath.<Integer>read(status, "$.documentsIndexedTotal")).as(status).isEqualTo(1);

    String otherShare = JSON.writeValueAsString(samba.url(SambaFixture.LINK_SHARE));
    mockMvc
        .perform(
            as(ADMIN, put("/api/v1/libraries/" + library))
                .content("{\"name\": \"Freigabe\", \"sourceUrl\": " + otherShare + "}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            as(ADMIN, put("/api/v1/libraries/" + library))
                .content(
                    "{\"name\": \"Freigabe\", \"sourceUrl\": "
                        + otherShare
                        + ", \"sourceCredentials\": "
                        + JSON.writeValueAsString(samba.credentials())
                        + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceCredentialsSet").value(true));
  }

  private String profileJson(String extra) {
    return """
        {"name": "Zugang Freigabe %s", "sourceType": "SMB",
         "serverUrl": "smb://%s:%d", "authMethod": "PERSONAL_SECRET", "ownership": "LIBRARY"%s}
        """
        .formatted(UUID.randomUUID(), samba.host(), samba.port(), extra);
  }
}
