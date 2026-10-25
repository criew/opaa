package io.opaa.connection.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.connection.token.ConnectionLifecycleSettings;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The thresholds and periods of the connections against the Flyway schema: the system
 * administration reads and sets them within their bounds, every change is audited with before and
 * after, and the runs read the stored value on every call, so a change made on another instance
 * holds without a restart.
 */
@OpaaIntegrationTest
class ConnectionLifecycleSettingsIntegrationTest {

  private static final String PATH = "/api/v1/admin/connection-lifecycle-settings";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ConnectionLifecycleSettings settings;

  @BeforeEach
  @AfterEach
  void removeOwnRows() {
    jdbc.update("DELETE FROM connection_lifecycle_settings");
    jdbc.update("DELETE FROM audit_log WHERE event_type = 'CONNECTION_LIFECYCLE_SETTING_CHANGED'");
  }

  @Test
  void withoutOwnValuesTheFactoryDefaultsApply() throws Exception {
    mockMvc
        .perform(get(PATH).with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.minimumGroupSize").value(5))
        .andExpect(jsonPath("$.settings.length()").value(3))
        .andExpect(jsonPath("$.settings[0].setting").value("INACTIVITY_THRESHOLD_DAYS"))
        .andExpect(jsonPath("$.settings[0].value").value(90))
        .andExpect(jsonPath("$.settings[0].defaultValue").value(90))
        .andExpect(jsonPath("$.settings[0].minValue").value(30))
        .andExpect(jsonPath("$.settings[0].maxValue").value(365))
        .andExpect(jsonPath("$.settings[0].overridden").value(false))
        .andExpect(jsonPath("$.settings[1].setting").value("PRIVATE_LIBRARY_DELETION_DAYS"))
        .andExpect(jsonPath("$.settings[1].value").value(30))
        .andExpect(jsonPath("$.settings[2].setting").value("EXPIRED_CONNECTION_WARNING_THRESHOLD"))
        .andExpect(jsonPath("$.settings[2].value").value(10));
  }

  @Test
  void aChangeIsStoredAuditedAndReadByTheRuns() throws Exception {
    update("INACTIVITY_THRESHOLD_DAYS", "{\"value\":120}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.value").value(120))
        .andExpect(jsonPath("$.overridden").value(true));

    assertThat(settings.inactivityThreshold()).isEqualTo(Duration.ofDays(120));
    List<Map<String, Object>> events = auditEntries();
    assertThat(events).hasSize(1);
    assertThat(String.valueOf(events.get(0).get("before")))
        .contains("\"setting\":\"INACTIVITY_THRESHOLD_DAYS\"")
        .contains("\"settingValue\":90")
        .contains("\"overridden\":false");
    assertThat(String.valueOf(events.get(0).get("after")))
        .contains("\"settingValue\":120")
        .contains("\"overridden\":true");
  }

  @Test
  void theSameValueAgainWritesNothing() throws Exception {
    update("PRIVATE_LIBRARY_DELETION_DAYS", "{\"value\":14}").andExpect(status().isOk());
    update("PRIVATE_LIBRARY_DELETION_DAYS", "{\"value\":14}").andExpect(status().isOk());
    update("EXPIRED_CONNECTION_WARNING_THRESHOLD", "{}").andExpect(status().isOk());

    assertThat(auditEntries()).hasSize(1);
  }

  @Test
  void resettingReturnsToTheFactoryDefault() throws Exception {
    update("PRIVATE_LIBRARY_DELETION_DAYS", "{\"value\":14}").andExpect(status().isOk());

    update("PRIVATE_LIBRARY_DELETION_DAYS", "{\"value\":null}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.value").value(30))
        .andExpect(jsonPath("$.overridden").value(false));

    assertThat(settings.privateLibraryDeletionPeriod()).isEqualTo(Duration.ofDays(30));
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM connection_lifecycle_settings", Long.class))
        .isZero();
    assertThat(auditEntries()).hasSize(2);
  }

  @Test
  void aValueOutsideItsBoundsIsRefusedAndWritesNothing() throws Exception {
    update("INACTIVITY_THRESHOLD_DAYS", "{\"value\":29}").andExpect(status().isBadRequest());
    update("INACTIVITY_THRESHOLD_DAYS", "{\"value\":366}").andExpect(status().isBadRequest());
    update("PRIVATE_LIBRARY_DELETION_DAYS", "{\"value\":0}").andExpect(status().isBadRequest());
    update("PRIVATE_LIBRARY_DELETION_DAYS", "{\"value\":91}").andExpect(status().isBadRequest());
    update("EXPIRED_CONNECTION_WARNING_THRESHOLD", "{\"value\":0}")
        .andExpect(status().isBadRequest());
    update("EXPIRED_CONNECTION_WARNING_THRESHOLD", "{\"value\":1001}")
        .andExpect(status().isBadRequest());

    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM connection_lifecycle_settings", Long.class))
        .isZero();
    assertThat(auditEntries()).isEmpty();
  }

  /** The bounds hold in the database as well: no SQL stretches the deletion period past 90 days. */
  @Test
  void theDatabaseHoldsTheBoundsToo() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO connection_lifecycle_settings (setting, setting_value, updated_at)"
                        + " VALUES ('PRIVATE_LIBRARY_DELETION_DAYS', 91, now())"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** What another instance wrote holds on the next read; nothing is kept in this process. */
  @Test
  void aValueWrittenElsewhereHoldsOnTheNextRead() {
    assertThat(settings.expiredConnectionWarningThreshold()).isEqualTo(10);

    jdbc.update(
        "INSERT INTO connection_lifecycle_settings (setting, setting_value, updated_at)"
            + " VALUES ('EXPIRED_CONNECTION_WARNING_THRESHOLD', 25, now())");

    assertThat(settings.expiredConnectionWarningThreshold()).isEqualTo(25);
  }

  @Test
  void onlyTheSystemAdministrationReadsAndSetsThem() throws Exception {
    mockMvc.perform(get(PATH).with(devUser())).andExpect(status().isForbidden());
    mockMvc
        .perform(put(PATH + "/INACTIVITY_THRESHOLD_DAYS").with(devUser()).content("{\"value\":60}"))
        .andExpect(status().isForbidden());
    assertThat(auditEntries()).isEmpty();
  }

  @Test
  void anUnknownSettingIsNotFound() throws Exception {
    update("UNBEKANNT", "{\"value\":5}").andExpect(status().isNotFound());
  }

  private ResultActions update(String setting, String body) throws Exception {
    return mockMvc.perform(put(PATH + "/" + setting).with(devAdmin()).content(body));
  }

  private List<Map<String, Object>> auditEntries() {
    return jdbc.queryForList(
        "SELECT before, after FROM audit_log"
            + " WHERE event_type = 'CONNECTION_LIFECYCLE_SETTING_CHANGED' ORDER BY recorded_at");
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
