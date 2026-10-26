package io.opaa.directory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.directory.sync.DirectorySyncChangeThreshold;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The installation-wide plausibility threshold of the directory sync against the Flyway schema:
 * only the system administration reads and sets it, the factory default of 30 percent applies
 * without a value of its own, and every change is an audit event with the value before and after.
 */
@OpaaIntegrationTest
class DirectorySyncSettingsIntegrationTest {

  private static final String SETTINGS = "/api/v1/admin/directory-sync/settings";
  private static final String EVENT = "DIRECTORY_SYNC_CHANGE_THRESHOLD_CHANGED";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DirectorySyncChangeThreshold threshold;

  @BeforeEach
  void provisionCallers() throws Exception {
    mockMvc.perform(get("/api/v1/spaces").with(caller("dev-user"))).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/spaces").with(caller("dev-admin"))).andExpect(status().isOk());
  }

  @AfterEach
  void removeOwnRows() {
    jdbcTemplate.update("DELETE FROM directory_sync_settings");
    jdbcTemplate.update("DELETE FROM audit_log WHERE event_type = ?", EVENT);
  }

  @Test
  void theFactoryDefaultOfThirtyPercentAppliesWithoutAValueOfItsOwn() throws Exception {
    mockMvc
        .perform(get(SETTINGS).with(caller("dev-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changeThresholdPercent").value(30))
        .andExpect(jsonPath("$.defaultChangeThresholdPercent").value(30))
        .andExpect(jsonPath("$.minChangeThresholdPercent").value(1))
        .andExpect(jsonPath("$.maxChangeThresholdPercent").value(100))
        .andExpect(jsonPath("$.overridden").value(false));

    assertThat(threshold.fraction()).isEqualTo(0.3);
  }

  @Test
  void theSystemAdministrationSetsAndResetsTheThresholdWithAnAuditTrail() throws Exception {
    putPercent("{\"changeThresholdPercent\":50}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changeThresholdPercent").value(50))
        .andExpect(jsonPath("$.overridden").value(true));
    assertThat(threshold.fraction()).isEqualTo(0.5);

    putPercent("{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changeThresholdPercent").value(30))
        .andExpect(jsonPath("$.overridden").value(false));

    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            "SELECT object_label, CAST(before AS text) AS before, CAST(after AS text) AS after"
                + " FROM audit_log WHERE event_type = ? ORDER BY recorded_at, event_id",
            EVENT);
    assertThat(events).hasSize(2);
    assertThat(events.get(0).get("object_label")).isEqualTo("Verzeichnisabgleich");
    assertThat(json(events.get(0), "before"))
        .containsEntry("changeThresholdPercent", 30)
        .containsEntry("overridden", false);
    assertThat(json(events.get(0), "after"))
        .containsEntry("changeThresholdPercent", 50)
        .containsEntry("overridden", true);
    assertThat(json(events.get(1), "after"))
        .containsEntry("changeThresholdPercent", 30)
        .containsEntry("overridden", false);
  }

  /** Saving the value in force again, or resetting without a value of its own, changes nothing. */
  @Test
  void aCallThatChangesNothingWritesNoAuditEvent() throws Exception {
    putPercent("{}").andExpect(status().isOk());
    assertThat(auditEvents()).isZero();

    for (int i = 0; i < 2; i++) {
      putPercent("{\"changeThresholdPercent\":30}")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.overridden").value(true));
    }
    assertThat(auditEvents()).isEqualTo(1);
  }

  @Test
  void aThresholdOutsideOneToOneHundredPercentIsRefusedAtItsField() throws Exception {
    for (String body :
        List.of("{\"changeThresholdPercent\":0}", "{\"changeThresholdPercent\":101}")) {
      putPercent(body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.fieldErrors[0].field").value("changeThresholdPercent"));
    }
    assertThat(auditEvents()).isZero();
    assertThat(threshold.percent()).isEqualTo(30);
  }

  @Test
  void onlyTheSystemAdministrationReachesTheThreshold() throws Exception {
    mockMvc.perform(get(SETTINGS).with(caller("dev-user"))).andExpect(status().isForbidden());
    mockMvc
        .perform(put(SETTINGS).with(caller("dev-user")).content("{\"changeThresholdPercent\":90}"))
        .andExpect(status().isForbidden());
    assertThat(auditEvents()).isZero();
  }

  private ResultActions putPercent(String body) throws Exception {
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
