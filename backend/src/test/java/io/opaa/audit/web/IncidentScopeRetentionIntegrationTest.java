package io.opaa.audit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.audit.AuditIncidentScopeRetentionSettings;
import io.opaa.auth.DevAuthFilter;
import io.opaa.test.OpaaIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The period the grants of the anlassbezogene Klärung are kept lives in the database (ADR-0060,
 * Entscheidung 1): one year by default as before, 1 to 10 years, and every change - a matter for
 * the staff council - stands with before and after in the audit log.
 */
@OpaaIntegrationTest
class IncidentScopeRetentionIntegrationTest {

  private static final String URL = "/api/v1/admin/retention/incident-scopes";
  private static final String EVENT = "INCIDENT_SCOPE_RETENTION_CHANGED";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuditIncidentScopeRetentionSettings settings;

  @BeforeEach
  void provisionCallers() throws Exception {
    mockMvc.perform(get("/api/v1/spaces").with(devUser())).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/spaces").with(devAdmin())).andExpect(status().isOk());
  }

  @AfterEach
  void removeOwnEvents() {
    jdbcTemplate.update("DELETE FROM audit_log WHERE event_type = ?", EVENT);
  }

  @Test
  void withoutARowOneYearApplies() throws Exception {
    mockMvc
        .perform(get(URL).with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.retentionYears").value(1))
        .andExpect(jsonPath("$.defaultRetentionYears").value(1))
        .andExpect(jsonPath("$.overridden").value(false));
  }

  @Test
  void aLongerPeriodActsOnTheNextRunAndIsAuditedWithBeforeAndAfter() throws Exception {
    mockMvc
        .perform(put(URL).with(devAdmin()).content("{\"retentionYears\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.retentionYears").value(3))
        .andExpect(jsonPath("$.overridden").value(true));
    mockMvc
        .perform(put(URL).with(devAdmin()).content("{\"retentionYears\":3}"))
        .andExpect(status().isOk());

    assertThat(settings.current().incidentScopeRetentionYears()).isEqualTo(3);
    assertThat(eventCount()).isOne();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT before::text || after::text FROM audit_log WHERE event_type = ?",
                String.class,
                EVENT))
        .contains("\"retentionYears\":1")
        .contains("\"retentionYears\":3");

    mockMvc
        .perform(put(URL).with(devAdmin()).content("{}"))
        .andExpect(jsonPath("$.retentionYears").value(1))
        .andExpect(jsonPath("$.overridden").value(false));
    assertThat(eventCount()).isEqualTo(2);
  }

  @Test
  void aPeriodOutsideOneToTenYearsIsRefused() throws Exception {
    mockMvc
        .perform(put(URL).with(devAdmin()).content("{\"retentionYears\":0}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(put(URL).with(devAdmin()).content("{\"retentionYears\":11}"))
        .andExpect(status().isBadRequest());

    assertThat(settings.current().incidentScopeRetentionYears()).isEqualTo(1);
    assertThat(eventCount()).isZero();
  }

  @Test
  void onlyTheSystemAdministrationReadsAndSetsThePeriod() throws Exception {
    mockMvc.perform(get(URL).with(devUser())).andExpect(status().isForbidden());
    mockMvc
        .perform(put(URL).with(devUser()).content("{\"retentionYears\":2}"))
        .andExpect(status().isForbidden());
  }

  private int eventCount() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM audit_log WHERE event_type = ?", Integer.class, EVENT);
  }

  private RequestPostProcessor devUser() {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, "dev-user");
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }

  private RequestPostProcessor devAdmin() {
    return request -> {
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }
}
