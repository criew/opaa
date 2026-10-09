package io.opaa.connection.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.AuthProperties;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.OidcProviderRegistry;
import io.opaa.directory.OidcProviderService;
import io.opaa.indexing.source.profileprobe.PersonProbeSourceConnector;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Switching a sign-in provider off or deleting it against the Liquibase schema (ADR-0041,
 * Entscheidung 4): without the confirmation both are refused with 409 while a profile admits
 * persons; confirmed, switching off lets the connections of its persons rest and deletes nothing,
 * deleting ends them, deletes their secrets and starts the deletion period. No answer of the
 * administration, alone or together, tells 0 connected persons from 1.
 */
@OpaaIntegrationTest
class ProviderShutdownIntegrationTest {

  private static final String PROVIDERS = "/api/v1/admin/oidc-providers";
  private static final String PROFILES = "/api/v1/admin/connection-profiles";
  private static final String SERVER = "https://provider-shutdown.example.org";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OidcProviderRegistry registry;
  @Autowired private AuthProperties authProperties;
  @Autowired private ConnectionLifecycle lifecycle;

  private final List<UUID> profiles = new ArrayList<>();
  private final List<UUID> providers = new ArrayList<>();
  private final List<UUID> persons = new ArrayList<>();
  private final List<String> personIssuers = new ArrayList<>();
  private UUID profile;
  private UUID provider;
  private String issuer;

  @BeforeEach
  void aProfileForPersonsAndAProvider() throws Exception {
    profile = profileForPersons();
    issuer = "https://idp.example/provider-shutdown-it/" + UUID.randomUUID();
    provider = provider(issuer);
  }

