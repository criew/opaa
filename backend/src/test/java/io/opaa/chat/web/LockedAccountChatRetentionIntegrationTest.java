package io.opaa.chat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.chat.LockedAccountChatCleanupSettings;
import io.opaa.test.OpaaIntegrationTest;
import java.sql.Timestamp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Switch and period of the deletion of the chats of locked local accounts live in the database
 * (ADR-0060, Entscheidung 1): the factory defaults are the former defaults of the configuration,
 * switching off forgets the count at once, and every change is an audit event.
 */
@OpaaIntegrationTest
class LockedAccountChatRetentionIntegrationTest {

  private static final String URL = "/api/v1/admin/retention/locked-account-chats";
  private static final String EVENT = "LOCKED_ACCOUNT_CHAT_CLEANUP_SETTINGS_CHANGED";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private LockedAccountChatCleanupSettings settings;

  private Timestamp countingSinceBefore;

  @BeforeEach
  void provisionCallers() throws Exception {
    mockMvc.perform(get("/api/v1/spaces").with(devUser())).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/spaces").with(devAdmin())).andExpect(status().isOk());
    countingSinceBefore =
        jdbcTemplate.queryForObject(
            "SELECT counting_since FROM chat_locked_account_cleanup_state WHERE id = 1",
            Timestamp.class);
  }

  @AfterEach
  void restore() {
    jdbcTemplate.update("DELETE FROM audit_log WHERE event_type = ?", EVENT);
    jdbcTemplate.update(
        "UPDATE chat_locked_account_cleanup_state SET counting_since = ? WHERE id = 1",
        countingSinceBefore);
  }

  @Test
  void withoutARowTheDeletionIsOnWithTheFormer365Days() throws Exception {
    mockMvc
        .perform(get(URL).with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.deleteAfterDays").value(365))
        .andExpect(jsonPath("$.defaultEnabled").value(true))
        .andExpect(jsonPath("$.defaultDeleteAfterDays").value(365))
        .andExpect(jsonPath("$.overridden").value(false));
  }

  @Test
  void switchingOffForgetsTheCountAndIsAuditedOnce() throws Exception {
    jdbcTemplate.update(
        "UPDATE chat_locked_account_cleanup_state SET counting_since = now() WHERE id = 1");

    String off = "{\"enabled\":false,\"deleteAfterDays\":365}";
    mockMvc
        .perform(put(URL).with(devAdmin()).content(off))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.overridden").value(true));
    mockMvc.perform(put(URL).with(devAdmin()).content(off)).andExpect(status().isOk());

    assertThat(settings.current().enabled()).isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT counting_since FROM chat_locked_account_cleanup_state WHERE id = 1",
                Timestamp.class))
        .isNull();
    assertThat(eventCount()).isOne();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT after::text FROM audit_log WHERE event_type = ?", String.class, EVENT))
        .contains("\"enabled\":false");
  }

  @Test
  void aPeriodBelowTheFloorOrASwitchAloneIsRefused() throws Exception {
    mockMvc
        .perform(put(URL).with(devAdmin()).content("{\"enabled\":true,\"deleteAfterDays\":89}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(put(URL).with(devAdmin()).content("{\"enabled\":false}"))
        .andExpect(status().isBadRequest());

    assertThat(settings.current().enabled()).isTrue();
    assertThat(eventCount()).isZero();
  }

  @Test
  void onlyTheSystemAdministrationReadsAndSetsTheDeletion() throws Exception {
    mockMvc.perform(get(URL).with(devUser())).andExpect(status().isForbidden());
    mockMvc
        .perform(put(URL).with(devUser()).content("{\"enabled\":false,\"deleteAfterDays\":365}"))
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
