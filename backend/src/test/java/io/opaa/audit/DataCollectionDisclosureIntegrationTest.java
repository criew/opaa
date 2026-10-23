package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.AuditEventType;
import io.opaa.auth.DevAuthFilter;
import io.opaa.compliance.ComplianceManifest.SettingSource;
import io.opaa.compliance.ComplianceManifestLoader;
import io.opaa.compliance.ManifestSnapshot;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The disclosure of the data collection with the configuration export of this instance, over HTTP
 * against the real schema: the manifest joined with the effective values, exactly the deviations an
 * administration made, no secret, only for the system administration, and every retrieval - a
 * refused one included - an audit event.
 */
@OpaaIntegrationTest
class DataCollectionDisclosureIntegrationTest {

  private static final String JSON_PATH = "/api/v1/admin/compliance/data-collection-disclosure";
  private static final String MARKDOWN_PATH = JSON_PATH + "/markdown";
  private static final String RETENTION_KEY = "audit_retention_settings.retention_months";
  private static final String CIPHERTEXT = "S3CR3T-CIPHERTEXT-OF-A-MODEL-KEY";
  private static final String URL_PASSWORD = "S3CR3T-PASSWORD-IN-A-MODEL-ADDRESS";

  /** Secrets the shared test context sets; none of them may leave with the export. */
  private static final List<String> SECRET_KEYS =
      List.of(
          "opaa.auth.local.jwt-secret",
          "opaa.security.credentials.encryption-key",
          "opaa.security.settings.encryption-key",
          "spring.datasource.password");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private Environment environment;

