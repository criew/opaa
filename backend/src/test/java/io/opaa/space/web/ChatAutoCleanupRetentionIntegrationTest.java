package io.opaa.space.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.space.ChatAutoCleanupSettings;
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

/**
 * The periods of the automatic chat cleanup live in the database (ADR-0060, Entscheidung 1): the
 * factory defaults are the former defaults of the configuration, the system administration sets and
 * resets them within their bounds, every reader sees the change at once, and every change - only a
 * change - is an audit event with before and after.
 */
@OpaaIntegrationTest
class ChatAutoCleanupRetentionIntegrationTest {

  private static final String URL = "/api/v1/admin/retention/chat-auto-cleanup";
  private static final String EVENT = "CHAT_AUTO_CLEANUP_PERIODS_CHANGED";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ChatAutoCleanupSettings settings;

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
  void withoutARowTheFormerDefaultsOfTheConfigurationApply() throws Exception {
    mockMvc
        .perform(get(URL).with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archiveAfterDays").value(90))
        .andExpect(jsonPath("$.deleteAfterDays").value(365))
        .andExpect(jsonPath("$.defaultArchiveAfterDays").value(90))
        .andExpect(jsonPath("$.defaultDeleteAfterDays").value(365))
        .andExpect(jsonPath("$.overridden").value(false));
  }

  @Test
  void aSetValueActsOnEveryReaderAndIsAuditedWithBeforeAndAfter() throws Exception {
    mockMvc
        .perform(
            put(URL).with(devAdmin()).content("{\"archiveAfterDays\":120,\"deleteAfterDays\":400}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archiveAfterDays").value(120))
        .andExpect(jsonPath("$.overridden").value(true));

    assertThat(settings.current().deleteAfterDays()).isEqualTo(400);
    mockMvc
        .perform(get("/api/v1/spaces/chat-auto-cleanup").with(devUser()))
        .andExpect(jsonPath("$.archiveAfterDays").value(120))
        .andExpect(jsonPath("$.deleteAfterDays").value(400));
    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            "SELECT before::text AS before, after::text AS after FROM audit_log"
                + " WHERE event_type = ?",
            EVENT);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().get("before").toString())
        .contains("\"archiveAfterDays\":90")
        .contains("\"overridden\":false");
    assertThat(events.getFirst().get("after").toString())
        .contains("\"deleteAfterDays\":400")
        .contains("\"overridden\":true");
  }

  @Test
  void anUnchangedValueWritesNoEventAndAResetReturnsToTheDefaults() throws Exception {
    String body = "{\"archiveAfterDays\":100,\"deleteAfterDays\":200}";
    mockMvc.perform(put(URL).with(devAdmin()).content(body)).andExpect(status().isOk());
    mockMvc.perform(put(URL).with(devAdmin()).content(body)).andExpect(status().isOk());
    mockMvc
        .perform(put(URL).with(devAdmin()).content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archiveAfterDays").value(90))
        .andExpect(jsonPath("$.overridden").value(false));
    mockMvc.perform(put(URL).with(devAdmin()).content("{}")).andExpect(status().isOk());

    assertThat(eventCount()).isEqualTo(2);
  }

  @Test
  void aValueOutsideItsBoundsOrOnePeriodAloneIsRefused() throws Exception {
    mockMvc
        .perform(
            put(URL).with(devAdmin()).content("{\"archiveAfterDays\":89,\"deleteAfterDays\":365}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put(URL).with(devAdmin()).content("{\"archiveAfterDays\":90,\"deleteAfterDays\":3651}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(put(URL).with(devAdmin()).content("{\"archiveAfterDays\":120}"))
        .andExpect(status().isBadRequest());

    assertThat(settings.current().archiveAfterDays()).isEqualTo(90);
    assertThat(eventCount()).isZero();
  }

  @Test
  void onlyTheSystemAdministrationReadsAndSetsThePeriods() throws Exception {
    mockMvc.perform(get(URL).with(devUser())).andExpect(status().isForbidden());
    mockMvc
        .perform(
            put(URL).with(devUser()).content("{\"archiveAfterDays\":90,\"deleteAfterDays\":30}"))
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
