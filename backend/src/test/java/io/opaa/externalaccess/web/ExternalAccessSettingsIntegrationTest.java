package io.opaa.externalaccess.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.externalaccess.ExternalAccessDefaults;
import io.opaa.externalaccess.ExternalAccessNetworkPolicy;
import io.opaa.externalaccess.ExternalAccessSettings;
import io.opaa.externalaccess.ExternalAccessSettingsRepository;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.library.ExternalAccessReminderLead;
import io.opaa.test.OpaaIntegrationTest;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The channel settings of the external access at the HTTP boundary (ADR-0035), against the real
 * {@code dev} security chain and a real Postgres - a {@code @WebMvcTest} slice would have to stub
 * the very authorization decision one of the acceptance criteria is about.
 *
 * <p>Carries the canonical {@link OpaaIntegrationTest} signature (AGENTS.md,
 * "Spring-Testkontexte"). The settings row itself is put back by {@code SeededRowRestorer}; this
 * class reads and removes only the audit entries written since its own method started - every class
 * that switches the channel on goes through the same audited service, so both an unfiltered
 * assertion and an unfiltered cleanup would depend on which class Gradle happens to run first.
 */
@OpaaIntegrationTest
class ExternalAccessSettingsIntegrationTest {

  private static final String SETTINGS = "/api/v1/system/external-access";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ExternalAccessSettingsRepository settings;
  @Autowired private ExternalAccessSettingsService settingsService;
  @Autowired private ExternalAccessNetworkPolicy networkPolicy;
  @Autowired private ExternalAccessReminderLead reminderLead;

  /** Every audit row from here on belongs to this test method; earlier ones do not. */
  private Instant methodStartedAt;

  @BeforeEach
  void markOwnAuditWindow() {
    methodStartedAt = Instant.now();
  }

  @AfterEach
  void resetSettingsAndItsTrail() {
    ExternalAccessSettings row = settings.findSingleton().orElseThrow();
    row.replace(ExternalAccessSettings.Values.defaults(), null, Instant.now());
    settings.save(row);
    jdbc.update(
        "DELETE FROM audit_log WHERE event_type = 'EXTERNAL_ACCESS_SETTINGS_CHANGED'"
            + " AND recorded_at >= ?",
        Timestamp.from(methodStartedAt));
  }