  @AfterEach
  void removeOwnRows() {
    for (int i = 0; i < persons.size(); i++) {
      jdbc.update(
          "UPDATE users SET issuer = ?, last_login_at = now() WHERE id = ?",
          personIssuers.get(i),
          persons.get(i));
      jdbc.update("DELETE FROM connection_person_states WHERE user_id = ?", persons.get(i));
    }
    for (UUID each : profiles) {
      jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", each);
      jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", each);
      ConnectorReleases.withdraw(jdbc, "PROFILE:" + each);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", each.toString());
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", each);
    }
    for (UUID each : providers) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", each.toString());
      jdbc.update("DELETE FROM oidc_providers WHERE id = ?", each);
    }
    jdbc.update(
        "DELETE FROM oidc_provider_removals WHERE issuer_uri_normalized LIKE ?",
        "https://idp.example/provider-shutdown-it/%");
    registry.refresh();
  }

  @Test
  void onlyTheSystemAdministrationSeesTheImpactAndItNamesTheEffectNoNumber() throws Exception {
    mockMvc
        .perform(as("dev-user", get(PROVIDERS + "/" + provider + "/impact")))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(as("dev-admin", get(PROVIDERS + "/" + provider + "/impact")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.confirmationRequired").value(true))
        .andExpect(jsonPath("$.disableEffect").value("CONNECTIONS_REST"))
        .andExpect(jsonPath("$.deleteEffect").value("CONNECTIONS_END"))
        .andExpect(jsonPath("$.connections").doesNotExist());
  }

  /**
   * Two providers, two profiles: the profile list, the impact of both profiles and the impact of
   * both providers read the same before anyone connects, after one person of the first provider
   * connects and after a second person connects on the other profile.
   */
  @Test
  void noAnswerOfTheAdministrationTellsNoConnectedPersonFromOne() throws Exception {
    UUID otherProfile = profileForPersons();
    UUID partner = provider("https://idp.example/provider-shutdown-it/" + UUID.randomUUID());
    List<UUID> both = List.of(profile, otherProfile);
    List<UUID> bothProviders = List.of(provider, partner);

    String nobody = observation(both, bothProviders);
    connect("dev-user", profile);
    personSignsInThroughTheProvider();
    String one = observation(both, bothProviders);
    connect("dev-admin", otherProfile);
    String two = observation(both, bothProviders);

    assertThat(one).isEqualTo(nobody);
    assertThat(two).isEqualTo(nobody);
  }

  @Test
  void switchedOffOnlyAfterTheConfirmationAndThenTheConnectionsRestWithNothingDeleted()
      throws Exception {
    connect("dev-user", profile);
    personSignsInThroughTheProvider();

    mockMvc
        .perform(
            as("dev-admin", post(PROVIDERS + "/" + provider + "/disable"))
                .param("acknowledgeLastProvider", "true"))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.code")
                .value(OidcProviderService.PROVIDER_CONNECTIONS_CONFIRMATION_REQUIRED));
    assertThat(providerEnabled()).isTrue();

    mockMvc
        .perform(
            as("dev-admin", post(PROVIDERS + "/" + provider + "/disable"))
                .param("acknowledgeLastProvider", "true")
                .param("confirmConnections", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false));

    assertThat(tokenRows()).isEqualTo(1);
    assertThat(stateOfAccount()).isEqualTo("CONNECTED");
    assertThat(personState("dormant_since")).isNotNull();
    assertThat(personState("deactivated_since")).isNull();
  }

  @Test
  void deletedOnlyAfterTheConfirmationAndThenTheSecretsAreGoneAndTheDeletionPeriodRuns()
      throws Exception {
    connect("dev-user", profile);
    personSignsInThroughTheProvider();

    mockMvc
        .perform(
            as("dev-admin", delete(PROVIDERS + "/" + provider))
                .param("acknowledgeLastProvider", "true"))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.code")
                .value(OidcProviderService.PROVIDER_CONNECTIONS_CONFIRMATION_REQUIRED));
    assertThat(tokenRows()).isEqualTo(1);

    mockMvc
        .perform(
            as("dev-admin", delete(PROVIDERS + "/" + provider))
                .param("acknowledgeLastProvider", "true")
                .param("confirmConnections", "true"))
        .andExpect(status().isNoContent());

    assertThat(tokenRows()).isZero();
    assertThat(personState("deactivated_since")).isNotNull();
  }

  /**
   * Regression guard for #2289: deleted, re-created with the reconciliation after it failed,
   * deleted again - the deletion period starts with the second deletion, not the first.
   */
  @Test
  void aSecondDeletionStartsTheDeletionPeriodAnewEvenWithoutAReconciliationInBetween()
      throws Exception {
    connect("dev-user", profile);
    personSignsInThroughTheProvider();
    deleteConfirmed(provider);
    provider = provider(issuer);
    // what a failed reconciliation after the re-creation leaves: the start of the first deletion
    Instant firstDeletion = Instant.now().minus(30, ChronoUnit.DAYS);
    jdbc.update(
        "UPDATE connection_person_states SET deactivated_since = ?, dormant_since = NULL"
            + " WHERE user_id = ?",
        Timestamp.from(firstDeletion),
        persons.getFirst());

    Instant beforeSecondDeletion = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    deleteConfirmed(provider);

    assertThat(lifecycle.deactivatedSince(List.of(persons.getFirst())))
        .hasEntrySatisfying(
            persons.getFirst(), start -> assertThat(start).isAfterOrEqualTo(beforeSecondDeletion));
  }

  private void deleteConfirmed(UUID onProvider) throws Exception {
    mockMvc
        .perform(
            as("dev-admin", delete(PROVIDERS + "/" + onProvider))
                .param("acknowledgeLastProvider", "true")
                .param("confirmConnections", "true"))
        .andExpect(status().isNoContent());
  }

  private UUID profileForPersons() throws Exception {
    String body =
        mockMvc
            .perform(
                as("dev-admin", post(PROFILES))
                    .content(
                        """
                        {"name": "Anbieter %s", "sourceType": "PERSON_PROBE",
                         "serverUrl": "%s", "authMethod": "PERSONAL_SECRET", "ownership": "PERSON"}
                        """
                            .formatted(UUID.randomUUID(), SERVER)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID created = UUID.fromString(JsonPath.read(body, "$.id"));
    profiles.add(created);
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + created);
    return created;
  }

  private UUID provider(String issuerUri) throws Exception {
    String body =
        mockMvc
            .perform(
                as("dev-admin", post(PROVIDERS))
                    .content(
                        """
                        {"displayName": "Abschaltung", "issuerUri": "%s",
                         "clientId": "opaa-frontend", "jwkSetUri": "http://127.0.0.1:9/certs"}
                        """
                            .formatted(issuerUri)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID created = UUID.fromString(JsonPath.read(body, "$.id"));
    providers.add(created);
    return created;
  }

  private void connect(String user, UUID onProfile) throws Exception {
    mockMvc.perform(as(user, get("/api/v1/spaces"))).andExpect(status().isOk());
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM users WHERE subject = ? AND issuer = ?",
            UUID.class,
            user,
            authProperties.dev().issuer());
    if (!persons.contains(id)) {
      persons.add(id);
      personIssuers.add(
          jdbc.queryForObject("SELECT issuer FROM users WHERE id = ?", String.class, id));
      jdbc.update("UPDATE users SET last_login_at = now() WHERE id = ?", id);
    }
    mockMvc
        .perform(
            as(user, put("/api/v1/me/connected-accounts/" + onProfile))
                .content(
                    "{\"username\": \"avogt\", \"secret\": \"%s\"}"
                        .formatted(PersonProbeSourceConnector.ACCEPTED_PASSWORD)))
        .andExpect(status().isOk());
  }

  /** Everything the administration is told about the profiles and providers, as one string. */
  private String observation(List<UUID> onProfiles, List<UUID> ofProviders) throws Exception {
    String list = read(get(PROFILES));
    StringBuilder seen = new StringBuilder();
    for (UUID each : onProfiles) {
      String entry = "$[?(@.id == '" + each + "')]";
      seen.append(JsonPath.read(list, entry + ".connectedAccountCount").toString())
          .append(JsonPath.read(list, entry + ".expiredConnectionCount").toString())
          .append(JsonPath.read(list, entry + ".connectionCount").toString())
          .append(read(get(PROFILES + "/" + each + "/impact")));
    }
    for (UUID each : ofProviders) {
      seen.append(read(get(PROVIDERS + "/" + each + "/impact")));
    }
    return seen.toString();
  }

  private String read(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc
        .perform(as("dev-admin", request))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  /** The person's account now belongs to the provider under test. */
  private void personSignsInThroughTheProvider() {
    jdbc.update("UPDATE users SET issuer = ? WHERE id = ?", issuer, persons.getFirst());
  }

  private boolean providerEnabled() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT enabled FROM oidc_providers WHERE id = ?", Boolean.class, provider));
  }

  private int tokenRows() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM connection_tokens WHERE profile_id = ?", Integer.class, profile);
  }

  private String stateOfAccount() {
    return jdbc.queryForObject(
        "SELECT state FROM connected_accounts WHERE profile_id = ?", String.class, profile);
  }

  private Timestamp personState(String column) {
    List<Timestamp> rows =
        jdbc.queryForList(
            "SELECT " + column + " FROM connection_person_states WHERE user_id = ?",
            Timestamp.class,
            persons.getFirst());
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
