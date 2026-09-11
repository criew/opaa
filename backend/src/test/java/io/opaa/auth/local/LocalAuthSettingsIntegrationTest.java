package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The settings endpoint of the local account management (ADR-0033, Entscheidungen 3, 4 and 10) in
 * the shared local-auth context, which has no public base URL on purpose: the switch runs through
 * the provider row and ends the sessions of regular accounts (quoted in the response), the policy
 * values are bounded and audited by their changed keys, and the two link flows cannot be switched
 * on without {@code OPAA_PUBLIC_BASE_URL}.
 */
@OpaaLocalAuthMockMvcTest
class LocalAuthSettingsIntegrationTest {

  private static final String SETTINGS = "/api/v1/admin/local-auth-settings";
  private static final String ME = "/api/v1/auth/me";

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private LocalAuthSettingsRepository settings;
  @Autowired private JdbcTemplate jdbc;

  private LocalAccountFixtures fixtures;
  private LocalAccount admin;
  private String adminBearer;

  @BeforeEach
  void setUp() throws Exception {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
    resetSettings();
    jdbc.update(
        "DELETE FROM audit_log WHERE event_type IN ('LOCAL_ACCOUNTS_SETTINGS_CHANGED',"
            + " 'LOCAL_ACCOUNTS_ENABLED', 'LOCAL_ACCOUNTS_DISABLED', 'LOCAL_SESSION_REVOKED')");
    admin = fixtures.activeAdmin("verwaltung-" + UUID.randomUUID() + "@stadt.example");
    adminBearer = bearer(login(admin.email(), LocalAccountFixtures.PASSWORD));
  }

  @AfterEach
  void tearDown() {
    resetSettings();
    fixtures.cleanUp();
  }

