package io.opaa.llm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import io.opaa.auth.DevAuthFilter;
import io.opaa.llm.ModelRoleEndpoints;
import io.opaa.llm.RerankModelRole;
import io.opaa.llm.RerankRoleState;
import io.opaa.test.OpaaIntegrationTest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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
 * The rerank and transcription model roles in the model administration against the Flyway schema
 * (ADR-0060, Entscheidung 10): the factory default without a row, every change an audit event with
 * before and after and the key only as set, changed or removed, no event for a change to the same
 * values, the key following only an unchanged endpoint, the target address check, and a test
 * request that follows no redirect.
 */
@OpaaIntegrationTest
class ModelRoleAdministrationIntegrationTest {

  private static final String KEY = "sk-rerank-geheim";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> EVENTS =
      List.of("RERANK_MODEL_CHANGED", "TRANSCRIPTION_MODEL_CHANGED");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ModelRoleEndpoints endpoints;
  @Autowired private RerankModelRole rerankRole;

  private HttpServer server;
  private String serverUrl;
  private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    deleteRows();
    mockMvc.perform(get("/api/v1/spaces").with(devUser())).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/spaces").with(devAdmin())).andExpect(status().isOk());
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/rerank",
        exchange -> {
          receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.getRequestBody().readAllBytes();
          respond(exchange, 200, "{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}");
        });
    server.createContext(
        "/v1/models",
        exchange -> {
          receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          respond(exchange, 200, "{\"data\":[]}");
        });
    server.createContext(
        "/moved/rerank",
        exchange -> {
          exchange.getResponseHeaders().add("Location", serverUrl + "/v1/rerank");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.start();
    serverUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    deleteRows();
  }

  @Test
  void withoutARowBothRolesShowTheFactoryDefault() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/models/rerank").with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.baseUrl").doesNotExist())
        .andExpect(jsonPath("$.apiKeySet").value(false))
        .andExpect(jsonPath("$.state").value("DISABLED"))
        .andExpect(jsonPath("$.retiredVariables").isEmpty());
    mockMvc
        .perform(get("/api/v1/admin/models/transcription").with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.language").value("de"))
        .andExpect(jsonPath("$.speechInputAvailable").value(true))
        .andExpect(jsonPath("$.state").value("DISABLED"));
  }

  @Test
  void aChangeIsStoredEncryptedAuditedWithoutTheKeyAndTakesEffectAtOnce() throws Exception {
    String body =
        "{\"enabled\":true,\"baseUrl\":\""
            + serverUrl
            + "/v1\",\"model\":\"bge-reranker\",\"apiKey\":\""
            + KEY
            + "\"}";
    String response =
        mockMvc
            .perform(put("/api/v1/admin/models/rerank").with(devAdmin()).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.baseUrl").value(serverUrl + "/v1"))
            .andExpect(jsonPath("$.model").value("bge-reranker"))
            .andExpect(jsonPath("$.apiKeySet").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).doesNotContain(KEY);

    String ciphertext =
        jdbcTemplate.queryForObject("SELECT api_key_ciphertext FROM rerank_model", String.class);
    assertThat(ciphertext).isNotBlank().doesNotContain(KEY);

    List<Map<String, Object>> events = events("RERANK_MODEL_CHANGED");
    assertThat(events).hasSize(1);
    assertThat(events.get(0).get("object_label")).isEqualTo("Rerank-Modell");
    assertThat(json(events.get(0), "before"))
        .containsEntry("enabled", false)
        .containsEntry("apiKeySet", false);
    assertThat(json(events.get(0), "after"))
        .containsEntry("enabled", true)
        .containsEntry("baseUrl", serverUrl + "/v1")
        .containsEntry("model", "bge-reranker")
        .containsEntry("apiKeyChange", "SET");
    assertThat(events.get(0).toString()).doesNotContain(KEY);

    assertThat(rerankRole.currentStatus().baseUrl()).isEqualTo(serverUrl + "/v1");
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> assertThat(rerankRole.currentStatus().state()).isEqualTo(RerankRoleState.READY));
    assertThat(receivedAuthorization.get()).isEqualTo("Bearer " + KEY);
  }

  @Test
  void savingTheSameValuesAgainWritesNoAuditEvent() throws Exception {
    String withKey =
        "{\"enabled\":false,\"baseUrl\":\"" + serverUrl + "/v1\",\"model\":\"m\",\"apiKey\":\"k\"}";
    String withoutKey = "{\"enabled\":false,\"baseUrl\":\"" + serverUrl + "/v1/\",\"model\":\"m\"}";
    mockMvc
        .perform(put("/api/v1/admin/models/rerank").with(devAdmin()).content(withKey))
        .andExpect(status().isOk());
    mockMvc
        .perform(put("/api/v1/admin/models/rerank").with(devAdmin()).content(withoutKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.apiKeySet").value(true));
    mockMvc
        .perform(put("/api/v1/admin/models/rerank").with(devAdmin()).content(withoutKey))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            put("/api/v1/admin/models/transcription")
                .with(devAdmin())
                .content("{\"enabled\":false,\"language\":\"de\"}"))
        .andExpect(status().isOk());

    assertThat(events("RERANK_MODEL_CHANGED")).hasSize(2);
    assertThat(events("TRANSCRIPTION_MODEL_CHANGED")).isEmpty();
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transcription_model", Long.class))
        .isZero();
  }

  @Test
  void theStoredKeyStaysOnlyWithTheSameEndpoint() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/admin/models/rerank")
                .with(devAdmin())
                .content(
                    "{\"enabled\":false,\"baseUrl\":\"http://8.8.8.8/v1\",\"model\":\"m\","
                        + "\"apiKey\":\"k\"}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            put("/api/v1/admin/models/rerank")
                .with(devAdmin())
                .content("{\"enabled\":false,\"baseUrl\":\"http://8.8.4.4/v1\",\"model\":\"m\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("rerank.apiKey"));
    mockMvc
        .perform(
            post("/api/v1/admin/models/rerank/test")
                .with(devAdmin())
                .content("{\"baseUrl\":\"" + serverUrl + "/v1\",\"model\":\"m\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("rerank.apiKey"));

    mockMvc
        .perform(
            put("/api/v1/admin/models/rerank")
                .with(devAdmin())
                .content(
                    "{\"enabled\":false,\"baseUrl\":\"http://8.8.4.4/v1\",\"model\":\"m\","
                        + "\"apiKey\":\"\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.apiKeySet").value(false));
    assertThat(json(events("RERANK_MODEL_CHANGED").get(1), "after"))
        .containsEntry("apiKeyChange", "REMOVED");
  }

  @Test
  void anInvalidOrBlockedEntryIsRefusedWithFieldErrorsAndNothingIsStored() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/admin/models/rerank")
                .with(devAdmin())
                .content("{\"enabled\":true,\"baseUrl\":\"\",\"model\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("rerank.baseUrl"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("REQUIRED"))
        .andExpect(jsonPath("$.fieldErrors[1].field").value("rerank.model"));
    mockMvc
        .perform(
            put("/api/v1/admin/models/rerank")
                .with(devAdmin())
                .content(
                    "{\"enabled\":true,\"baseUrl\":\"http://10.20.30.40/v1\",\"model\":\"m\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].code").value("BLOCKED"));
    mockMvc
        .perform(
            post("/api/v1/admin/models/transcription/test")
                .with(devAdmin())
                .content("{\"baseUrl\":\"http://10.20.30.40/v1\",\"model\":\"m\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].code").value("BLOCKED"));
    mockMvc
        .perform(
            put("/api/v1/admin/models/transcription")
                .with(devAdmin())
                .content(
                    "{\"enabled\":false,\"baseUrl\":\"http://8.8.8.8/v1\",\"model\":\"w\","
                        + "\"apiKey\":\"a\\nb\",\"language\":\"deutsch\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.fieldErrors[*].field")
                .value(
                    org.hamcrest.Matchers.containsInAnyOrder(
                        "transcription.apiKey", "transcription.language")));

    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM rerank_model", Long.class))
        .isZero();
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transcription_model", Long.class))
        .isZero();
    assertThat(events("RERANK_MODEL_CHANGED")).isEmpty();
  }

  @Test
  void theTranscriptionRoleStoresItsLanguageAndIsAudited() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/admin/models/transcription")
                .with(devAdmin())
                .content(
                    "{\"enabled\":true,\"baseUrl\":\""
                        + serverUrl
                        + "/v1\",\"model\":\"whisper\",\"language\":\"EN\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.language").value("en"))
        .andExpect(jsonPath("$.apiKeySet").value(false));

    assertThat(json(events("TRANSCRIPTION_MODEL_CHANGED").get(0), "before"))
        .containsEntry("language", "de")
        .containsEntry("enabled", false);
    assertThat(json(events("TRANSCRIPTION_MODEL_CHANGED").get(0), "after"))
        .containsEntry("language", "en")
        .containsEntry("enabled", true)
        .containsEntry("model", "whisper");
  }

  @Test
  void aTestRequestUsesTheStoredKeyForItsEndpointAndFollowsNoRedirect() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/admin/models/rerank")
                .with(devAdmin())
                .content(
                    "{\"enabled\":false,\"baseUrl\":\""
                        + serverUrl
                        + "/v1\",\"model\":\"m\",\"apiKey\":\""
                        + KEY
                        + "\"}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/v1/admin/models/rerank/test")
                .with(devAdmin())
                .content("{\"baseUrl\":\"" + serverUrl + "/v1\",\"model\":\"m\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
    assertThat(receivedAuthorization.get()).isEqualTo("Bearer " + KEY);

    receivedAuthorization.set(null);
    mockMvc
        .perform(
            post("/api/v1/admin/models/rerank/test")
                .with(devAdmin())
                .content(
                    "{\"baseUrl\":\"" + serverUrl + "/moved\",\"model\":\"m\",\"apiKey\":\"x\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.message").value("Der Server antwortete mit HTTP 302."));
    assertThat(receivedAuthorization.get()).isNull();

    mockMvc
        .perform(
            post("/api/v1/admin/models/transcription/test")
                .with(devAdmin())
                .content("{\"baseUrl\":\"" + serverUrl + "/v1\",\"model\":\"w\",\"apiKey\":\"\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
    assertThat(receivedAuthorization.get()).isNull();
  }

  @Test
  void onlyTheSystemAdministrationReachesTheRoles() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/models/rerank").with(devUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            put("/api/v1/admin/models/transcription")
                .with(devUser())
                .content("{\"enabled\":false}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/admin/models/rerank/test")
                .with(devUser())
                .content("{\"baseUrl\":\"" + serverUrl + "/v1\",\"model\":\"m\"}"))
        .andExpect(status().isForbidden());
  }

  private void deleteRows() {
    jdbcTemplate.update("DELETE FROM rerank_model");
    jdbcTemplate.update("DELETE FROM transcription_model");
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE event_type IN (?, ?)", EVENTS.get(0), EVENTS.get(1));
    jdbcTemplate.update(
        "DELETE FROM model_role_observations WHERE base_url LIKE 'http://127.0.0.1:%'");
    endpoints.invalidateAll();
  }

  private List<Map<String, Object>> events(String type) {
    return jdbcTemplate.queryForList(
        "SELECT object_label, before, after FROM audit_log WHERE event_type = ?"
            + " ORDER BY recorded_at, event_id",
        type);
  }

  private static Map<String, Object> json(Map<String, Object> row, String column) {
    return JSON.readValue((String) row.get(column), new TypeReference<Map<String, Object>>() {});
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
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
