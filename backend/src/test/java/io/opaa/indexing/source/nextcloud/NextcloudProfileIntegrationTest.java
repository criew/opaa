package io.opaa.indexing.source.nextcloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.ProfileLibraries;
import java.io.IOException;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A {@code NEXTCLOUD} library through a profile ("Zugang"): its address lies under the profile's,
 * it signs in with its own app password, and a run indexes its folders.
 */
@OpaaIntegrationTest
class NextcloudProfileIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures fixtures;

  private ProfileLibraries api;
  private FakeNextcloudServer nextcloud;

  @BeforeEach
  void setUp() throws IOException {
    api = new ProfileLibraries(mockMvc, jdbc, fixtures);
    nextcloud = new FakeNextcloudServer("");
    nextcloud.put("Akten/Bescheid.txt", "Der Bescheid ergeht wie folgt.");
    nextcloud.put("Akten/Protokoll.md", "# Protokoll\n\nDie Sitzung beginnt.");
  }

  @AfterEach
  void tearDown() {
    api.cleanUp();
    nextcloud.close();
  }

  @Test
  void aLibraryRunsUnderTheProfileWithItsOwnAppPassword() throws Exception {
    UUID profile =
        api.createProfile(
            """
            {"name": "Zugang Cloud %s", "sourceType": "NEXTCLOUD", "serverUrl": "%s",
             "authMethod": "PERSONAL_SECRET", "ownership": "LIBRARY"}
            """
                .formatted(UUID.randomUUID(), nextcloud.baseUrl()));

    api.postLibrary(libraryJson(profile, ""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("sourceCredentials")));
    UUID library =
        api.createLibrary(
            libraryJson(profile, "\"sourceCredentials\": \"" + nextcloud.credentials() + "\","));

    String status = api.run(library);

    assertThat(JsonPath.<String>read(status, "$.status")).as(status).isEqualTo("COMPLETED");
    assertThat(JsonPath.<Integer>read(status, "$.documentsIndexedTotal")).as(status).isEqualTo(2);
    assertThat(nextcloud.passwords()).isNotEmpty().containsOnly(FakeNextcloudServer.APP_PASSWORD);
  }

  private String libraryJson(UUID profile, String credentials) {
    return """
        {"name": "Cloud-Ablage %s", "sourceType": "NEXTCLOUD", %s
         "connectionProfileId": "%s", "sourceSettings": {"folders": ["/Akten"]}}
        """
        .formatted(UUID.randomUUID(), credentials, profile);
  }
}
