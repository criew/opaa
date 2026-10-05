package io.opaa.integration.nextcloud;

import static io.opaa.test.ProfileLibraries.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Connected accounts against a real Nextcloud (#2167): two persons each connect their own app
 * password on one profile for persons and run a private library; each holds only what her own
 * Nextcloud account sees, and neither sees the other's library. A revoked app password lets the
 * connection expire.
 */
@OpaaIntegrationTest
class NextcloudConnectedAccountsIntegrationTest {

  private static final AtomicInteger SCENARIOS = new AtomicInteger();
  private static final String LIBRARIES = "/api/v1/libraries";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures fixtures;

  private final NextcloudFixture nextcloud = NextcloudFixture.get();
  private final List<UUID> libraries = new ArrayList<>();
  private UUID profile;
  private int scenario;

  @BeforeEach
  void aProfileForPersons() throws Exception {
    scenario = SCENARIOS.incrementAndGet();
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
    for (UUID library : libraries) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", library.toString());
      jdbc.update("DELETE FROM asset_grant_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", library);
    }
    fixtures.removeLibraries(libraries.toArray(UUID[]::new));
    libraries.clear();
    jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
    ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
    jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
    jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
  }

  @Test
  void eachPrivateLibraryHoldsOnlyWhatItsOwnersAccountSeesAndNoOneElseSeesIt() throws Exception {
    String bob = "bob" + scenario;
    String carol = "carol" + scenario;
    String bobPassword = nextcloud.appPasswordOf(bob);
    String carolPassword = nextcloud.appPasswordOf(carol);
    store(bob, "/Akten/bob-bescheid.txt", "Der Bescheid von Bob.");
    store(carol, "/Akten/carol-protokoll.txt", "Das Protokoll von Carol.");
    connect("dev-user", bob, bobPassword).andExpect(status().isOk());
    connect("dev-admin", carol, carolPassword).andExpect(status().isOk());

    UUID ofBob = createPrivateLibrary("dev-user");
    UUID ofCarol = createPrivateLibrary("dev-admin");
    assertThat(JsonPath.<String>read(run("dev-user", ofBob), "$.status")).isEqualTo("COMPLETED");
    assertThat(JsonPath.<String>read(run("dev-admin", ofCarol), "$.status")).isEqualTo("COMPLETED");

    assertThat(fileNamesOf(ofBob)).containsExactly("bob-bescheid.txt");
    assertThat(fileNamesOf(ofCarol)).containsExactly("carol-protokoll.txt");
    mockMvc.perform(as("dev-admin", get(LIBRARIES + "/" + ofBob))).andExpect(status().isNotFound());
    mockMvc
        .perform(as("dev-user", get(LIBRARIES + "/" + ofCarol)))
        .andExpect(status().isNotFound());
    assertThat(body(mockMvc.perform(as("dev-admin", get(LIBRARIES)))))
        .doesNotContain(ofBob.toString());
    assertThat(body(mockMvc.perform(as("dev-user", get(LIBRARIES)))))
        .doesNotContain(ofCarol.toString());
  }

  @Test
  void aRevokedAppPasswordLetsTheConnectionExpire() throws Exception {
    String dora = "dora" + scenario;
    String password = nextcloud.appPasswordOf(dora);
    store(dora, "/Akten/dora.txt", "Doras Akte.");
    connect("dev-user", dora, password).andExpect(status().isOk());
    UUID library = createPrivateLibrary("dev-user");
    assertThat(JsonPath.<String>read(run("dev-user", library), "$.status")).isEqualTo("COMPLETED");

    nextcloud.revokeAppPasswords(dora);
    String status = run("dev-user", library);

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
    assertThat(fileNamesOf(library)).containsExactly("dora.txt");
  }

  private void store(String user, String path, String text) {
    nextcloud.put(user, path, text.getBytes(StandardCharsets.UTF_8));
  }

  private ResultActions connect(String person, String user, String appPassword) throws Exception {
    return mockMvc.perform(
        as(person, put("/api/v1/me/connected-accounts/" + profile))
            .content("{\"username\": \"%s\", \"secret\": \"%s\"}".formatted(user, appPassword)));
  }

  private UUID createPrivateLibrary(String person) throws Exception {
    String body =
        body(
            mockMvc
                .perform(
                    as(person, post(LIBRARIES))
                        .content(
                            """
                            {"name": "Meine Cloud %s", "sourceType": "NEXTCLOUD",
                             "privateLibrary": true, "connectionProfileId": "%s",
                             "sourceSettings": {"folders": ["/Akten"]}}
                            """
                                .formatted(UUID.randomUUID(), profile)))
                .andExpect(status().isCreated()));
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
    libraries.add(id);
    return id;
  }

  private String run(String person, UUID library) throws Exception {
    int before = runsOf(library);
    mockMvc
        .perform(as(person, post(LIBRARIES + "/" + library + "/indexing")))
        .andExpect(status().isAccepted());
    String[] status = new String[1];
    await()
        .atMost(Duration.ofSeconds(120))
        .until(
            () -> {
              status[0] =
                  body(
                      mockMvc.perform(
                          as(person, get(LIBRARIES + "/" + library + "/indexing/status"))));
              String state = JsonPath.read(status[0], "$.status");
              return runsOf(library) > before
                  && (state.equals("COMPLETED") || state.equals("FAILED"));
            });
    return status[0];
  }

  private List<String> fileNamesOf(UUID library) {
    return jdbc.queryForList(
        "SELECT file_name FROM documents WHERE library_id = ? ORDER BY file_name",
        String.class,
        library);
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
