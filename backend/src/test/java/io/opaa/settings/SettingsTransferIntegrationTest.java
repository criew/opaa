package io.opaa.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.mail.MailTemplateKey;
import io.opaa.ratelimit.InstallationCounters;
import io.opaa.test.OpaaIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Export and import of the settings over the API (ADR-0060, Entscheidung 12): a round trip changes
 * nothing, secrets never leave, a value outside its bounds or a limit of the operation refuses the
 * whole document, the preview changes nothing, and an import is one "Vorgang" of the audit log. The
 * settings rows are put back by {@code SeededRowRestorer}; this class removes the audit entries and
 * the transfer counter it wrote.
 */
@OpaaIntegrationTest
class SettingsTransferIntegrationTest {

  private static final String EXPORT = "/api/v1/system/settings/export";
  private static final String PREVIEW = "/api/v1/system/settings/import/preview";
  private static final String IMPORT = "/api/v1/system/settings/import";
  private static final String ADMIN = "dev-admin";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private InstallationCounters counters;
  @Autowired private SettingsTransferService transfer;

  private Instant methodStartedAt;
  private String original;

  @BeforeEach
  void setUp() throws Exception {
    methodStartedAt = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    counters.reset(SettingsTransferService.COUNTER_KEY, SettingsTransferService.WINDOW);
    original = export();
  }

  /**
   * Imports the settings found at the start back, through the same write paths, so the caches of
   * this instance follow the rows {@code SeededRowRestorer} puts back afterwards.
   */
  @AfterEach
  void tearDown() throws Exception {
    counters.reset(SettingsTransferService.COUNTER_KEY, SettingsTransferService.WINDOW);
    send(IMPORT, original).andExpect(status().isOk());
    counters.reset(SettingsTransferService.COUNTER_KEY, SettingsTransferService.WINDOW);
    jdbc.update(
        "DELETE FROM audit_log WHERE actor_kind = 'USER' AND object_type = 'SYSTEM_SETTING'"
            + " AND recorded_at >= ?",
        Timestamp.from(methodStartedAt));
  }

  @Test
  void theExportIsAVersionedDocumentOfEverySectionWithoutSecrets() throws Exception {
    jdbc.update("UPDATE mail_settings SET password_ciphertext = 'enc:v1:geheim' WHERE id = 1");

    String body =
        mockMvc
            .perform(get(EXPORT).with(dev(ADMIN)))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    ObjectNode document = (ObjectNode) JSON.readTree(body);

    assertThat(document.get("format").stringValue()).isEqualTo("opaa-settings");
    assertThat(document.get("formatVersion").intValue()).isEqualTo(1);
    assertThat(document.get("exportedAt").isNull()).isFalse();
    assertThat(document.get("sections").propertyNames())
        .containsExactlyElementsOf(transfer.sections().stream().map(SettingsSection::key).toList());
    assertThat(document.get("sections").get("mail").propertyNames())
        .containsExactlyInAnyOrder(
            "enabled", "host", "port", "username", "encryption", "fromAddress", "fromName");
    assertThat(body)
        .doesNotContain("enc:v1:")
        .doesNotContainIgnoringCase("password\"")
        .doesNotContain("passwordSet")
        .doesNotContain("updatedBy")
        .doesNotContain("updatedAt");
  }

  @Test
  void importingTheExportAgainChangesAndRecordsNothing() throws Exception {
    String exported = export();

    send(IMPORT, exported)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(true))
        .andExpect(jsonPath("$.changes").isEmpty());