  @Test
  void aFreshInstallationIsClosedAndCarriesTheDeliveredDefaults() throws Exception {
    mockMvc
        .perform(get(SETTINGS).with(devUser("dev-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.tokenMaxLifetimeDays").value(90))
        .andExpect(jsonPath("$.tokenRateLimitPerHour").value(60))
        .andExpect(jsonPath("$.allowedCidrs[0]").value("10.0.0.0/8"))
        .andExpect(jsonPath("$.allowedCidrs[3]").value("127.0.0.0/8"))
        .andExpect(jsonPath("$.massRetrievalAlertThreshold").value(600))
        .andExpect(
            jsonPath("$.serverInstructions").value(ExternalAccessDefaults.SERVER_INSTRUCTIONS))
        .andExpect(
            jsonPath("$.defaultServerInstructions")
                .value(ExternalAccessDefaults.SERVER_INSTRUCTIONS))
        .andExpect(jsonPath("$.releaseReminderLeadDays").value(14))
        .andExpect(jsonPath("$.releaseReminderLeadDaysOverridden").value(false))
        .andExpect(jsonPath("$.defaultReleaseReminderLeadDays").value(14))
        .andExpect(jsonPath("$.maxReleaseReminderLeadDays").value(364))
        .andExpect(jsonPath("$.updatedAt").exists());

    assertThat(settingsService.isEnabled()).isFalse();
    assertThat(reminderLead.days()).isEqualTo(14);
  }

  /**
   * The reminder lead of a release: an own value holds for the next reminder run, is audited with
   * the value before and after, the same value again writes nothing, and the factory default comes
   * back as {@code null}.
   */
  @Test
  void theReminderLeadIsStoredAuditedReadByTheReminderAndResettable() throws Exception {
    putLead(30).andExpect(status().isOk());

    assertThat(reminderLead.days()).isEqualTo(30);
    List<Map<String, Object>> events = settingsEvents();
    assertThat(events).hasSize(1);
    assertThat(json(events.getFirst().get("before")))
        .isEqualTo(
            Map.of("releaseReminderLeadDays", 14, "releaseReminderLeadDaysOverridden", false));
    assertThat(json(events.getFirst().get("after")))
        .isEqualTo(
            Map.of("releaseReminderLeadDays", 30, "releaseReminderLeadDaysOverridden", true));

    putLead(30)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.releaseReminderLeadDays").value(30))
        .andExpect(jsonPath("$.releaseReminderLeadDaysOverridden").value(true));
    assertThat(settingsEvents()).as("an unchanged lead writes no event").hasSize(1);

    putLead(null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.releaseReminderLeadDays").value(14))
        .andExpect(jsonPath("$.releaseReminderLeadDaysOverridden").value(false));
    assertThat(reminderLead.days()).isEqualTo(14);
    assertThat(settingsEvents()).hasSize(2);
  }

  /**
   * A stored lead beyond a since lowered Befristung stays as stored: sent back unchanged it is
   * accepted, it reads as the bound, and an unchanged lead is in no audit entry.
   */
  @Test
  void aStoredLeadBeyondTheBoundIsKeptRawAndReadAsTheBound() throws Exception {
    jdbc.update(
        "UPDATE external_access_settings SET release_reminder_lead_days = 400 WHERE id = 1");

    mockMvc
        .perform(get(SETTINGS).with(devUser("dev-admin")))
        .andExpect(jsonPath("$.releaseReminderLeadDays").value(364))
        .andExpect(jsonPath("$.storedReleaseReminderLeadDays").value(400));
    assertThat(reminderLead.days()).isEqualTo(364);

    putLead(400).andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT release_reminder_lead_days FROM external_access_settings WHERE id = 1",
                Integer.class))
        .isEqualTo(400);
    assertThat(settingsEvents()).as("nothing changed, nothing audited").isEmpty();

    putLead(500)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("releaseReminderLeadDays"));
  }

  /**
   * The lead ends one day before the longest Befristung; beyond it, or negative, nothing is kept.
   */
  @Test
  void aLeadBeyondTheLongestBefristungIsRefusedAtItsField() throws Exception {
    putLead(365)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("releaseReminderLeadDays"));
    putLead(-1)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("releaseReminderLeadDays"));
    putLead(0).andExpect(status().isOk()).andExpect(jsonPath("$.releaseReminderLeadDays").value(0));

    assertThat(reminderLead.days()).as("0 switches the reminder off").isZero();
    assertThat(settingsEvents()).hasSize(1);
  }

  @Test
  void onlyASystemAdministratorReadsOrChangesTheChannel() throws Exception {
    mockMvc.perform(get(SETTINGS).with(devUser("dev-user"))).andExpect(status().isForbidden());
    mockMvc
        .perform(
            put(SETTINGS)
                .with(devUser("dev-user"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(true, 90, 60, List.of("10.0.0.0/8"), 600, "Zuerst suchen.")))
        .andExpect(status().isForbidden());

    assertThat(settingsService.isEnabled()).as("the channel stayed closed").isFalse();
  }

  @Test
  void switchingTheChannelOnIsStoredAuditedAndVisibleToTheNextCall() throws Exception {
    mockMvc
        .perform(
            put(SETTINGS)
                .with(devUser("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body(
                        true,
                        30,
                        120,
                        List.of("10.0.0.0/8", "192.168.0.0/16"),
                        900,
                        ExternalAccessDefaults.SERVER_INSTRUCTIONS)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.tokenMaxLifetimeDays").value(30))
        .andExpect(jsonPath("$.updatedBy").exists());

    assertThat(settingsService.isEnabled()).isTrue();
    assertThat(networkPolicy.isAllowed(from("192.168.4.4"))).isTrue();
    assertThat(networkPolicy.isAllowed(from("127.0.0.1")))
        .as("loopback is no longer listed")
        .isFalse();

    List<Map<String, Object>> events = settingsEvents();
    assertThat(events).hasSize(1);
    assertThat(String.valueOf(events.getFirst().get("before")))
        .contains("\"enabled\":false")
        .contains("\"tokenMaxLifetimeDays\":90")
        .contains("\"massRetrievalAlertThreshold\":600");
    assertThat(String.valueOf(events.getFirst().get("after")))
        .contains("\"enabled\":true")
        .contains("\"tokenMaxLifetimeDays\":30")
        .contains("\"tokenRateLimitPerHour\":120")
        .contains("192.168.0.0/16")
        .contains("\"massRetrievalAlertThreshold\":900");
    assertThat(String.valueOf(events.getFirst().get("actor_ref")))
        .as("a pseudonym, never a name")
        .doesNotContain("dev-admin");
  }

  /** ADR-0060, Entscheidung 11: the instructions text is audited, as hash and length only. */
  @Test
  void changingTheInstructionsTextAloneIsAuditedAsHashAndLengthOnly() throws Exception {
    mockMvc
        .perform(
            put(SETTINGS)
                .with(devUser("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body(
                        false,
                        90,
                        60,
                        ExternalAccessDefaults.ALLOWED_CIDRS,
                        600,
                        "Zuerst „search“ aufrufen und die Herkunft angeben.")))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.serverInstructions")
                .value("Zuerst „search“ aufrufen und die Herkunft angeben."));

    mockMvc
        .perform(
            put(SETTINGS)
                .with(devUser("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body(
                        false,
                        90,
                        60,
                        ExternalAccessDefaults.ALLOWED_CIDRS,
                        600,
                        "Zuerst „search“ aufrufen und die Herkunft angeben.")))
        .andExpect(status().isOk());

    List<Map<String, Object>> events = settingsEvents();
    assertThat(events).as("the unchanged text the second time writes nothing").hasSize(1);
    assertThat(String.valueOf(events.getFirst().get("before")))
        .contains("serverInstructionsSha256")
        .contains("serverInstructionsLength")
        .doesNotContain("enabled");
    assertThat(String.valueOf(events.getFirst().get("after")))
        .contains("\"serverInstructionsLength\":50")
        .doesNotContain("Zuerst")
        .doesNotContain("tokenMaxLifetimeDays");
    assertThat(String.valueOf(events.getFirst().get("before")))
        .doesNotContain(ExternalAccessDefaults.SERVER_INSTRUCTIONS.substring(0, 20));
  }

  @Test
  void theInstructionsTextIsResettableToTheDeliveredDefault() throws Exception {
    mockMvc.perform(
        put(SETTINGS)
            .with(devUser("dev-admin"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                body(false, 90, 60, ExternalAccessDefaults.ALLOWED_CIDRS, 600, "Eigener Text.")));

    mockMvc
        .perform(
            put(SETTINGS)
                .with(devUser("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body(
                        false,
                        90,
                        60,
                        ExternalAccessDefaults.ALLOWED_CIDRS,
                        600,
                        ExternalAccessDefaults.SERVER_INSTRUCTIONS)))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.serverInstructions").value(ExternalAccessDefaults.SERVER_INSTRUCTIONS));
  }

  @Test
  void aLifetimeOutsideTheBoundsOrAMalformedNetworkIsAnInputError() throws Exception {
    mockMvc
        .perform(
            put(SETTINGS)
                .with(devUser("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(true, 0, 60, List.of("10.0.0.0/8"), 600, "Text.")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("tokenMaxLifetimeDays"));

    mockMvc
        .perform(
            put(SETTINGS)
                .with(devUser("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(true, 366, 60, List.of("10.0.0.0/8"), 600, "Text.")))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            put(SETTINGS)
                .with(devUser("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(true, 90, 60, List.of("10.0.0.0/8", "stadt.example"), 600, "Text.")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("allowedCidrs"));

    assertThat(settingsService.isEnabled()).as("nothing of a refused change was stored").isFalse();
    assertThat(settingsEvents()).isEmpty();
  }

  /**
   * The emergency stop, and the only key that changed: the entry names it alone - an implementation
   * that always wrote all five keys would be indistinguishable in a test that changes all five ("in
   * beide Richtungen").
   */
  @Test
  void switchingTheChannelOffIsAuditedWithThatOneKeyOnly() throws Exception {
    mockMvc.perform(
        put(SETTINGS)
            .with(devUser("dev-admin"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                body(
                    true,
                    90,
                    60,
                    ExternalAccessDefaults.ALLOWED_CIDRS,
                    600,
                    ExternalAccessDefaults.SERVER_INSTRUCTIONS)));
    jdbc.update("DELETE FROM audit_log WHERE event_type = 'EXTERNAL_ACCESS_SETTINGS_CHANGED'");

    mockMvc
        .perform(
            put(SETTINGS)
                .with(devUser("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body(
                        false,
                        90,
                        60,
                        ExternalAccessDefaults.ALLOWED_CIDRS,
                        600,
                        ExternalAccessDefaults.SERVER_INSTRUCTIONS)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false));

    List<Map<String, Object>> events = settingsEvents();
    assertThat(events).hasSize(1);
    assertThat(String.valueOf(events.getFirst().get("before"))).isEqualTo("{\"enabled\":true}");
    assertThat(String.valueOf(events.getFirst().get("after"))).isEqualTo("{\"enabled\":false}");
    assertThat(settingsService.isEnabled()).isFalse();
  }

  private org.springframework.test.web.servlet.ResultActions putLead(Integer days)
      throws Exception {
    String body =
        body(
            false,
            ExternalAccessDefaults.TOKEN_MAX_LIFETIME_DAYS,
            ExternalAccessDefaults.TOKEN_RATE_LIMIT_PER_HOUR,
            ExternalAccessDefaults.ALLOWED_CIDRS,
            ExternalAccessDefaults.MASS_RETRIEVAL_ALERT_THRESHOLD,
            ExternalAccessDefaults.SERVER_INSTRUCTIONS);
    return mockMvc.perform(
        put(SETTINGS)
            .with(devUser("dev-admin"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                body.substring(0, body.length() - 1)
                    + ",\"releaseReminderLeadDays\":"
                    + days
                    + "}"));
  }

  /** An audit column as a map: the database keeps no key order. */
  private static Map<String, Object> json(Object column) {
    return tools.jackson.databind.json.JsonMapper.builder()
        .build()
        .readValue(
            String.valueOf(column),
            new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
  }

  private List<Map<String, Object>> settingsEvents() {
    return jdbc.queryForList(
        "SELECT actor_ref, CAST(before AS text) AS before, CAST(after AS text) AS after FROM"
            + " audit_log WHERE event_type = 'EXTERNAL_ACCESS_SETTINGS_CHANGED'"
            + " AND recorded_at >= ? ORDER BY recorded_at",
        Timestamp.from(methodStartedAt));
  }

  private static String body(
      boolean enabled,
      int tokenMaxLifetimeDays,
      int tokenRateLimitPerHour,
      List<String> allowedCidrs,
      int massRetrievalAlertThreshold,
      String serverInstructions) {
    String cidrs =
        allowedCidrs.stream()
            .map(cidr -> "\"" + cidr + "\"")
            .reduce((a, b) -> a + "," + b)
            .orElse("");
    return "{\"enabled\":"
        + enabled
        + ",\"tokenMaxLifetimeDays\":"
        + tokenMaxLifetimeDays
        + ",\"tokenRateLimitPerHour\":"
        + tokenRateLimitPerHour
        + ",\"allowedCidrs\":["
        + cidrs
        + "],\"massRetrievalAlertThreshold\":"
        + massRetrievalAlertThreshold
        + ",\"serverInstructions\":\""
        + serverInstructions.replace("\"", "\\\"")
        + "\"}";
  }

  private static HttpServletRequest from(String remoteAddress) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(remoteAddress);
    return request;
  }

  private RequestPostProcessor devUser(String subject) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      return request;
    };
  }
}
