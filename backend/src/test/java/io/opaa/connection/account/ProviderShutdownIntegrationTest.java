package io.opaa.connection.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.OidcProviderRegistry;
import io.opaa.directory.OidcProviderService;
import io.opaa.indexing.source.profileprobe.PersonProbeSourceConnector;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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
 * deleting ends them, deletes their secrets and starts the deletion period.
 */
@OpaaIntegrationTest
class ProviderShutdownIntegrationTest {

  private static final String PROVIDERS = "/api/v1/admin/oidc-providers";
  private static final String SERVER = "https://provider-shutdown.example.org";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OidcProviderRegistry registry;

  private UUID profile;
  private UUID person;
  private String personIssuer;
  private UUID provider;
  private String issuer;

  @BeforeEach
  void aConnectedPersonAndAProvider() throws Exception {
    mockMvc.perform(as("dev-user", get("/api/v1/spaces"))).andExpect(status().isOk());
    person =
        jdbc.queryForObject(
            "SELECT id FROM users WHERE email = ?", UUID.class, "dev-user@opaa.local");
    personIssuer =
        jdbc.queryForObject("SELECT issuer FROM users WHERE id = ?", String.class, person);
    jdbc.update("UPDATE users SET last_login_at = now() WHERE id = ?", person);
    String body =
        mockMvc
            .perform(
                as("dev-admin", post("/api/v1/admin/connection-profiles"))
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
    profile = UUID.fromString(JsonPath.read(body, "$.id"));
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + profile);
    mockMvc
        .perform(
            as("dev-user", put("/api/v1/me/connected-accounts/" + profile))
                .content(
                    "{\"username\": \"avogt\", \"secret\": \"%s\"}"
                        .formatted(PersonProbeSourceConnector.ACCEPTED_PASSWORD)))
        .andExpect(status().isOk());

    issuer = "https://idp.example/provider-shutdown-it/" + UUID.randomUUID();
    String created =
        mockMvc
            .perform(
                as("dev-admin", post(PROVIDERS))
                    .content(
                        """
                        {"displayName": "Abschaltung", "issuerUri": "%s",
                         "clientId": "opaa-frontend", "jwkSetUri": "http://127.0.0.1:9/certs"}
                        """
                            .formatted(issuer)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    provider = UUID.fromString(JsonPath.read(created, "$.id"));
  }

  @AfterEach
  void removeOwnRows() {
    jdbc.update(
        "UPDATE users SET issuer = ?, last_login_at = now() WHERE id = ?", personIssuer, person);
    jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connection_person_states WHERE user_id = ?", person);
    ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
    jdbc.update(
        "DELETE FROM audit_log WHERE object_id IN (?, ?)", profile.toString(), provider.toString());
    jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    jdbc.update("DELETE FROM oidc_providers WHERE id = ?", provider);
    registry.refresh();
  }

  @Test
  void onlyTheSystemAdministrationSeesTheImpactAndItNamesNoSinglePerson() throws Exception {
    mockMvc
        .perform(as("dev-user", get(PROVIDERS + "/" + provider + "/impact")))
        .andExpect(status().isForbidden());
    personSignsInThroughTheProvider();

    mockMvc
        .perform(as("dev-admin", get(PROVIDERS + "/" + provider + "/impact")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.confirmationRequired").value(true))
        .andExpect(jsonPath("$.connections").doesNotExist())
        .andExpect(jsonPath("$.privateLibraries").doesNotExist());
  }

  @Test
  void switchedOffOnlyAfterTheConfirmationAndThenTheConnectionsRestWithNothingDeleted()
      throws Exception {
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

  /** The person's account now belongs to the provider under test. */
  private void personSignsInThroughTheProvider() {
    jdbc.update("UPDATE users SET issuer = ? WHERE id = ?", issuer, person);
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
            person);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