    assertThat(auditEventTypes()).isEmpty();
    assertThat(export()).isEqualTo(withoutExportedAt(exported));
  }

  @Test
  void anImportAppliesTheChangedValuesAsOneVorgangAndIsIdempotent() throws Exception {
    ObjectNode document = (ObjectNode) JSON.readTree(export());
    sections(document).withObject("auditRetention").put("retentionMonths", 48);
    sections(document)
        .withObject("linkTargets")
        .putArray("allowedOrigins")
        .add("https://intranet.stadt.example");
    String changed = JSON.writeValueAsString(document);

    send(IMPORT, changed)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(true))
        .andExpect(jsonPath("$.changes.length()").value(2))
        .andExpect(jsonPath("$.changes[0].section").value("auditRetention"))
        .andExpect(jsonPath("$.changes[0].sectionLabel").value("Aufbewahrungsfrist des Protokolls"))
        .andExpect(jsonPath("$.changes[0].key").value("retentionMonths"))
        .andExpect(jsonPath("$.changes[0].before").value(36))
        .andExpect(jsonPath("$.changes[0].after").value(48))
        .andExpect(jsonPath("$.changes[1].section").value("linkTargets"));

    ObjectNode after = sections((ObjectNode) JSON.readTree(export()));
    assertThat(after.get("auditRetention").get("retentionMonths").intValue()).isEqualTo(48);
    assertThat(after.get("linkTargets").get("allowedOrigins").get(0).stringValue())
        .isEqualTo("https://intranet.stadt.example");

    List<Map<String, Object>> entries = auditEntries();
    assertThat(entries)
        .extracting(entry -> entry.get("event_type"))
        .containsExactlyInAnyOrder(
            "AUDIT_LOG_CONFIGURATION_CHANGED", "LINK_TARGETS_CHANGED", "SETTINGS_IMPORTED");
    Set<Object> correlations =
        entries.stream().map(entry -> entry.get("correlation_ref")).collect(Collectors.toSet());
    assertThat(correlations).hasSize(1).doesNotContainNull();
    Map<String, Object> imported =
        entries.stream()
            .filter(entry -> "SETTINGS_IMPORTED".equals(entry.get("event_type")))
            .findFirst()
            .orElseThrow();
    assertThat(String.valueOf(imported.get("after")))
        .contains("\"formatVersion\":1")
        .contains("\"sections\":[\"auditRetention\",\"linkTargets\"]");

    send(IMPORT, changed).andExpect(status().isOk()).andExpect(jsonPath("$.changes").isEmpty());
    assertThat(auditEntries()).hasSize(3);
  }

  /**
   * The round trip of every section: one changed value each, imported in one document, comes back
   * in the next export, is named as a change and in {@code SETTINGS_IMPORTED}, and a second import
   * of the same document changes nothing.
   */
  @Test
  void everySectionComesBackFromAnImportAsImported() throws Exception {
    ObjectNode document = (ObjectNode) JSON.readTree(export());
    ObjectNode sections = sections(document);
    retention(sections, "auditRetention", 48, 60);
    retention(sections, "permissionHistoryRetention", 48, 60);
    retention(sections, "diagnosticContextRetention", 6, 9);
    retention(sections, "connectionLogRetention", 18, 20);
    sections.withObject("connectionLifecycle").put("inactivityThresholdDays", 120);
    sections.withObject("branding").put("productName", "Stadt-Assistent");
    sections.withObject("externalAccess").put("tokenRateLimitPerHour", 90);
    sections
        .withObject("linkTargets")
        .putArray("allowedOrigins")
        .add("https://intranet.stadt.example");
    sections.withObject("localAccounts").put("passwordMinLength", 14);
    sections.withObject("mail").put("fromName", "OPAA der Stadt");
    ObjectNode override =
        sections.withObject("mailTemplates").putObject(MailTemplateKey.TEST_MAIL.key());
    override.put("subject", "Eigener Betreff");
    override.put("bodyPlain", "Eigener Text");
    override.putNull("bodyHtml");
    sections.withObject("outboundProxy").putArray("noProxy").add("intranet.stadt.example");
    sections.withObject("privateStorageQuota").put("quotaBytes", 1_073_741_824L);
    sections.withObject("fullSyncRhythm").put("SHAREPOINT", 3);
    sections.withObject("selfDisclosure").put("dailyLimit", 7);
    assertThat(sections.propertyNames())
        .as("every registered section is changed by this test")
        .containsExactlyInAnyOrderElementsOf(
            transfer.sections().stream().map(SettingsSection::key).toList());
    String changed = JSON.writeValueAsString(document);

    String result =
        send(IMPORT, changed)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(export()).isEqualTo(withoutExportedAt(changed));
    Set<String> changedSections = new java.util.TreeSet<>();
    JSON.readTree(result)
        .get("changes")
        .forEach(c -> changedSections.add(c.get("section").stringValue()));
    assertThat(changedSections).containsExactlyInAnyOrderElementsOf(sections.propertyNames());
    String imported =
        jdbc.queryForObject(
            "SELECT after FROM audit_log WHERE event_type = 'SETTINGS_IMPORTED' AND recorded_at >= ?",
            String.class,
            Timestamp.from(methodStartedAt));
    for (String section : sections.propertyNames()) {
      assertThat(imported).contains("\"" + section + "\"");
    }

    send(IMPORT, changed).andExpect(status().isOk()).andExpect(jsonPath("$.changes").isEmpty());
  }

  /**
   * The full-sync rhythm travels per source type: an own value and the factory default ({@code
   * null}) survive the round trip, each change is marked security-relevant, and the same file
   * imported again writes nothing.
   */
  @Test
  void theFullSyncRhythmTravelsPerSourceTypeAndAgainChangesNothing() throws Exception {
    ObjectNode document = (ObjectNode) JSON.readTree(export());
    ObjectNode rhythm = sections(document).withObject("fullSyncRhythm");
    assertThat(rhythm.propertyNames())
        .containsExactlyInAnyOrder(
            "CONFLUENCE", "DROPBOX", "GOOGLE_DRIVE", "ONEDRIVE", "SHAREPOINT");
    rhythm.put("ONEDRIVE", 4);
    rhythm.put("CONFLUENCE", 30);
    String changed = JSON.writeValueAsString(document);

    send(PREVIEW, changed)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(2))
        .andExpect(
            jsonPath("$.changes[*].securityRelevant")
                .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(true))));
    send(IMPORT, changed).andExpect(status().isOk());

    assertThat(export()).isEqualTo(withoutExportedAt(changed));
    assertThat(auditEventTypes()).filteredOn("FULL_SYNC_RHYTHM_CHANGED"::equals).hasSize(2);
    int entries = auditEntries().size();

    send(IMPORT, changed).andExpect(status().isOk()).andExpect(jsonPath("$.changes").isEmpty());
    assertThat(auditEntries()).hasSize(entries);

    rhythm.putNull("ONEDRIVE");
    send(IMPORT, JSON.writeValueAsString(document)).andExpect(status().isOk());
    assertThat(
            sections((ObjectNode) JSON.readTree(export()))
                .get("fullSyncRhythm")
                .get("ONEDRIVE")
                .isNull())
        .isTrue();
  }

  /** A value outside 1 to 365 days or of another type is refused under its field. */
  @Test
  void aFullSyncRhythmOutsideItsBoundsIsRefusedUnderItsField() throws Exception {
    String before = export();
    Map<String, String> refused = new java.util.LinkedHashMap<>();
    refused.put("{\"fullSyncRhythm\": {\"CONFLUENCE\": 0}}", "fullSyncRhythm.CONFLUENCE");
    refused.put("{\"fullSyncRhythm\": {\"DROPBOX\": 366}}", "fullSyncRhythm.DROPBOX");
    refused.put("{\"fullSyncRhythm\": {\"SHAREPOINT\": 2.5}}", "fullSyncRhythm.SHAREPOINT");
    refused.put("{\"fullSyncRhythm\": {\"S3\": 3}}", "fullSyncRhythm");
    for (Map.Entry<String, String> entry : refused.entrySet()) {
      send(
              IMPORT,
              "{\"format\": \"opaa-settings\", \"formatVersion\": 1, \"sections\": "
                  + entry.getKey()
                  + "}")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.fieldErrors[0].field").value(entry.getValue()));
    }

    assertThat(export()).isEqualTo(withoutExportedAt(before));
    assertThat(auditEventTypes()).isEmpty();
  }

  /**
   * The thresholds and periods of the connections travel with their own value or {@code null} for
   * the factory default; the inactivity threshold and the deletion period are marked
   * security-relevant, the warning threshold is not, and the same file again writes nothing.
   */
  @Test
  void theConnectionLifecycleTravelsAndAgainChangesNothing() throws Exception {
    ObjectNode document = (ObjectNode) JSON.readTree(export());
    ObjectNode lifecycle = sections(document).withObject("connectionLifecycle");
    assertThat(lifecycle.propertyNames())
        .containsExactly(
            "inactivityThresholdDays",
            "privateLibraryDeletionDays",
            "expiredConnectionWarningThreshold");
    assertThat(lifecycle.get("privateLibraryDeletionDays").isNull()).isTrue();
    lifecycle.put("inactivityThresholdDays", 180);
    lifecycle.put("privateLibraryDeletionDays", 14);
    lifecycle.put("expiredConnectionWarningThreshold", 25);
    String changed = JSON.writeValueAsString(document);

    send(PREVIEW, changed)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(3))
        .andExpect(
            jsonPath("$.changes[?(@.key == 'inactivityThresholdDays')].securityRelevant")
                .value(org.hamcrest.Matchers.contains(true)))
        .andExpect(
            jsonPath("$.changes[?(@.key == 'privateLibraryDeletionDays')].securityRelevant")
                .value(org.hamcrest.Matchers.contains(true)))
        .andExpect(
            jsonPath("$.changes[?(@.key == 'expiredConnectionWarningThreshold')].securityRelevant")
                .value(org.hamcrest.Matchers.contains(false)));
    send(IMPORT, changed).andExpect(status().isOk());

    assertThat(export()).isEqualTo(withoutExportedAt(changed));
    assertThat(auditEventTypes())
        .filteredOn("CONNECTION_LIFECYCLE_SETTING_CHANGED"::equals)
        .hasSize(3);
    int entries = auditEntries().size();
    send(IMPORT, changed).andExpect(status().isOk()).andExpect(jsonPath("$.changes").isEmpty());
    assertThat(auditEntries()).hasSize(entries);

    lifecycle.putNull("privateLibraryDeletionDays");
    send(IMPORT, JSON.writeValueAsString(document)).andExpect(status().isOk());
    assertThat(
            sections((ObjectNode) JSON.readTree(export()))
                .get("connectionLifecycle")
                .get("privateLibraryDeletionDays")
                .isNull())
        .isTrue();
  }

  /** A value outside the bounds of its setting is refused under its field, nothing written. */
  @Test
  void aConnectionLifecycleValueOutsideItsBoundsIsRefusedUnderItsField() throws Exception {
    String before = export();
    Map<String, String> refused = new java.util.LinkedHashMap<>();
    refused.put(
        "{\"connectionLifecycle\": {\"inactivityThresholdDays\": 29}}",
        "connectionLifecycle.inactivityThresholdDays");
    refused.put(
        "{\"connectionLifecycle\": {\"privateLibraryDeletionDays\": 91}}",
        "connectionLifecycle.privateLibraryDeletionDays");
    refused.put(
        "{\"connectionLifecycle\": {\"expiredConnectionWarningThreshold\": 0}}",
        "connectionLifecycle.expiredConnectionWarningThreshold");
    for (Map.Entry<String, String> entry : refused.entrySet()) {
      send(
              IMPORT,
              "{\"format\": \"opaa-settings\", \"formatVersion\": 1, \"sections\": "
                  + entry.getKey()
                  + "}")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.fieldErrors[0].field").value(entry.getValue()));
    }

    assertThat(export()).isEqualTo(withoutExportedAt(before));
    assertThat(auditEventTypes()).isEmpty();
  }

  private static void retention(ObjectNode sections, String section, int value, int otherwise) {
    ObjectNode values = sections.withObject(section);
    int current = values.get("retentionMonths").intValue();
    values.put("retentionMonths", current == value ? otherwise : value);
  }

  @Test
  void thePreviewNamesTheChangesAndChangesNothing() throws Exception {
    String before = export();
    ObjectNode document = (ObjectNode) JSON.readTree(before);
    sections(document).withObject("selfDisclosure").put("dailyLimit", 7);

    send(PREVIEW, JSON.writeValueAsString(document))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(false))
        .andExpect(jsonPath("$.changes.length()").value(1))
        .andExpect(jsonPath("$.changes[0].section").value("selfDisclosure"))
        .andExpect(jsonPath("$.changes[0].key").value("dailyLimit"))
        .andExpect(jsonPath("$.changes[0].after").value(7));

    assertThat(export()).isEqualTo(withoutExportedAt(before));
    assertThat(auditEventTypes()).isEmpty();
  }

  /** A section the document leaves out stays, a key a section leaves out keeps its value. */
  @Test
  void anOmittedSectionOrKeyKeepsItsValue() throws Exception {
    ObjectNode before = sections((ObjectNode) JSON.readTree(export()));
    String body =
        """
        {"format": "opaa-settings", "formatVersion": 1,
         "sections": {"externalAccess": {"tokenRateLimitPerHour": 90}}}
        """;

    send(IMPORT, body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(1));

    ObjectNode after = sections((ObjectNode) JSON.readTree(export()));
    assertThat(after.get("externalAccess").get("tokenRateLimitPerHour").intValue()).isEqualTo(90);
    assertThat(after.get("externalAccess").get("tokenMaxLifetimeDays"))
        .isEqualTo(before.get("externalAccess").get("tokenMaxLifetimeDays"));
    before.remove("externalAccess");
    after.remove("externalAccess");
    assertThat(after).isEqualTo(before);
  }

  /** Regression guard: a value past a limit of the operation refuses the whole document. */
  @Test
  void aValuePastALimitOfTheOperationRefusesTheWholeDocument() throws Exception {
    String before = export();
    ObjectNode document = (ObjectNode) JSON.readTree(before);
    sections(document).withObject("auditRetention").put("retentionMonths", 48);
    // OPAA_PUBLIC_BASE_URL is not set in the test context: no link could be built
    ObjectNode localAccounts = sections(document).withObject("localAccounts");
    localAccounts.put("selfRegistrationEnabled", true);
    localAccounts.putArray("selfRegistrationAllowedDomains").add("stadt.example");

    send(IMPORT, JSON.writeValueAsString(document)).andExpect(status().isConflict());

    assertThat(export()).isEqualTo(withoutExportedAt(before));
    assertThat(auditEventTypes()).isEmpty();
  }

  @Test
  void aValueOutsideItsBoundsRefusesTheWholeDocument() throws Exception {
    String before = export();
    ObjectNode document = (ObjectNode) JSON.readTree(before);
    sections(document).withObject("auditRetention").put("retentionMonths", 48);
    sections(document).withObject("selfDisclosure").put("dailyLimit", 99);

    send(IMPORT, JSON.writeValueAsString(document)).andExpect(status().isBadRequest());
    send(PREVIEW, JSON.writeValueAsString(document)).andExpect(status().isBadRequest());

    assertThat(export()).isEqualTo(withoutExportedAt(before));
    assertThat(auditEventTypes()).isEmpty();
  }

  @Test
  void aDocumentOfAnotherFormatVersionSectionOrKeyIsRefused() throws Exception {
    List<String> refused =
        List.of(
            "{\"format\": \"anderes\", \"formatVersion\": 1, \"sections\": {}}",
            "{\"format\": \"opaa-settings\", \"formatVersion\": 2, \"sections\": {}}",
            "{\"format\": \"opaa-settings\", \"formatVersion\": 1,"
                + " \"sections\": {\"unbekannt\": {}}}",
            "{\"format\": \"opaa-settings\", \"formatVersion\": 1,"
                + " \"sections\": {\"linkTargets\": {\"wildcard\": true}}}",
            "{\"format\": \"opaa-settings\", \"formatVersion\": 1,"
                + " \"sections\": {\"auditRetention\": {\"retentionMonths\": \"viele\"}}}",
            "kein JSON");
    for (String body : refused) {
      send(IMPORT, body).andExpect(status().isBadRequest());
    }
    assertThat(auditEventTypes()).isEmpty();
  }

  @Test
  void onlyTheSystemAdministrationTransfersSettings() throws Exception {
    String exported = export();

    mockMvc.perform(get(EXPORT).with(dev("dev-user"))).andExpect(status().isForbidden());
    for (String path : List.of(PREVIEW, IMPORT)) {
      mockMvc
          .perform(
              post(path)
                  .with(dev("dev-user"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(exported))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void aDocumentLargerThanTheBoundIsRefused() throws Exception {
    String padding = "x".repeat(300 * 1024);
    String body =
        "{\"format\": \"opaa-settings\", \"formatVersion\": 1, \"sections\": {},"
            + " \"padding\": \""
            + padding
            + "\"}";

    send(IMPORT, body).andExpect(status().isPayloadTooLarge());
  }

  @Test
  void previewAndImportAreLimitedPerInstallation() throws Exception {
    String exported = export();
    for (int i = 0; i < SettingsTransferService.CALLS_PER_WINDOW; i++) {
      send(i % 2 == 0 ? PREVIEW : IMPORT, exported).andExpect(status().isOk());
    }

    send(PREVIEW, exported).andExpect(status().isTooManyRequests());
  }

  /**
   * Regression guard: the import checks every bound the administration's request schema sets, in
   * the write path, and names the field as {@code <section>.<key>}; nothing is stored.
   */
  @Test
  void aValueBeyondABoundOfTheAdministrationIsRefusedWithItsField() throws Exception {
    String template = MailTemplateKey.TEST_MAIL.key();
    Map<String, String> refused = new java.util.LinkedHashMap<>();
    refused.put(
        "{\"mailTemplates\": {\"" + template + "\": {\"subject\": \"\", \"bodyPlain\": \"Text\"}}}",
        "mailTemplates." + template + ".subject");
    refused.put(
        "{\"mailTemplates\": {\""
            + template
            + "\": {\"subject\": \"Betreff\", \"bodyPlain\": \" \"}}}",
        "mailTemplates." + template + ".bodyPlain");
    refused.put(
        "{\"mailTemplates\": {\""
            + template
            + "\": {\"subject\": \""
            + "b".repeat(400)
            + "\", \"bodyPlain\": \"Text\"}}}",
        "mailTemplates." + template + ".subject");
    refused.put(
        "{\"mailTemplates\": {\""
            + template
            + "\": {\"subject\": \"Betreff\", \"bodyPlain\": \""
            + "t".repeat(20_001)
            + "\"}}}",
        "mailTemplates." + template + ".bodyPlain");
    refused.put("{\"mail\": {\"host\": \"" + "h".repeat(300) + "\"}}", "mail.host");
    refused.put("{\"mail\": {\"fromName\": \"" + "n".repeat(121) + "\"}}", "mail.fromName");
    StringBuilder domains = new StringBuilder();
    for (int i = 0; i < 150; i++) {
      domains.append(i == 0 ? "" : ",").append("\"d").append(i).append(".stadt.example\"");
    }
    refused.put(
        "{\"localAccounts\": {\"selfRegistrationAllowedDomains\": [" + domains + "]}}",
        "localAccounts.selfRegistrationAllowedDomains");
    String before = export();

    for (Map.Entry<String, String> entry : refused.entrySet()) {
      String body =
          "{\"format\": \"opaa-settings\", \"formatVersion\": 1, \"sections\": "
              + entry.getKey()
              + "}";
      send(IMPORT, body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.fieldErrors[0].field").value(entry.getValue()));
    }

    assertThat(export()).isEqualTo(withoutExportedAt(before));
    assertThat(auditEventTypes()).isEmpty();
  }

  /**
   * Regression guard: a value the write path normalizes ({@code productName} trimmed, the proxy
   * written as {@code http://host:port}) imported twice writes its entries once.
   */
  @Test
  void aNormalizedValueImportedTwiceIsRecordedOnce() throws Exception {
    String body =
        """
        {"format": "opaa-settings", "formatVersion": 1, "sections": {
          "branding": {"productName": "  Stadt  "},
          "outboundProxy": {"proxyUrl": "proxy.stadt.example:3128"},
          "privateStorageQuota": {"quotaBytes": 1073741824}}}
        """;

    send(IMPORT, body).andExpect(status().isOk());
    int afterFirst = auditEntries().size();
    send(IMPORT, body).andExpect(status().isOk()).andExpect(jsonPath("$.changes").isEmpty());

    assertThat(afterFirst).isEqualTo(4);
    assertThat(auditEntries()).hasSize(afterFirst);
  }

  /**
   * Regression guard: a stored mail password never goes to another server, account or encryption
   * than it was entered for; changing them without a new password is refused, by import and form.
   */
  @Test
  void theStoredMailPasswordDoesNotFollowANewTarget() throws Exception {
    jdbc.update("UPDATE mail_settings SET password_ciphertext = 'enc:v1:geheim' WHERE id = 1");
    try {
      for (String change :
          List.of(
              "\"host\": \"smtp.fremd.example\"",
              "\"port\": 2526",
              "\"username\": \"andere\"",
              "\"encryption\": \"NONE\"")) {
        String body =
            "{\"format\": \"opaa-settings\", \"formatVersion\": 1, \"sections\":"
                + " {\"mail\": {"
                + change
                + "}}}";
        send(PREVIEW, body)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors[0].field").value("mail.password"));
      }
      mockMvc
          .perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                      "/api/v1/system/mail-settings")
                  .with(dev(ADMIN))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"enabled\": false, \"host\": \"smtp.fremd.example\","
                          + " \"encryption\": \"STARTTLS\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
    } finally {
      jdbc.update("UPDATE mail_settings SET password_ciphertext = NULL WHERE id = 1");
    }
  }

  /** The preview marks a change of a security-relevant value and names keys in German. */
  @Test
  void thePreviewMarksSecurityRelevantChangesAndNamesThemInGerman() throws Exception {
    String body =
        """
        {"format": "opaa-settings", "formatVersion": 1, "sections": {
          "linkTargets": {"allowedOrigins": ["https://intranet.stadt.example"]},
          "selfDisclosure": {"dailyLimit": 7}}}
        """;

    send(PREVIEW, body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes[0].key").value("allowedOrigins"))
        .andExpect(jsonPath("$.changes[0].keyLabel").value("Erlaubte Linkziele"))
        .andExpect(jsonPath("$.changes[0].securityRelevant").value(true))
        .andExpect(jsonPath("$.changes[1].keyLabel").value("Tagesgrenze"))
        .andExpect(jsonPath("$.changes[1].securityRelevant").value(false));
  }

  /** Duplicate keys, unknown fields at any level and {@code null} for a list are refused. */
  @Test
  void theDocumentIsReadStrictly() throws Exception {
    String template = MailTemplateKey.TEST_MAIL.key();
    for (String body :
        List.of(
            "{\"format\": \"opaa-settings\", \"format\": \"opaa-settings\", \"formatVersion\": 1,"
                + " \"sections\": {}}",
            "{\"format\": \"opaa-settings\", \"formatVersion\": 1, \"sections\": {},"
                + " \"extra\": 1}",
            "{\"format\": \"opaa-settings\", \"formatVersion\": 1, \"sections\":"
                + " {\"mailTemplates\": {\""
                + template
                + "\": {\"subject\": \"B\", \"bodyPlain\": \"T\", \"locale\": \"en\"}}}}",
            "{\"format\": \"opaa-settings\", \"formatVersion\": 1, \"sections\":"
                + " {\"linkTargets\": {\"allowedOrigins\": null}}}")) {
      send(IMPORT, body).andExpect(status().isBadRequest());
    }
    assertThat(auditEventTypes()).isEmpty();
  }

  /** An unknown key is named shortened and without control characters. */
  @Test
  void aReflectedUnknownKeyIsShortenedAndCleaned() throws Exception {
    String key = "x\\u0007\\n" + "y".repeat(200);
    String body =
        "{\"format\": \"opaa-settings\", \"formatVersion\": 1, \"sections\":"
            + " {\"linkTargets\": {\""
            + key
            + "\": 1}}}";

    String error =
        send(IMPORT, body)
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(error)
        .doesNotContain("\\u0007")
        .doesNotContain("\\n")
        .doesNotContain("y".repeat(100));
  }

  private String export() throws Exception {
    String body =
        mockMvc
            .perform(get(EXPORT).with(dev(ADMIN)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return withoutExportedAt(body);
  }

  private static String withoutExportedAt(String document) {
    ObjectNode node = (ObjectNode) JSON.readTree(document);
    node.remove("exportedAt");
    return JSON.writeValueAsString(node);
  }

  private static ObjectNode sections(ObjectNode document) {
    return (ObjectNode) document.get("sections");
  }

  private ResultActions send(String path, String body) throws Exception {
    return mockMvc.perform(
        post(path).with(dev(ADMIN)).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private List<Map<String, Object>> auditEntries() {
    return jdbc.queryForList(
        "SELECT event_type, correlation_ref, after FROM audit_log WHERE actor_kind = 'USER'"
            + " AND object_type = 'SYSTEM_SETTING' AND recorded_at >= ? ORDER BY recorded_at",
        Timestamp.from(methodStartedAt));
  }

  private List<String> auditEventTypes() {
    return auditEntries().stream().map(entry -> String.valueOf(entry.get("event_type"))).toList();
  }

  private static RequestPostProcessor dev(String devUser) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, devUser);
      return request;
    };
  }
}
