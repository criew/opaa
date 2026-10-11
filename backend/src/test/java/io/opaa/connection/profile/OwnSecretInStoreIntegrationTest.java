package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SecretKind;
import io.opaa.indexing.source.SourceConnectionResolver;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The own secret of a library on a profile lies in the token store with the library as owner, its
 * column stays empty: written on creation and change, moved in on connecting and back on release,
 * carried along to another profile and gone with another sign-in method.
 */
@OpaaIntegrationTest
class OwnSecretInStoreIntegrationTest {

  private static final String ADMIN = "/api/v1/admin/connection-profiles";
  private static final String SERVER = "https://probe.example.org";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private SourceConnectionResolver resolver;
  @Autowired private TransactionTemplate transactions;

  private final List<UUID> profiles = new ArrayList<>();
  private final List<UUID> libraries = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (UUID library : libraries) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", library.toString());
    }
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    for (UUID profile : profiles) {
      ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
  }

  @Test
  void aLibraryCreatedAndChangedOnAProfileKeepsItsSecretInTheStore() throws Exception {
    UUID profile = profile("PERSONAL_SECRET");
    UUID library = libraryOn(profile, "nutzer:geheim");

    assertThat(column(library)).isNull();
    assertThat(row(library)).containsEntry("kind", "PERSONAL_SECRET");
    assertThat(row(library)).containsEntry("profile_id", profile);
    assertThat((String) row(library).get("secret_ciphertext")).startsWith("enc:");
    assertThat(secretOf(library))
        .isEqualTo(new Secret(SecretKind.PERSONAL_SECRET, "nutzer:geheim"));
    mockMvc
        .perform(as("dev-user", get("/api/v1/libraries/" + library)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceCredentialsSet").value(true));

    mockMvc
        .perform(
            as("dev-user", put("/api/v1/libraries/" + library))
                .content(
                    """
                    {"name": "Umbenannt", "sourceUrl": "%s/a", "sourceCredentials": "nutzer:neu"}
                    """
                        .formatted(SERVER)))
        .andExpect(status().isOk());

    assertThat(column(library)).isNull();
    assertThat(secretOf(library)).isEqualTo(new Secret(SecretKind.PERSONAL_SECRET, "nutzer:neu"));

    mockMvc
        .perform(
            as("dev-user", put("/api/v1/libraries/" + library))
                .content("{\"name\": \"Umbenannt\", \"sourceUrl\": \"%s/b\"}".formatted(SERVER)))
        .andExpect(status().isOk());

    assertThat(secretOf(library)).isEqualTo(new Secret(SecretKind.PERSONAL_SECRET, "nutzer:neu"));
  }

  @Test
  void connectingMovesTheSecretIntoTheStoreAndReleasingBackIntoTheColumn() throws Exception {
    UUID profile = profile("PERSONAL_SECRET");
    UUID library = ownLibrary("eigen:geheim");
    assertThat(column(library)).isNotNull();

    mockMvc
        .perform(
            as("dev-admin", put("/api/v1/libraries/" + library + "/connection-profile"))
                .content("{\"profileId\": \"" + profile + "\"}"))
        .andExpect(status().isOk());

    assertThat(column(library)).isNull();
    assertThat(row(library)).containsEntry("profile_id", profile);
    assertThat(secretOf(library)).isEqualTo(new Secret(SecretKind.PERSONAL_SECRET, "eigen:geheim"));

    mockMvc
        .perform(as("dev-admin", delete("/api/v1/libraries/" + library + "/connection-profile")))
        .andExpect(status().isOk());

    assertThat(rows(library)).isZero();
    assertThat(column(library)).isNotNull();
    assertThat(secretOf(library)).isEqualTo(new Secret(SecretKind.PERSONAL_SECRET, "eigen:geheim"));
  }

  @Test
  void theSecretFollowsTheLibraryToAnotherProfileAndGoesOnlyWithThatOne() throws Exception {
    UUID first = profile("PERSONAL_SECRET");
    UUID second = profile("PERSONAL_SECRET");
    UUID library = libraryOn(first, "nutzer:geheim");

    mockMvc
        .perform(
            as("dev-admin", put("/api/v1/libraries/" + library + "/connection-profile"))
                .content("{\"profileId\": \"" + second + "\"}"))
        .andExpect(status().isOk());

    assertThat(row(library)).containsEntry("profile_id", second);
    assertThat(secretOf(library))
        .isEqualTo(new Secret(SecretKind.PERSONAL_SECRET, "nutzer:geheim"));

    mockMvc
        .perform(as("dev-admin", delete(ADMIN + "/" + first)))
        .andExpect(status().is2xxSuccessful());
    assertThat(rows(library)).isOne();

    mockMvc
        .perform(as("dev-admin", post(ADMIN + "/" + second + "/disconnect-all")))
        .andExpect(status().is2xxSuccessful());
    assertThat(rows(library)).isZero();
  }

  @Test
  void anotherSignInMethodDiscardsTheSecretInTheStore() throws Exception {
    UUID profile = profile("PERSONAL_SECRET");
    UUID library = libraryOn(profile, "nutzer:geheim");
    String name =
        JsonPath.read(
            mockMvc
                .perform(as("dev-admin", get(ADMIN + "/" + profile)))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8),
            "$.name");

    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + profile))
                .content(
                    """
                    {"name": "%s", "serverUrl": "%s", "authMethod": "NONE",
                     "ownership": "LIBRARY", "confirmDiscard": true}
                    """
                        .formatted(name, SERVER)))
        .andExpect(status().isOk());

    assertThat(rows(library)).isZero();
    assertThat(column(library)).isNull();
  }

  private Secret secretOf(UUID library) {
    return transactions.execute(
        status -> resolver.currentSecret(libraryRepository.findById(library).orElseThrow()));
  }

  private String column(UUID library) {
    return jdbc.queryForObject(
        "SELECT source_credentials FROM knowledge_libraries WHERE id = ?", String.class, library);
  }

  private Map<String, Object> row(UUID library) {
    return jdbc.queryForMap(
        "SELECT kind, profile_id, secret_ciphertext FROM connection_tokens WHERE library_id = ?",
        library);
  }

  private long rows(UUID library) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM connection_tokens WHERE library_id = ?", Long.class, library);
  }

  private UUID profile(String method) throws Exception {
    UUID id =
        created(
            as("dev-admin", post(ADMIN))
                .content(
                    """
                    {"name": "Zugang Geheimnis %s", "sourceType": "PROFILE_PROBE",
                     "serverUrl": "%s", "authMethod": "%s", "ownership": "LIBRARY"}
                    """
                        .formatted(UUID.randomUUID(), SERVER, method)),
            profiles);
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + id);
    return id;
  }

  private UUID libraryOn(UUID profile, String credentials) throws Exception {
    return created(
        as("dev-user", post("/api/v1/libraries"))
            .content(
                """
                {"name": "Bibliothek %s", "sourceType": "PROFILE_PROBE", "sourceUrl": "%s/a",
                 "sourceCredentials": "%s", "connectionProfileId": "%s"}
                """
                    .formatted(UUID.randomUUID(), SERVER, credentials, profile)),
        libraries);
  }

  private UUID ownLibrary(String credentials) throws Exception {
    return created(
        as("dev-admin", post("/api/v1/libraries"))
            .content(
                """
                {"name": "Eigen %s", "sourceType": "PROFILE_PROBE", "sourceUrl": "%s/x",
                 "sourceCredentials": "%s"}
                """
                    .formatted(UUID.randomUUID(), SERVER, credentials)),
        libraries);
  }

  private UUID created(MockHttpServletRequestBuilder request, List<UUID> into) throws Exception {
    String body =
        mockMvc
            .perform(request)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
    into.add(id);
    return id;
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
