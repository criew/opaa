package io.opaa.indexing.source.s3;

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
import io.opaa.indexing.source.SourceSyncState;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.s3.S3Credentials;
import io.opaa.s3.S3TestFixture;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.ProfileLibraries;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * An {@code S3} library through a profile ("Zugang") whose defaults are region and addressing
 * style, against a real object store: created without them and run with them; another region is
 * refused; a changed default passes the connector - its target check refuses a bucket host it
 * cannot reach - and discards the run state; releasing keeps the defaults. A new server address is
 * checked in full once the credentials are entered again, and no run reaches it before.
 */
@OpaaIntegrationTest
class S3ProfileIntegrationTest {

  private static final String PROFILES = "/api/v1/admin/connection-profiles";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures fixtures;
  @Autowired private SourceSyncStateRepository syncStates;

  private ProfileLibraries api;
  private S3TestFixture store;
  private String bucket;
  private String profileName;

  @BeforeEach
  void setUp() {
    api = new ProfileLibraries(mockMvc, jdbc, fixtures);
    store = S3TestFixture.get();
    bucket = store.createBucket("opaa-zugang");
    store.putObject(bucket, "akten/Bescheid.txt", "Der Bescheid ergeht wie folgt.", "text/plain");
    profileName = "Zugang Speicher " + UUID.randomUUID();
  }

  @AfterEach
  void tearDown() {
    api.cleanUp();
  }

  @Test
  void aLibraryLeavesRegionAndAddressingStyleToTheProfileAndRunsWithThem() throws Exception {
    UUID profile = api.createProfile(profileJson(true));

    api.postLibrary(libraryJson(profile, "\"region\": \"eu-central-1\", "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("sourceSettings.region")));
    UUID library = api.createLibrary(libraryJson(profile, ""));
    assertThat(api.stored(library, "source_settings"))
        .contains(bucket)
        .doesNotContain("region")
        .doesNotContain("pathStyle");
    mockMvc
        .perform(as(ADMIN, get("/api/v1/libraries/" + library)))
        .andExpect(jsonPath("$.sourceSettings.region").value(S3TestFixture.REGION))
        .andExpect(jsonPath("$.sourceSettings.pathStyle").value(true))
        .andExpect(jsonPath("$.sourceSettings.scopes[0].bucket").value(bucket));
    mockMvc
        .perform(
            as(ADMIN, put("/api/v1/libraries/" + library))
                .content(
                    "{\"name\": \"Ablage\", \"sourceSettings\": {\"scopes\": [{\"bucket\": \""
                        + bucket
                        + "\"}]}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceSettings.region").value(S3TestFixture.REGION))
        .andExpect(jsonPath("$.sourceSettings.scopes[0].bucket").value(bucket));
    assertThat(api.stored(library, "source_settings")).doesNotContain("region");

    String status = api.run(library);

    assertThat(JsonPath.<String>read(status, "$.status")).as(status).isEqualTo("COMPLETED");
    assertThat(JsonPath.<Integer>read(status, "$.documentsIndexedTotal")).as(status).isEqualTo(1);
  }

  @Test
  void aChangedDefaultPassesTheConnectorDiscardsTheRunStateAndReleasingKeepsIt() throws Exception {
    UUID profile = api.createProfile(profileJson(true));
    UUID library = api.createLibrary(libraryJson(profile, ""));
    syncStates.save(new SourceSyncState(library));

    mockMvc
        .perform(as(ADMIN, put(PROFILES + "/" + profile)).content(change("eu-west-1", true, null)))
        .andExpect(status().isOk());
    assertThat(syncStates.findByLibraryId(library)).isEmpty();
    assertThat(sourceUpdates(library)).anyMatch(changed -> changed.contains("s3Settings"));

    // virtual-host addressing would reach <bucket>.<host>, which the target check refuses
    String virtualHost = change("eu-west-1", false, null);
    mockMvc
        .perform(as(ADMIN, post(PROFILES + "/" + profile + "/impact")).content(virtualHost))
        .andExpect(jsonPath("$.rejections[0].libraryId").value(library.toString()))
        .andExpect(jsonPath("$.rejections[0].category").value("CONNECTION"));
    mockMvc
        .perform(as(ADMIN, put(PROFILES + "/" + profile)).content(virtualHost))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONNECTION_PROFILE_CHANGE_REJECTED"));

    mockMvc
        .perform(as(ADMIN, delete("/api/v1/libraries/" + library + "/connection-profile")))
        .andExpect(status().isOk());
    assertThat(api.stored(library, "source_settings").replace(" ", ""))
        .contains("\"region\":\"eu-west-1\"")
        .contains("\"pathStyle\":true");
  }

