package io.opaa.succession.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import io.opaa.succession.SuccessionAgingThreshold;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The aging threshold of the operational list "Ohne Zuständigkeit" against the Flyway schema: only
 * the system administration reads and sets it, the factory default of twelve months applies without
 * a value of its own, every change is an audit event with the value before and after, and the list
 * reads the value in force.
 */
@OpaaIntegrationTest
class SuccessionSettingsIntegrationTest {

  private static final String SETTINGS = "/api/v1/admin/succession/settings";
  private static final String EVENT = "SUCCESSION_AGING_THRESHOLD_CHANGED";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private SuccessionAgingThreshold threshold;

  @Autowired
  @Qualifier("successionInstanceState")
  private InstanceStateSource instanceState;

  @BeforeEach
  void provisionCallers() throws Exception {
    mockMvc.perform(get("/api/v1/spaces").with(caller("dev-user"))).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/spaces").with(caller("dev-admin"))).andExpect(status().isOk());
  }

  @AfterEach
  void removeOwnRows() {
    jdbcTemplate.update("DELETE FROM succession_settings");
    jdbcTemplate.update("DELETE FROM audit_log WHERE event_type = ?", EVENT);
  }

  @Test
  void theFactoryDefaultOfTwelveMonthsAppliesWithoutAValueOfItsOwn() throws Exception {
    mockMvc
        .perform(get(SETTINGS).with(caller("dev-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agingThresholdMonths").value(12))
        .andExpect(jsonPath("$.defaultAgingThresholdMonths").value(12))
        .andExpect(jsonPath("$.minAgingThresholdMonths").value(1))
        .andExpect(jsonPath("$.maxAgingThresholdMonths").value(120))
        .andExpect(jsonPath("$.overridden").value(false));

    assertThat(threshold.months()).isEqualTo(12);
    assertThat(instanceState.storedSettings())
        .containsEntry("succession_settings.aging_threshold_months", new StoredValue("12", null));
  }

  @Test
  void theSystemAdministrationSetsAndResetsTheThresholdWithAnAuditTrail() throws Exception {
    putMonths("{\"agingThresholdMonths\":6}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agingThresholdMonths").value(6))
        .andExpect(jsonPath("$.overridden").value(true));
    assertThat(threshold.months()).isEqualTo(6);
    StoredValue stored =
        instanceState.storedSettings().get("succession_settings.aging_threshold_months");
    assertThat(stored.value()).isEqualTo("6");
    assertThat(stored.changedAt()).isNotNull();

    putMonths("{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agingThresholdMonths").value(12))
        .andExpect(jsonPath("$.overridden").value(false));

    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            "SELECT object_label, CAST(before AS text) AS before, CAST(after AS text) AS after"
                + " FROM audit_log WHERE event_type = ? ORDER BY recorded_at, event_id",
            EVENT);
    assertThat(events).hasSize(2);
    assertThat(events.get(0).get("object_label")).isEqualTo("Ohne Zuständigkeit");
    assertThat(json(events.get(0), "before"))
        .containsEntry("agingThresholdMonths", 12)
        .containsEntry("overridden", false);
    assertThat(json(events.get(0), "after"))
        .containsEntry("agingThresholdMonths", 6)
        .containsEntry("overridden", true);
    assertThat(json(events.get(1), "after"))
        .containsEntry("agingThresholdMonths", 12)
        .containsEntry("overridden", false);
  }

  /** Saving the value in force again, or resetting without a value of its own, changes nothing. */
  @Test
  void aCallThatChangesNothingWritesNoAuditEvent() throws Exception {
    putMonths("{}").andExpect(status().isOk());
    assertThat(auditEvents()).isZero();

    for (int i = 0; i < 2; i++) {
      putMonths("{\"agingThresholdMonths\":12}")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.overridden").value(true));
    }
    assertThat(auditEvents()).isEqualTo(1);
  }

  @Test
  void aValueOutsideOneToOneHundredTwentyMonthsIsRefusedAtItsField() throws Exception {
    for (String body : List.of("{\"agingThresholdMonths\":0}", "{\"agingThresholdMonths\":121}")) {
      putMonths(body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.fieldErrors[0].field").value("agingThresholdMonths"));
    }
    assertThat(auditEvents()).isZero();
    assertThat(threshold.months()).isEqualTo(12);
  }

  @Test
  void onlyTheSystemAdministrationReachesTheThreshold() throws Exception {
    mockMvc.perform(get(SETTINGS).with(caller("dev-user"))).andExpect(status().isForbidden());
    mockMvc
        .perform(put(SETTINGS).with(caller("dev-user")).content("{\"agingThresholdMonths\":3}"))
        .andExpect(status().isForbidden());
    assertThat(auditEvents()).isZero();
  }

  private org.springframework.test.web.servlet.ResultActions putMonths(String body)
      throws Exception {
    return mockMvc.perform(put(SETTINGS).with(caller("dev-admin")).content(body));
  }

  private int auditEvents() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM audit_log WHERE event_type = ?", Integer.class, EVENT);
  }

  private static Map<String, Object> json(Map<String, Object> row, String column) {
    return JSON.readValue((String) row.get(column), new TypeReference<Map<String, Object>>() {});
  }

  private static RequestPostProcessor caller(String subject) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }
}
