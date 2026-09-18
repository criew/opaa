package io.opaa.api;

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
import io.opaa.test.OpaaIntegrationTest;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The channel settings of the external access at the HTTP boundary (#1717, ADR-0035), against the
 * real {@code dev} security chain and a real Postgres - a {@code @WebMvcTest} slice would have to
 * stub the very authorization decision one of the acceptance criteria is about.
 *
 * <p>Carries the canonical {@link OpaaIntegrationTest} signature (AGENTS.md,
 * "Spring-Testkontexte"). The settings row itself is put back by {@code SeededRowRestorer}; this
 * class only removes the audit entries it wrote, which no other class produces.
 */
@OpaaIntegrationTest
class ExternalAccessSettingsIntegrationTest {

  private static final String SETTINGS = "/api/v1/system/external-access";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ExternalAccessSettingsRepository settings;
  @Autowired private ExternalAccessSettingsService settingsService;
  @Autowired private ExternalAccessNetworkPolicy networkPolicy;

  @AfterEach
  void resetSettingsAndItsTrail() {
    ExternalAccessSettings row = settings.findSingleton().orElseThrow();
    row.replace(ExternalAccessSettings.Values.defaults(), null, Instant.now());
    settings.save(row);
    jdbc.update("DELETE FROM audit_log WHERE event_type = 'EXTERNAL_ACCESS_SETTINGS_CHANGED'");
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
        .andExpect(jsonPath("$.updatedAt").exists());

    assertThat(settingsService.isEnabled()).isFalse();
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

  /** Regression guard: the instructions text changes no reach and is outside the closed list. */
  @Test
  void changingTheInstructionsTextAloneWritesNoAuditEntry() throws Exception {
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

    assertThat(settingsEvents()).isEmpty();
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
   * that always wrote all five keys would be indistinguishable in a test that changes all five
   * (#1717, "in beide Richtungen").
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

  private List<Map<String, Object>> settingsEvents() {
    return jdbc.queryForList(
        "SELECT actor_ref, CAST(before AS text) AS before, CAST(after AS text) AS after FROM"
            + " audit_log WHERE event_type = 'EXTERNAL_ACCESS_SETTINGS_CHANGED' ORDER BY"
            + " recorded_at");
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
