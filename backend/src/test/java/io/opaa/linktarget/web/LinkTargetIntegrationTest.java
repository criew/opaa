package io.opaa.linktarget.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.test.OpaaIntegrationTest;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The allowed link targets over the API: every signed-in person reads them, only the system
 * administration changes them, entries are normalized or refused with a German message, a change is
 * audited, and a write that bypasses this instance is visible on the next read. {@code
 * SeededRowRestorer} puts the settings row back after every method.
 */
@OpaaIntegrationTest
class LinkTargetIntegrationTest {

  private static final String READ = "/api/v1/link-targets";
  private static final String WRITE = "/api/v1/system/link-targets";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;

  private Instant methodStartedAt;

  @BeforeEach
  void setUp() {
    methodStartedAt = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
  }

  @AfterEach
  void tearDown() {
    jdbc.update(
        "DELETE FROM audit_log WHERE event_type = 'LINK_TARGETS_CHANGED' AND recorded_at >= ?",
        Timestamp.from(methodStartedAt));
  }

  @Test
  void theDefaultIsEmptyAndReadableForEverySignedInPerson() throws Exception {
    mockMvc
        .perform(get(READ).with(dev("dev-user")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowedOrigins").isEmpty())
        .andExpect(jsonPath("$.updatedAt").isNotEmpty());
  }

  @Test
  void onlyTheSystemAdministrationChangesTheList() throws Exception {
    mockMvc
        .perform(
            put(WRITE)
                .with(dev("dev-user"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"allowedOrigins\":[\"https://x.example\"]}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get(READ).with(dev("dev-user")))
        .andExpect(jsonPath("$.allowedOrigins").isEmpty());
  }

  @Test
  void validEntriesAreStoredNormalizedAndTheChangeIsAudited() throws Exception {
    mockMvc
        .perform(
            put(WRITE)
                .with(dev("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"allowedOrigins\":[\"HTTPS://Intranet.Stadt.Example:443/\","
                        + "\"https://www.service.bund.de\",\"https://intranet.stadt.example\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowedOrigins[0]").value("https://intranet.stadt.example"))
        .andExpect(jsonPath("$.allowedOrigins[1]").value("https://www.service.bund.de"))
        .andExpect(jsonPath("$.allowedOrigins.length()").value(2));

    mockMvc
        .perform(get(READ).with(dev("dev-user")))
        .andExpect(jsonPath("$.allowedOrigins.length()").value(2));
    List<Map<String, Object>> events = events();
    assertThat(events).hasSize(1);
    assertThat(String.valueOf(events.getFirst().get("before"))).contains("[]");
    assertThat(String.valueOf(events.getFirst().get("after")))
        .contains("https://intranet.stadt.example")
        .contains("https://www.service.bund.de");
  }

  @Test
  void anUnchangedListWritesNoAuditEntry() throws Exception {
    mockMvc
        .perform(
            put(WRITE)
                .with(dev("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"allowedOrigins\":[]}"))
        .andExpect(status().isOk());

    assertThat(events()).isEmpty();
  }

  @Test
  void anInvalidEntryIsRefusedWithAGermanMessageAndNothingIsStored() throws Exception {
    mockMvc
        .perform(
            put(WRITE)
                .with(dev("dev-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"allowedOrigins\":[\"https://ok.example\",\"https://*.stadt.example\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Platzhalter")));

    mockMvc
        .perform(get(READ).with(dev("dev-user")))
        .andExpect(jsonPath("$.allowedOrigins").isEmpty());
    assertThat(events()).isEmpty();
  }

  /** Another instance writes the same row; this one reads it on the next call, no cache between. */
  @Test
  void aChangeMadeElsewhereIsVisibleOnTheNextRead() throws Exception {
    mockMvc.perform(get(READ).with(dev("dev-user"))).andExpect(status().isOk());

    jdbc.update(
        "UPDATE link_target_settings SET allowed_origins = ARRAY['https://andere-instanz.example']"
            + " WHERE id = 1");

    mockMvc
        .perform(get(READ).with(dev("dev-user")))
        .andExpect(jsonPath("$.allowedOrigins[0]").value("https://andere-instanz.example"));
  }

  private List<Map<String, Object>> events() {
    return jdbc.queryForList(
        "SELECT CAST(before AS text) AS before, CAST(after AS text) AS after FROM audit_log"
            + " WHERE event_type = 'LINK_TARGETS_CHANGED' AND recorded_at >= ?",
        Timestamp.from(methodStartedAt));
  }

  private static RequestPostProcessor dev(String devUser) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, devUser);
      return request;
    };
  }
}