  private final UUID libraryId = UUID.randomUUID();
  private final UUID libraryOwnerId = UUID.randomUUID();
  private final UUID modelId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    deleteOwnEvents();
  }

  /**
   * The HTTP calls act as the dev users of the default organization; the events this class causes
   * all carry the fixed object id of the disclosure.
   */
  @AfterEach
  void tearDown() {
    deleteOwnEvents();
    jdbcTemplate.update("DELETE FROM assets WHERE id = ?", libraryId);
    jdbcTemplate.update(
        "DELETE FROM asset_ownership_history WHERE owner_user_id = ?", libraryOwnerId);
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", libraryOwnerId);
    jdbcTemplate.update("DELETE FROM llm_models WHERE id = ?", modelId);
  }

  @Test
  void theSystemAdministrationReceivesTheManifestJoinedWithTheInstanceValues() throws Exception {
    mockMvc
        .perform(get(JSON_PATH).with(devUser("dev-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.opaaVersion").isNotEmpty())
        .andExpect(jsonPath("$.commit").isNotEmpty())
        .andExpect(jsonPath("$.generatedAt").isNotEmpty())
        .andExpect(
            jsonPath("$.manifest.auditEvents[?(@.id == 'DATA_COLLECTION_DISCLOSURE_EXPORTED')]")
                .isNotEmpty())
        .andExpect(
            jsonPath("$.instance.settings[?(@.key == '" + RETENTION_KEY + "')].effectiveValue")
                .value("36"))
        .andExpect(jsonPath("$.protocol.queryPaths.length()").value(6))
        .andExpect(jsonPath("$.protocol.absentQueryPaths").isNotEmpty());
  }

  @Test
  void aChangedRetentionAndASwitchedOnConnectorAreExactlyTheNewDeviations() throws Exception {
    JsonNode before = exportAsAdmin();

    jdbcTemplate.update(
        "UPDATE audit_retention_settings SET retention_months = 24, updated_at = now()"
            + " WHERE id = 1");
    createLibrary("HTTP_DIRECTORY");
    JsonNode after = exportAsAdmin();

    Set<String> added = new HashSet<>(deviations(after));
    added.removeAll(deviations(before));
    Set<String> removed = new HashSet<>(deviations(before));
    removed.removeAll(deviations(after));
    assertThat(added).containsExactlyInAnyOrder(RETENTION_KEY, "connector:HTTP_DIRECTORY");
    assertThat(removed).isEmpty();
    assertThat(flowActive(before, "source-web")).isFalse();
    assertThat(flowActive(after, "source-web")).isTrue();
    JsonNode retention = setting(after, RETENTION_KEY);
    assertThat(retention.path("effectiveValue").asString()).isEqualTo("24");
    assertThat(retention.path("defaultValue").asString()).isEqualTo("36");
    assertThat(retention.path("changedAt").isNull()).isFalse();
  }

  @Test
  void noSecretLeavesWithTheExport() throws Exception {
    jdbcTemplate.update(
        "INSERT INTO llm_models (id, display_name, base_url, model_identifier, api_key_ciphertext,"
            + " active) VALUES (?, 'Geprüftes Modell', ?, 'llama-test', ?, false)",
        modelId,
        "https://nutzer:" + URL_PASSWORD + "@llm.example/v1",
        CIPHERTEXT);

    String json = body(JSON_PATH, "dev-admin");
    String markdown = body(MARKDOWN_PATH, "dev-admin");

    List<String> secrets = new ArrayList<>(List.of(CIPHERTEXT, URL_PASSWORD));
    for (String key : SECRET_KEYS) {
      String value = environment.getProperty(key);
      if (value != null && value.length() >= 8) {
        secrets.add(value);
      }
    }
    assertThat(secrets)
        .as("the context sets the secrets this test looks for")
        .hasSizeGreaterThan(3);
    for (String secret : secrets) {
      assertThat(json).as("JSON form").doesNotContain(secret);
      assertThat(markdown).as("Markdown form").doesNotContain(secret);
    }
    assertThat(json).contains("https://llm.example/v1");
  }

  @Test
  void anAccountWithoutTheRoleIsRefusedAndTheAttemptIsLogged() throws Exception {
    mockMvc.perform(get(JSON_PATH).with(devUser("dev-user"))).andExpect(status().isForbidden());
    mockMvc.perform(get(MARKDOWN_PATH).with(devUser("dev-user"))).andExpect(status().isForbidden());

    assertThat(ownEvents())
        .extracting(event -> event.get("outcome"))
        .containsExactly("DENIED", "DENIED");
  }

  @Test
  void everyRetrievalWritesOneAuditEventNamingItsFormat() throws Exception {
    body(JSON_PATH, "dev-admin");
    body(MARKDOWN_PATH, "dev-admin");
    body(JSON_PATH, "dev-admin");

    List<Map<String, Object>> events = ownEvents();
    assertThat(events).extracting(event -> event.get("outcome")).containsOnly("SUCCESS");
    assertThat(events)
        .extracting(event -> (String) event.get("after"))
        .satisfiesExactly(
            after -> assertThat(after).contains("JSON"),
            after -> assertThat(after).contains("MARKDOWN"),
            after -> assertThat(after).contains("JSON"));
    assertThat(events).extracting(event -> event.get("object_type")).containsOnly("SYSTEM_SETTING");
  }

  @Test
  void theMarkdownFormIsReadableText() throws Exception {
    mockMvc
        .perform(get(MARKDOWN_PATH).with(devUser("dev-admin")))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/markdown"));

    assertThat(body(MARKDOWN_PATH, "dev-admin"))
        .startsWith("# Auskunft über die Datenerhebung")
        .contains("## Abweichungen von der Voreinstellung")
        .contains("## Protokoll");
  }

  @Test
  void theExportReadsAsTheManifestOfTheDeltaTooling(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("auskunft.json");
    Files.writeString(file, body(JSON_PATH, "dev-admin"), StandardCharsets.UTF_8);

    ManifestSnapshot snapshot = ManifestSnapshot.fromJson(file);

    assertThat(snapshot.sections().get("auditEvents"))
        .containsKey(AuditEventType.DATA_COLLECTION_DISCLOSURE_EXPORTED.name());
    assertThat(snapshot.sections().get("settings")).containsKey(RETENTION_KEY);
  }

  /**
   * Guards the ports: every {@code DATABASE} setting of the manifest has its owning module answer
   * with value and time of change, and only the flows that happen on an action of a person stay
   * open.
   */
  @Test
  void everyStoredSettingAndEverySwitchedFlowIsAnsweredByItsModule() throws Exception {
    JsonNode export = exportAsAdmin();

    Set<String> databaseKeys =
        ComplianceManifestLoader.load().settings().stream()
            .filter(entry -> entry.source() == SettingSource.DATABASE)
            .map(entry -> entry.key())
            .collect(Collectors.toSet());
    assertThat(databaseKeys).isNotEmpty();
    for (String key : databaseKeys) {
      JsonNode setting = setting(export, key);
      assertThat(setting.path("effectiveValue").isNull()).as(key + " has a value").isFalse();
      assertThat(setting.path("changedAt").isNull()).as(key + " has a time of change").isFalse();
    }
    Set<String> open = new HashSet<>();
    for (JsonNode flow : export.path("instance").path("dataFlows")) {
      if (flow.path("active").isNull() || flow.path("active").isMissingNode()) {
        open.add(flow.path("id").asString());
      }
    }
    assertThat(open).containsExactlyInAnyOrder("model-endpoint", "mcp-discovery");
  }

  /** The field list of the protocol description names exactly the columns of the audit log. */
  @Test
  void theProtocolDescriptionNamesEveryColumnOfTheAuditLog() throws Exception {
    List<String> columns =
        jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_schema ="
                + " current_schema() AND table_name = 'audit_log'",
            String.class);
    List<String> fields = new ArrayList<>();
    for (JsonNode field : exportAsAdmin().path("protocol").path("fields")) {
      fields.add(field.path("name").asString());
    }

    assertThat(columns).isNotEmpty();
    assertThat(fields).containsExactlyInAnyOrderElementsOf(columns);
  }

  /**
   * The incident scope is the one query path by person for third parties, the self-disclosure the
   * one for the person herself, and the absent paths name both exceptions.
   */
  @Test
  void theIncidentScopeAndTheSelfDisclosureAreNamedAsThePathsByPerson() throws Exception {
    JsonNode protocol = exportAsAdmin().path("protocol");

    List<String> names = new ArrayList<>();
    for (JsonNode path : protocol.path("queryPaths")) {
      names.add(path.path("name").asString());
    }
    assertThat(names).contains("anlassbezogene Klärung", "Selbstauskunft");
    List<String> absent = new ArrayList<>();
    for (JsonNode path : protocol.path("absentQueryPaths")) {
      absent.add(path.asString());
    }
    assertThat(absent.get(0))
        .contains("außer in der freigegebenen anlassbezogenen Klärung")
        .contains("Selbstauskunft über die eigene Person");
    assertThat(absent)
        .anySatisfy(
            path ->
                assertThat(path)
                    .startsWith("Kein Vollabzug")
                    .contains("einzige Ausnahme die Selbstauskunft"))
        .anySatisfy(
            path ->
                assertThat(path)
                    .startsWith("Keine Sicht")
                    .contains("für die Person selbst ihre Selbstauskunft"));
  }

  private JsonNode exportAsAdmin() throws Exception {
    return JSON.readTree(body(JSON_PATH, "dev-admin"));
  }

  private String body(String path, String subject) throws Exception {
    return mockMvc
        .perform(get(path).with(devUser(subject)))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  /** Deviating setting keys and the connector types libraries read from. */
  private static Set<String> deviations(JsonNode export) {
    Set<String> result = new HashSet<>();
    for (JsonNode setting : export.path("instance").path("settings")) {
      if (setting.path("deviates").asBoolean()) {
        result.add(setting.path("key").asString());
      }
    }
    for (JsonNode connector : export.path("instance").path("connectorTypes")) {
      if (connector.path("libraries").asLong() > 0) {
        result.add("connector:" + connector.path("type").asString());
      }
    }
    return result;
  }

  private static JsonNode setting(JsonNode export, String key) {
    for (JsonNode setting : export.path("instance").path("settings")) {
      if (setting.path("key").asString().equals(key)) {
        return setting;
      }
    }
    throw new AssertionError("no setting " + key);
  }

  private static boolean flowActive(JsonNode export, String id) {
    for (JsonNode flow : export.path("instance").path("dataFlows")) {
      if (flow.path("id").asString().equals(id)) {
        return flow.path("active").asBoolean();
      }
    }
    throw new AssertionError("no data flow " + id);
  }

  private void createLibrary(String sourceType) {
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, organization_id)"
            + " VALUES (?, ?, 'test-issuer', ?, 'Bibliothek', ?)",
        libraryOwnerId,
        "disclosure-" + libraryOwnerId,
        "disclosure-" + libraryOwnerId + "@example.com",
        Organization.DEFAULT_ID);
    jdbcTemplate.update(
        "WITH shell AS (INSERT INTO assets (id, asset_type, organization_id, name, owner_type,"
            + " owner_user_id) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, 'Webquelle', 'USER', ?)"
            + " RETURNING id, organization_id) INSERT INTO knowledge_libraries (id,"
            + " organization_id, source_type, source_url) SELECT id, organization_id, ?,"
            + " 'https://web.example/docs/' FROM shell",
        libraryId,
        Organization.DEFAULT_ID,
        libraryOwnerId,
        sourceType);
  }

  private List<Map<String, Object>> ownEvents() {
    return jdbcTemplate.queryForList(
        "SELECT outcome, object_type, \"after\" FROM audit_log WHERE event_type = ? AND"
            + " object_id = ? ORDER BY recorded_at",
        AuditEventType.DATA_COLLECTION_DISCLOSURE_EXPORTED.name(),
        DataCollectionDisclosureService.OBJECT_ID.toString());
  }

  private void deleteOwnEvents() {
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE event_type = ? AND object_id = ?",
        AuditEventType.DATA_COLLECTION_DISCLOSURE_EXPORTED.name(),
        DataCollectionDisclosureService.OBJECT_ID.toString());
  }

  private static RequestPostProcessor devUser(String subject) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      return request;
    };
  }
}
