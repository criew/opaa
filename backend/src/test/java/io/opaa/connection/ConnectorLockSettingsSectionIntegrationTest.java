package io.opaa.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.ratelimit.InstallationCounters;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The connector release in the settings document (ADR-0060, Entscheidung 12): per source type its
 * lock and, where profiles are optional, "Nur über Zugänge", matched by the type key and applied
 * through the administration's write paths with their audit events. A requirement without a profile
 * that could take over refuses the whole document.
 */
@OpaaIntegrationTest
class ConnectorReleaseSettingsSectionIntegrationTest {

  private static final String TYPE = "PROFILE_PROBE";
  private static final String LOCKED = TYPE + ".locked";
  private static final String REQUIRED = TYPE + ".profileRequired";
  private static final String SECTION = "connectorRelease";
  private static final String EXPORT = "/api/v1/system/settings/export";
  private static final String PREVIEW = "/api/v1/system/settings/import/preview";
  private static final String IMPORT = "/api/v1/system/settings/import";
  private static final String ADMIN = "dev-admin";

  /** The installation-wide counter of preview and import, as {@code SettingsTransferService}. */
  private static final String TRANSFER_COUNTER = "settings-transfer";

  private static final Duration TRANSFER_WINDOW = Duration.ofHours(1);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private InstallationCounters counters;

  private final List<UUID> profiles = new ArrayList<>();
  private Instant methodStartedAt;

  @BeforeEach
  void setUp() {
    methodStartedAt = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    counters.reset(TRANSFER_COUNTER, TRANSFER_WINDOW);
    jdbc.update("DELETE FROM connector_type_policies WHERE source_type = ?", TYPE);
  }

  @AfterEach
  void tearDown() {
    counters.reset(TRANSFER_COUNTER, TRANSFER_WINDOW);
    jdbc.update("DELETE FROM connector_type_policies WHERE source_type = ?", TYPE);
    for (UUID profile : profiles) {
      ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
    jdbc.update(
        "DELETE FROM audit_log WHERE actor_kind = 'USER' AND object_type = 'SYSTEM_SETTING'"
            + " AND recorded_at >= ?",
        Timestamp.from(methodStartedAt));
  }

  @Test
  void theExportNamesTheLockOfEveryTypeAndTheRequirementWhereProfilesAreOptional()
      throws Exception {
    ObjectNode section = section(export());

    assertThat(section.get(LOCKED).booleanValue()).isFalse();
    assertThat(section.get(REQUIRED).isNull()).isTrue();
    assertThat(section.get("FILESYSTEM.locked").booleanValue()).isFalse();
    assertThat(section.has("FILESYSTEM.profileRequired")).isFalse();
    assertThat(section.has("UPLOAD.locked")).isFalse();
  }

  @Test
  void aLockIsMarkedInThePreviewAndAppliedThroughTheLockWithItsAuditEvent() throws Exception {
    ObjectNode document = export();
    section(document).put(LOCKED, true);
    String body = JSON.writeValueAsString(document);

    send(PREVIEW, body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(1))
        .andExpect(jsonPath("$.changes[0].section").value(SECTION))
        .andExpect(jsonPath("$.changes[0].key").value(LOCKED))
        .andExpect(jsonPath("$.changes[0].securityRelevant").value(true))
        .andExpect(jsonPath("$.changes[0].before").value(false))
        .andExpect(jsonPath("$.changes[0].after").value(true));
    assertThat(lockedAt()).isNull();

    send(IMPORT, body).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(true));

    assertThat(lockedAt()).isNotNull();
    assertThat(auditEventTypes()).containsExactly("CONNECTOR_LOCKED", "SETTINGS_IMPORTED");
    send(IMPORT, body).andExpect(status().isOk()).andExpect(jsonPath("$.changes").isEmpty());
  }

  @Test
  void aRequirementWithoutAProfileThatCouldTakeOverRefusesTheWholeDocument() throws Exception {
    ObjectNode document = export();
    section(document).put(LOCKED, true).put(REQUIRED, "RUNS");

    send(IMPORT, JSON.writeValueAsString(document))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value(SECTION + "." + REQUIRED))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("PROFILE_REQUIREMENT_NEEDS_PROFILE"))
        .andExpect(
            jsonPath("$.fieldErrors[0].message")
                .value(org.hamcrest.Matchers.containsString("Zugang")));

    assertThat(lockedAt()).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM connector_type_policies WHERE source_type = ?",
                Integer.class,
                TYPE))
        .isZero();
    assertThat(auditEventTypes()).isEmpty();
  }

  @Test
  void aRequirementWithAProfileThatCouldTakeOverIsSwitchedOnWithItsAuditEvent() throws Exception {
    createProfile();
    ObjectNode document = export();
    section(document).put(REQUIRED, "LOCKED");

    send(IMPORT, JSON.writeValueAsString(document))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes[0].key").value(REQUIRED))
        .andExpect(jsonPath("$.changes[0].securityRelevant").value(true))
        .andExpect(jsonPath("$.changes[0].after").value("LOCKED"));

    assertThat(section(export()).get(REQUIRED).stringValue()).isEqualTo("LOCKED");
    assertThat(auditEventTypes())
        .containsExactly("CONNECTOR_PROFILE_REQUIRED", "SETTINGS_IMPORTED");

    section(document).putNull(REQUIRED);
    send(IMPORT, JSON.writeValueAsString(document)).andExpect(status().isOk());
    assertThat(section(export()).get(REQUIRED).isNull()).isTrue();
  }

  @Test
  void anUnknownRequirementValueIsRefusedUnderItsKey() throws Exception {
    ObjectNode document = export();
    section(document).put(REQUIRED, "SOMETIMES");

    send(IMPORT, JSON.writeValueAsString(document))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value(SECTION + "." + REQUIRED));
  }

  private Timestamp lockedAt() {
    List<Timestamp> found =
        jdbc.queryForList(
            "SELECT locked_at FROM connector_type_policies WHERE source_type = ?",
            Timestamp.class,
            TYPE);
    return found.isEmpty() ? null : found.get(0);
  }

  private void createProfile() throws Exception {
    String body =
        mockMvc
            .perform(
                as(post("/api/v1/admin/connection-profiles"))
                    .content(
                        """
                        {"name": "Zugang Übertragung %s", "sourceType": "%s",
                         "serverUrl": "https://probe.example.org", "authMethod": "NONE",
                         "ownership": "LIBRARY"}
                        """
                            .formatted(UUID.randomUUID(), TYPE)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    profiles.add(UUID.fromString(JsonPath.read(body, "$.id")));
  }

  private ObjectNode export() throws Exception {
    String body =
        mockMvc
            .perform(as(get(EXPORT)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return (ObjectNode) JSON.readTree(body);
  }

  private static ObjectNode section(ObjectNode document) {
    return (ObjectNode) document.get("sections").get(SECTION);
  }

  private ResultActions send(String path, String body) throws Exception {
    return mockMvc.perform(as(post(path)).content(body));
  }

  /** The audit events of this method, oldest first, without the profile's own creation. */
  private List<String> auditEventTypes() {
    return jdbc
        .queryForList(
            "SELECT event_type FROM audit_log WHERE actor_kind = 'USER'"
                + " AND object_type = 'SYSTEM_SETTING' AND recorded_at >= ?"
                + " AND event_type NOT LIKE 'CONNECTION_PROFILE_%' ORDER BY recorded_at",
            String.class, Timestamp.from(methodStartedAt))
        .stream()
        .toList();
  }

  private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, ADMIN)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