  @Test
  void theSettingsCarryTheSwitchTheDefaultsAndTheBaseUrlState() throws Exception {
    mockMvc
        .perform(get(SETTINGS).header(HttpHeaders.AUTHORIZATION, adminBearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.selfRegistrationEnabled").value(false))
        .andExpect(jsonPath("$.selfRegistrationAllowedDomains").isEmpty())
        .andExpect(jsonPath("$.passwordResetEnabled").value(true))
        .andExpect(jsonPath("$.passwordMinLength").value(12))
        .andExpect(jsonPath("$.invitationTokenTtlHours").value(72))
        .andExpect(jsonPath("$.resetTokenTtlMinutes").value(30))
        .andExpect(jsonPath("$.defaultExpiryDays").value(90))
        .andExpect(jsonPath("$.inactiveDays").value(90))
        .andExpect(jsonPath("$.publicBaseUrlConfigured").value(false))
        .andExpect(jsonPath("$.revokedSessions").doesNotExist());
  }

  @Test
  void aChangeIsStoredAndAuditedWithTheChangedKeysOnly() throws Exception {
    mockMvc
        .perform(
            put(SETTINGS)
                .header(HttpHeaders.AUTHORIZATION, adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(true, false, List.of(), false, 14, 72, 30, 90, 120)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passwordMinLength").value(14))
        .andExpect(jsonPath("$.inactiveDays").value(120))
        .andExpect(jsonPath("$.passwordResetEnabled").value(false));
    LocalAuthSettings.Values stored = settings.findSingleton().orElseThrow().values();
    assertThat(stored.passwordMinLength()).isEqualTo(14);
    assertThat(stored.inactiveDays()).isEqualTo(120);
    assertThat(stored.passwordResetEnabled()).isFalse();

    List<Map<String, Object>> events = settingsEvents();
    assertThat(events).hasSize(1);
    String before = (String) events.getFirst().get("before");
    String after = (String) events.getFirst().get("after");
    assertThat(before).contains("\"passwordMinLength\": 12").contains("\"inactiveDays\": 90");
    assertThat(after).contains("\"passwordMinLength\": 14").contains("\"inactiveDays\": 120");
    assertThat(after).contains("passwordResetEnabled");
    assertThat(after).doesNotContain("invitationTokenTtlHours").doesNotContain("defaultExpiryDays");

    // the same values again: nothing changed, no event
    mockMvc
        .perform(
            put(SETTINGS)
                .header(HttpHeaders.AUTHORIZATION, adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(true, false, List.of(), false, 14, 72, 30, 90, 120)))
        .andExpect(status().isOk());
    assertThat(settingsEvents()).hasSize(1);
  }

  @Test
  void theBoundsAndTheDomainListAreEnforcedAsFieldErrors() throws Exception {
    mockMvc
        .perform(
            put(SETTINGS)
                .header(HttpHeaders.AUTHORIZATION, adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(true, false, List.of(), false, 7, 72, 2000, 90, 10)))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath(
                "$.fieldErrors[*].field",
                Matchers.containsInAnyOrder(
                    "passwordMinLength", "resetTokenTtlMinutes", "inactiveDays")));
    mockMvc
        .perform(
            put(SETTINGS)
                .header(HttpHeaders.AUTHORIZATION, adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(true, true, List.of(), false, 12, 72, 30, 90, 90)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("selfRegistrationAllowedDomains"));
    assertThat(settings.findSingleton().orElseThrow().values())
        .isEqualTo(LocalAuthSettings.Values.defaults());
  }

  @Test
  void theLinkFlowsCannotBeSwitchedOnWithoutThePublicBaseUrl() throws Exception {
    // first off (the seed default is on), then on again: the transition is what is refused
    mockMvc
        .perform(
            put(SETTINGS)
                .header(HttpHeaders.AUTHORIZATION, adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(true, false, List.of(), false, 12, 72, 30, 90, 90)))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            put(SETTINGS)
                .header(HttpHeaders.AUTHORIZATION, adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(true, false, List.of(), true, 12, 72, 30, 90, 90)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PUBLIC_BASE_URL_REQUIRED"));
    mockMvc
        .perform(
            put(SETTINGS)
                .header(HttpHeaders.AUTHORIZATION, adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(true, true, List.of("Stadt.example"), false, 12, 72, 30, 90, 90)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PUBLIC_BASE_URL_REQUIRED"));
    assertThat(settings.findSingleton().orElseThrow().values().passwordResetEnabled()).isFalse();
    assertThat(settings.findSingleton().orElseThrow().values().selfRegistrationEnabled()).isFalse();
  }

  @Test
  void switchingOffEndsRegularSessionsQuotesTheirNumberAndKeepsAdministratorsSignedIn()
      throws Exception {
    LocalAccount user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");
    LocalAccount idle = fixtures.activeUser("ruhend-" + UUID.randomUUID() + "@stadt.example");
    String userBearer = bearer(login(user.email(), LocalAccountFixtures.PASSWORD));
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, userBearer))
        .andExpect(status().isOk());

    MvcResult switchedOff =
        mockMvc
            .perform(
                put(SETTINGS)
                    .header(HttpHeaders.AUTHORIZATION, adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(false, false, List.of(), true, 12, 72, 30, 90, 90)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andReturn();
    // one account with a session, one without: the number counts sessions actually ended
    assertThat(
            JsonPath.<Integer>read(
                switchedOff.getResponse().getContentAsString(), "$.revokedSessions"))
        .isEqualTo(1);
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, userBearer))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string("WWW-Authenticate", Matchers.containsString("local_accounts_disabled")));
    mockMvc
        .perform(get(ME).header(HttpHeaders.AUTHORIZATION, adminBearer))
        .andExpect(status().isOk());
    login(idle.email(), LocalAccountFixtures.PASSWORD, 401);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE event_type = 'LOCAL_ACCOUNTS_DISABLED'",
                Long.class))
        .isEqualTo(1L);
    // no settings event for a request that only flipped the switch
    assertThat(settingsEvents()).isEmpty();

    mockMvc
        .perform(
            put(SETTINGS)
                .header(HttpHeaders.AUTHORIZATION, adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(true, false, List.of(), true, 12, 72, 30, 90, 90)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.revokedSessions").doesNotExist());
    login(idle.email(), LocalAccountFixtures.PASSWORD, 200);
    mockMvc
        .perform(get("/api/v1/auth/config"))
        .andExpect(jsonPath("$.localAccounts.enabled").value(true));
  }

  @Test
  void aRegularAccountIsRefused() throws Exception {
    LocalAccount user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");
    String userBearer = bearer(login(user.email(), LocalAccountFixtures.PASSWORD));
    mockMvc
        .perform(get(SETTINGS).header(HttpHeaders.AUTHORIZATION, userBearer))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            put(SETTINGS)
                .header(HttpHeaders.AUTHORIZATION, userBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(false, false, List.of(), true, 12, 72, 30, 90, 90)))
        .andExpect(status().isForbidden());
  }

  private List<Map<String, Object>> settingsEvents() {
    return jdbc.queryForList(
        "SELECT CAST(before AS text) AS before, CAST(after AS text) AS after FROM audit_log"
            + " WHERE event_type = 'LOCAL_ACCOUNTS_SETTINGS_CHANGED' ORDER BY recorded_at");
  }

  private void resetSettings() {
    LocalAuthSettings row = settings.findSingleton().orElseThrow();
    row.replace(LocalAuthSettings.Values.defaults(), null, java.time.Instant.now());
    settings.save(row);
  }

  private static String body(
      boolean enabled,
      boolean selfRegistration,
      List<String> domains,
      boolean passwordReset,
      int minLength,
      int invitationHours,
      int resetMinutes,
      int expiryDays,
      int inactiveDays) {
    return "{\"enabled\":"
        + enabled
        + ",\"selfRegistrationEnabled\":"
        + selfRegistration
        + ",\"selfRegistrationAllowedDomains\":["
        + String.join(",", domains.stream().map(d -> "\"" + d + "\"").toList())
        + "],\"passwordResetEnabled\":"
        + passwordReset
        + ",\"passwordMinLength\":"
        + minLength
        + ",\"invitationTokenTtlHours\":"
        + invitationHours
        + ",\"resetTokenTtlMinutes\":"
        + resetMinutes
        + ",\"defaultExpiryDays\":"
        + expiryDays
        + ",\"inactiveDays\":"
        + inactiveDays
        + "}";
  }

  private MvcResult login(String email, String password) throws Exception {
    return login(email, password, 200);
  }

  private MvcResult login(String email, String password, int expectedStatus) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/auth/local/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().is(expectedStatus))
        .andReturn();
  }

  private static String bearer(MvcResult result) throws Exception {
    return "Bearer " + JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
  }
}