  /**
   * A new server address discards the secrets, so the connector can check only the settings then;
   * the new address is checked in full when the credentials are entered again, and until then a run
   * is refused before it reaches the source.
   */
  @Test
  void aNewServerAddressIsCheckedInFullOnceTheCredentialsAreEnteredAgain() throws Exception {
    UUID profile = api.createProfile(profileJson(true));
    UUID library = api.createLibrary(libraryJson(profile, ""));
    String unreachable = "http://s3-neu.invalid:9000";

    mockMvc
        .perform(
            as(ADMIN, put(PROFILES + "/" + profile))
                .content(change("us-east-1", true, unreachable)))
        .andExpect(status().isOk());
    assertThat(api.stored(library, "source_url")).startsWith(unreachable);
    assertThat(api.stored(library, "source_credentials")).isNull();

    String refused = api.run(library);
    assertThat(JsonPath.<String>read(refused, "$.status")).as(refused).isEqualTo("FAILED");
    assertThat(JsonPath.<String>read(refused, "$.message"))
        .as("refused before the source is reached")
        .contains("Verbindung getrennt")
        .contains("keine Zugangsdaten hinterlegt");
    assertThat(JsonPath.<Integer>read(refused, "$.totalDocuments")).isZero();

    mockMvc
        .perform(
            as(ADMIN, put("/api/v1/libraries/" + library))
                .content(
                    "{\"name\": \"Ablage\", \"sourceUrl\": \""
                        + api.stored(library, "source_url")
                        + "\", \"sourceCredentials\": \""
                        + credentials()
                        + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("s3-neu.invalid")));
    assertThat(api.stored(library, "source_credentials")).isNull();
  }

  private List<String> sourceUpdates(UUID library) {
    return jdbc.queryForList(
        "SELECT after::text FROM audit_log WHERE object_id = ? AND event_type ="
            + " 'LIBRARY_SOURCE_UPDATED'",
        String.class,
        library.toString());
  }

  private String credentials() {
    S3Credentials root = store.rootCredentials();
    return root.accessKey() + ":" + root.secretKey();
  }

  private String profileJson(boolean pathStyle) {
    return """
        {"name": "%s", "sourceType": "S3", "serverUrl": "%s",
         "authMethod": "PERSONAL_SECRET", "ownership": "LIBRARY",
         "connectorSettings": {"region": "%s", "pathStyle": %s}}
        """
        .formatted(profileName, store.endpoint(), S3TestFixture.REGION, pathStyle);
  }

  private String change(String region, boolean pathStyle, String serverUrl) {
    return """
        {"name": "%s", "serverUrl": "%s", "authMethod": "PERSONAL_SECRET", "ownership": "LIBRARY",
         "connectorSettings": {"region": "%s", "pathStyle": %s}, "confirmDiscard": true}
        """
        .formatted(
            profileName, serverUrl == null ? store.endpoint() : serverUrl, region, pathStyle);
  }

  private String libraryJson(UUID profile, String settings) {
    return """
        {"name": "Ablage %s", "sourceType": "S3", "sourceCredentials": "%s",
         "connectionProfileId": "%s",
         "sourceSettings": {%s"scopes": [{"bucket": "%s"}]}}
        """
        .formatted(UUID.randomUUID(), credentials(), profile, settings, bucket);
  }
}
