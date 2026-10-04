package io.opaa.connection.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.connection.profile.SecretTarget;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.NewSecret;
import io.opaa.connection.token.SecretOwner.PersonOwned;
import io.opaa.indexing.source.profileprobe.PersonProbeSourceConnector;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The expiry watch against the Liquibase schema: a named end of a person's OAuth consent is warned
 * of once to that person, 14 days ahead; a new consent anew, a lifetime shorter than that never. A
 * client secret's expiry date is warned of once to the system administration, without a new version
 * of the profile.
 */
@OpaaIntegrationTest
class ConnectionExpiryWatchIntegrationTest {

  private static final String ME = "/api/v1/me/connected-accounts";
  private static final String ADMIN = "/api/v1/admin/connection-profiles";
  private static final String SERVER = "https://expiry.example.org";
  private static final String TARGET = new SecretTarget(SERVER, null).key();

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ConnectionSecrets secrets;
  @Autowired private ConnectionExpiryWatch watch;
  @Autowired private PlatformTransactionManager transactionManager;

  private UUID profile;
  private UUID person;
  private UUID admin;

  @BeforeEach
  void aPersonConnectedOnAProfile() throws Exception {
    mockMvc.perform(as("dev-user", get("/api/v1/spaces"))).andExpect(status().isOk());
    mockMvc.perform(as("dev-admin", get("/api/v1/spaces"))).andExpect(status().isOk());
    person = userId("dev-user@opaa.local");
    admin = userId("admin@opaa.local");
    String body =
        mockMvc
            .perform(
                as("dev-admin", post(ADMIN))
                    .content(
                        """
                        {"name": "Ablauf %s", "sourceType": "PERSON_PROBE",
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
            as("dev-user", put(ME + "/" + profile))
                .content(
                    "{\"username\": \"avogt\", \"secret\": \"%s\"}"
                        .formatted(PersonProbeSourceConnector.ACCEPTED_PASSWORD)))
        .andExpect(status().isOk());
  }

  @AfterEach
  void removeOwnRows() {
    jdbc.update("DELETE FROM notifications WHERE object_id = ?", profile);
    jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
    ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
    jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
    jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
  }

  @Test
  void aConsentEndingWithinFourteenDaysIsWarnedOfOnceToItsPerson() throws Exception {
    grantEndingIn(Duration.ofDays(20));
    Instant end = timePassesUntilTheEndIsIn(Duration.ofDays(10));

    watch.warn();
    watch.warn();

    assertThat(notifications("CONNECTION_EXPIRING")).containsExactly(person);
    assertThat(
            jdbc.queryForObject(
                "SELECT body FROM notifications WHERE object_id = ? AND type ="
                    + " 'CONNECTION_EXPIRING'",
                String.class,
                profile))
        .contains("Verbundene Konten")
        .doesNotContain("refresh", "access");
    String overview =
        mockMvc
            .perform(as("dev-user", get(ME)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String expiresAt =
        JsonPath.read(overview, "$.accounts[?(@.profileId == '" + profile + "')].expiresAt")
            .toString();
    assertThat(expiresAt).contains(end.toString().substring(0, 16));
  }

  @Test
  void aConsentEndingLaterIsNotWarnedOfYetAndANewEndIsWarnedOfAnew() {
    grantEndingIn(Duration.ofDays(20));
    watch.warn();
    assertThat(notifications("CONNECTION_EXPIRING")).isEmpty();

    timePassesUntilTheEndIsIn(Duration.ofDays(5));
    watch.warn();
    // a reconnection is a new consent with a new end
    grantEndingIn(Duration.ofDays(30));
    timePassesUntilTheEndIsIn(Duration.ofDays(3));
    watch.warn();

    assertThat(notifications("CONNECTION_EXPIRING")).containsExactly(person, person);
  }

  @Test
  void aConsentWhoseLifetimeIsShorterThanTheWarningIsNeverWarnedOf() {
    grantEndingIn(Duration.ofMinutes(30));

    watch.warn();

    assertThat(notifications("CONNECTION_EXPIRING")).isEmpty();
  }

  @Test
  void aClientSecretExpiringSoonIsWarnedOfOnceToTheAdministrationWithoutANewVersion() {
    jdbc.update(
        "UPDATE connection_profiles SET client_secret_ciphertext = 'enc:v1:x',"
            + " client_secret_expires_on = current_date + 3 WHERE id = ?",
        profile);
    long version = version();

    watch.warn();
    watch.warn();

    assertThat(notifications("CONNECTION_PROFILE_SECRET_EXPIRING")).containsOnlyOnce(admin);
    assertThat(notifications("CONNECTION_PROFILE_SECRET_EXPIRING")).doesNotContain(person);
    // a version change would turn every consent started on the profile into a 409
    assertThat(version()).isEqualTo(version);
  }

  private Instant grantEndingIn(Duration ahead) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant end = now.plus(ahead);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                secrets.store(
                    new PersonOwned(profile, person),
                    new NewSecret.OAuthGrant(
                        "refresh-" + UUID.randomUUID(),
                        "access-" + UUID.randomUUID(),
                        now.plus(Duration.ofHours(1)),
                        end),
                    TARGET));
    return end;
  }

  /** Moves the stored end as the clock would, without a renewal; returns the new end. */
  private Instant timePassesUntilTheEndIsIn(Duration ahead) {
    Instant end = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(ahead);
    jdbc.update(
        "UPDATE connection_tokens SET expires_at = ? WHERE connected_account_id IN"
            + " (SELECT id FROM connected_accounts WHERE profile_id = ?)",
        Timestamp.from(end),
        profile);
    return end;
  }

  private List<UUID> notifications(String type) {
    return jdbc.queryForList(
        "SELECT recipient_user_id FROM notifications WHERE object_id = ? AND type = ?"
            + " ORDER BY created_at",
        UUID.class,
        profile,
        type);
  }

  private long version() {
    return jdbc.queryForObject(
        "SELECT version FROM connection_profiles WHERE id = ?", Long.class, profile);
  }

  private UUID userId(String email) {
    return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
