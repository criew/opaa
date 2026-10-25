package io.opaa.library.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The installation-wide rhythm of the full sync per source type against the Flyway schema: only
 * the system administration reads and sets it, the factory default applies without a value of its
 * own, every change is an audit event with the value before and after, and the source types answer
 * the value in force.
 */
@OpaaIntegrationTest
class FullSyncRhythmIntegrationTest {

  private static final String EVENT = "FULL_SYNC_RHYTHM_CHANGED";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void provisionCallers() throws Exception {
    mockMvc.perform(get("/api/v1/spaces").with(devUser())).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/spaces").with(devAdmin())).andExpect(status().isOk());
  }

  @AfterEach
  void removeOwnRows() {
    jdbcTemplate.update("DELETE FROM audit_log WHERE event_type = ?", EVENT);
  }

  @Test
  void everySourceTypeWithAFullSyncStartsAtItsFactoryDefault() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/full-sync-rhythms").with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$[*].sourceType")
                .value(contains("CONFLUENCE", "DROPBOX", "GOOGLE_DRIVE", "ONEDRIVE", "SHAREPOINT")))
        .andExpect(jsonPath("$[*].defaultIntervalDays").value(contains(7, 7, 7, 1, 1)))
        .andExpect(jsonPath("$[*].intervalDays").value(contains(7, 7, 7, 1, 1)))
        .andExpect(jsonPath("$[*].overridden").value(contains(false, false, false, false, false)))
        .andExpect(jsonPath("$[0].displayName").value("Confluence"));
  }

  @Test
  void theSystemAdministrationSetsAndResetsTheRhythmOfOneType() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/admin/full-sync-rhythms/SHAREPOINT")
                .with(devAdmin())
                .content("{\"intervalDays\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceType").value("SHAREPOINT"))
        .andExpect(jsonPath("$.intervalDays").value(3))
        .andExpect(jsonPath("$.defaultIntervalDays").value(1))
        .andExpect(jsonPath("$.overridden").value(true));
    mockMvc
        .perform(get("/api/v1/source-types").with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$[?(@.type == 'SHAREPOINT')].fullSyncIntervalDefaultDays").value(contains(3)))
        .andExpect(
            jsonPath("$[?(@.type == 'ONEDRIVE')].fullSyncIntervalDefaultDays").value(contains(1)));

    mockMvc
        .perform(put("/api/v1/admin/full-sync-rhythms/SHAREPOINT").with(devAdmin()).content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.intervalDays").value(1))
        .andExpect(jsonPath("$.overridden").value(false));

    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            "SELECT object_label, before, after FROM audit_log WHERE event_type = ?"
                + " ORDER BY recorded_at, event_id",
            EVENT);
    assertThat(events).hasSize(2);
    assertThat(events.get(0).get("object_label")).isEqualTo("Quellart SharePoint");
    assertThat(json(events.get(0), "before"))
        .containsEntry("sourceType", "SHAREPOINT")
        .containsEntry("intervalDays", 1)
        .containsEntry("overridden", false);
    assertThat(json(events.get(0), "after"))
        .containsEntry("intervalDays", 3)
        .containsEntry("overridden", true);
    assertThat(json(events.get(1), "after"))
        .containsEntry("intervalDays", 1)
        .containsEntry("overridden", false);
  }

  @Test
  void aValueOutsideOneToThreeHundredSixtyFiveDaysIsRefused() throws Exception {
    for (String body : List.of("{\"intervalDays\":0}", "{\"intervalDays\":366}")) {
      mockMvc
          .perform(
              put("/api/v1/admin/full-sync-rhythms/CONFLUENCE").with(devAdmin()).content(body))
          .andExpect(status().isBadRequest());
    }
    assertThat(auditEvents()).isZero();
  }

  @Test
  void aTypeWithoutAFullSyncIsUnknown() throws Exception {
    for (String type : List.of("UPLOAD", "S3", "NO_SUCH_TYPE")) {
      mockMvc
          .perform(
              put("/api/v1/admin/full-sync-rhythms/" + type)
                  .with(devAdmin())
                  .content("{\"intervalDays\":3}"))
          .andExpect(status().isNotFound());
    }
    assertThat(auditEvents()).isZero();
  }

  @Test
  void onlyTheSystemAdministrationReachesTheRhythm() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/full-sync-rhythms").with(devUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            put("/api/v1/admin/full-sync-rhythms/CONFLUENCE")
                .with(devUser())
                .content("{\"intervalDays\":3}"))
        .andExpect(status().isForbidden());
    assertThat(auditEvents()).isZero();
  }

  private int auditEvents() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM audit_log WHERE event_type = ?", Integer.class, EVENT);
  }

  private static Map<String, Object> json(Map<String, Object> row, String column) {
    return JSON.readValue((String) row.get(column), new TypeReference<Map<String, Object>>() {});
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
