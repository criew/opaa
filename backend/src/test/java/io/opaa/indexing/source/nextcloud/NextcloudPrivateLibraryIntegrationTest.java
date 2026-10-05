package io.opaa.indexing.source.nextcloud;

import static io.opaa.test.ProfileLibraries.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A person's private Nextcloud library the way the creation assistant builds it (#2167): she
 * connects her own app password on a profile for persons, the profile is offered to her with {@code
 * ownAccount}, the library runs with her secret, and a revoked app password lets the connection
 * expire with the hint to reconnect.
 */
@OpaaIntegrationTest
class NextcloudPrivateLibraryIntegrationTest {

  private static final String OWNER = "dev-user";
  private static final String LIBRARIES = "/api/v1/libraries";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures fixtures;

  private FakeNextcloudServer nextcloud;
  private UUID profile;
  private UUID library;

  @BeforeEach
  void aProfileForPersons() throws Exception {
    nextcloud = new FakeNextcloudServer("");
    nextcloud.put("Akten/Bescheid.txt", "Der Bescheid ergeht wie folgt.");
    nextcloud.put("Akten/Protokoll.md", "# Protokoll\n\nDie Sitzung beginnt.");
    String body =
        body(
            mockMvc
                .perform(
                    as("dev-admin", post("/api/v1/admin/connection-profiles"))
                        .content(
                            """
                            {"name": "Zugang Nextcloud %s", "sourceType": "NEXTCLOUD",
                             "serverUrl": "%s", "authMethod": "PERSONAL_SECRET",
                             "ownership": "PERSON"}
                            """
                                .formatted(UUID.randomUUID(), nextcloud.baseUrl())))
                .andExpect(status().isCreated()));
    profile = UUID.fromString(JsonPath.read(body, "$.id"));
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + profile);
  }

  @AfterEach
  void removeOwnRows() {
    if (library != null) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", library.toString());
      jdbc.update("DELETE FROM asset_grant_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", library);
      fixtures.removeLibraries(library);
    }
    if (profile != null) {
      jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
      jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
      ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
    nextcloud.close();
  }

  @Test
  void theAssistantPathCreatesAPrivateLibraryThatRunsWithHerOwnAppPassword() throws Exception {
    connect().andExpect(status().isOk());

    String options =
        body(
            mockMvc
                .perform(as(OWNER, get("/api/v1/connection-profiles?sourceType=NEXTCLOUD")))
                .andExpect(status().isOk()));
    assertThat(field(options, "ownAccount")).containsExactly(true);
    assertThat(field(options, "creatable")).containsExactly(true);
    assertThat(field(options, "ownership")).containsExactly("PERSON");

    library = createPrivateLibrary();
    mockMvc
        .perform(as(OWNER, get(LIBRARIES + "/" + library)))
        .andExpect(jsonPath("$.privateLibrary").value(true))
        .andExpect(jsonPath("$.sourceBlock").doesNotExist());
    assertThat(
            jdbc.queryForObject(
                "SELECT source_credentials FROM knowledge_libraries WHERE id = ?",
                String.class,
                library))
        .isNull();

    String status = run(library);

    assertThat(JsonPath.<String>read(status, "$.status")).as(status).isEqualTo("COMPLETED");
    assertThat(JsonPath.<Integer>read(status, "$.documentsIndexedTotal")).as(status).isEqualTo(2);
    assertThat(nextcloud.passwords()).containsOnly(FakeNextcloudServer.APP_PASSWORD);
  }

  @Test
  void aRevokedAppPasswordLetsTheConnectionExpireWithTheHintToReconnect() throws Exception {
    connect().andExpect(status().isOk());
    library = createPrivateLibrary();
    nextcloud.rejectCredentials();

    String status = run(library);

    assertThat(JsonPath.<String>read(status, "$.status")).as(status).isEqualTo("FAILED");
    assertThat(failureCategoryOfLastRun(library)).isEqualTo("CREDENTIALS_REJECTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM connected_accounts WHERE profile_id = ?", String.class, profile))
        .isEqualTo("EXPIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type = 'CONNECTION_EXPIRED'"
                    + " AND object_id = ?",
                Integer.class,
                library))
        .isEqualTo(1);
    mockMvc
        .perform(as(OWNER, get(LIBRARIES + "/" + library)))
        .andExpect(jsonPath("$.sourceBlock.reason").value("EXPIRED"))
        .andExpect(jsonPath("$.sourceBlock.action").value("CONNECT_OWN_ACCOUNT"))
        .andExpect(
            jsonPath("$.sourceBlock.notice").value(org.hamcrest.Matchers.containsString("neu")));
  }

  @Test
  void aRefusedAppPasswordIsNotStored() throws Exception {
    nextcloud.rejectCredentials();

    connect().andExpect(status().isBadRequest());

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM connected_accounts WHERE profile_id = ?",
                Integer.class,
                profile))
        .isZero();
  }

  private ResultActions connect() throws Exception {
    return mockMvc.perform(
        as(OWNER, put("/api/v1/me/connected-accounts/" + profile))
            .content(
                "{\"username\": \"%s\", \"secret\": \"%s\"}"
                    .formatted(FakeNextcloudServer.LOGIN, FakeNextcloudServer.APP_PASSWORD)));
  }

  private UUID createPrivateLibrary() throws Exception {
    String body =
        body(
            mockMvc
                .perform(
                    as(OWNER, post(LIBRARIES))
                        .content(
                            """
                            {"name": "Meine Cloud %s", "sourceType": "NEXTCLOUD",
                             "privateLibrary": true, "connectionProfileId": "%s",
                             "sourceSettings": {"folders": ["/Akten"]}}
                            """
                                .formatted(UUID.randomUUID(), profile)))
                .andExpect(status().isCreated()));
    return UUID.fromString(JsonPath.read(body, "$.id"));
  }

  private String run(UUID library) throws Exception {
    int before = runsOf(library);
    mockMvc
        .perform(as(OWNER, post(LIBRARIES + "/" + library + "/indexing")))
        .andExpect(status().isAccepted());
    String[] status = new String[1];
    await()
        .atMost(Duration.ofSeconds(60))
        .until(
            () -> {
              status[0] =
                  body(
                      mockMvc.perform(
                          as(OWNER, get(LIBRARIES + "/" + library + "/indexing/status"))));
              String state = JsonPath.read(status[0], "$.status");
              return runsOf(library) > before
                  && (state.equals("COMPLETED") || state.equals("FAILED"));
            });
    return status[0];
  }

  private List<Object> field(String options, String name) {
    return JsonPath.read(options, "$[?(@.id == '" + profile + "')]." + name);
  }

  private int runsOf(UUID library) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM indexing_jobs WHERE library_id = ?", Integer.class, library);
  }

  private String failureCategoryOfLastRun(UUID library) {
    return jdbc.queryForObject(
        "SELECT failure_category FROM indexing_jobs WHERE library_id = ?"
            + " ORDER BY started_at DESC LIMIT 1",
        String.class,
        library);
  }

  private static String body(ResultActions result) throws Exception {
    return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
  }
}
