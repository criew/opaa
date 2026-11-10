package io.opaa.llm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opaa.auth.DevAuthFilter;
import io.opaa.llm.textrecognition.TextRecognitionEndpoints;
import io.opaa.test.OpaaPropertyVariantIntegrationTest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
 * The text recognition role in the model administration with the operation's switch on, against the
 * Flyway schema and a stub standing in for docling-serve and for an OpenAI-compatible vision model:
 * the key stored encrypted, never answered and sent in the header of its connection type, every
 * change an audit event, the address check and redirects as for every model role, the test's
 * outcomes, and the role in the status overview.
 */
@OpaaPropertyVariantIntegrationTest
class TextRecognitionModelAdministrationIntegrationTest {

  private static final String KEY = "ocr-schluessel-geheim";
  private static final String EVENT = "TEXT_RECOGNITION_MODEL_CHANGED";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String DOCLING_SUCCESS =
      "{\"status\":\"success\",\"errors\":[],\"document\":{\"json_content\":{\"texts\":"
          + "[{\"text\":\"OPAA Testseite\"}],\"tables\":[]}}}";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TextRecognitionEndpoints endpoints;

  private HttpServer server;
  private String serverUrl;
  private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicInteger conversions = new AtomicInteger();

  @BeforeEach
  void setUp() throws Exception {
    deleteRows();
    mockMvc.perform(get("/api/v1/spaces").with(devUser())).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/spaces").with(devAdmin())).andExpect(status().isOk());
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/docling/health", exchange -> respond(exchange, 200, "{\"status\":\"ok\"}"));
    server.createContext(
        "/docling/v1/convert/file",
        exchange -> {
          conversions.incrementAndGet();
          apiKeyHeader.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.getRequestBody().readAllBytes();
          if (!KEY.equals(apiKeyHeader.get())) {
            respond(exchange, 401, "{\"detail\":\"Api key is required\"}");
            return;
          }
          respond(exchange, 200, DOCLING_SUCCESS);
        });
    server.createContext(
        "/docling/v1/status/poll/",
        exchange -> {
          boolean accepted = KEY.equals(exchange.getRequestHeaders().getFirst("X-Api-Key"));
          respond(exchange, accepted ? 404 : 401, "{\"detail\":\"Task not found.\"}");
        });
    server.createContext(
        "/vision/v1/models",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          apiKeyHeader.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
          respond(exchange, 200, "{\"data\":[{\"id\":\"paddle-vl\"}]}");
        });
    server.createContext(
        "/vision/v1/chat/completions",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          respond(
              exchange,
              200,
              "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Testseite\"}}]}");
        });
    server.createContext(
        "/moved/health",
        exchange -> {
          exchange.getResponseHeaders().add("Location", serverUrl + "/docling/health");
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
  void withoutARowTheFactoryDefaultShowsTheOperationAllowsTextRecognition() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/models/text-recognition").with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.connectionType").value("DOCLING_SERVE"))
        .andExpect(jsonPath("$.baseUrl").doesNotExist())
        .andExpect(jsonPath("$.apiKeySet").value(false))
        .andExpect(jsonPath("$.state").value("DISABLED"))
        .andExpect(jsonPath("$.textRecognitionAvailable").value(true));
  }

  @Test
  void aChangeIsStoredEncryptedAuditedWithoutTheKeyAndTheKeyGoesAsXApiKey() throws Exception {
    String response =
        mockMvc
            .perform(
                put("/api/v1/admin/models/text-recognition")
                    .with(devAdmin())
                    .content(
                        "{\"enabled\":true,\"connectionType\":\"DOCLING_SERVE\",\"baseUrl\":\""
                            + serverUrl
                            + "/docling\",\"model\":\"ignored\",\"apiKey\":\""
                            + KEY
                            + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.connectionType").value("DOCLING_SERVE"))
            .andExpect(jsonPath("$.baseUrl").value(serverUrl + "/docling"))
            .andExpect(jsonPath("$.model").doesNotExist())
            .andExpect(jsonPath("$.apiKeySet").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).doesNotContain(KEY);

    String ciphertext =
        jdbcTemplate.queryForObject(
            "SELECT api_key_ciphertext FROM text_recognition_model", String.class);
    assertThat(ciphertext).isNotBlank().doesNotContain(KEY);

    List<Map<String, Object>> events = events();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).get("object_label")).isEqualTo("Texterkennung");
    assertThat(json(events.get(0), "before"))
        .containsEntry("enabled", false)
        .containsEntry("apiKeySet", false);
    assertThat(json(events.get(0), "after"))
        .containsEntry("enabled", true)
        .containsEntry("connectionType", "DOCLING_SERVE")
        .containsEntry("baseUrl", serverUrl + "/docling")
        .containsEntry("apiKeyChange", "SET")
        .doesNotContainValue(KEY);

    mockMvc
        .perform(
            post("/api/v1/admin/models/text-recognition/test")
                .with(devAdmin())
                .content(
                    "{\"connectionType\":\"DOCLING_SERVE\",\"baseUrl\":\""
                        + serverUrl
                        + "/docling\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.outcome").value("READY"))
        .andExpect(jsonPath("$.failedStage").doesNotExist());
    assertThat(apiKeyHeader.get()).isEqualTo(KEY);
    assertThat(authorization.get()).isNull();

    mockMvc
        .perform(
            put("/api/v1/admin/models/text-recognition")
                .with(devAdmin())
                .content(
                    "{\"enabled\":true,\"connectionType\":\"DOCLING_SERVE\",\"baseUrl\":\""
                        + serverUrl
                        + "/docling\"}"))
        .andExpect(status().isOk());
    assertThat(events()).as("a change to the same values writes nothing").hasSize(1);
  }

  @Test
  void theTestTellsAWrongKeyFromAWorkingService() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/models/text-recognition/test")
                .with(devAdmin())
                .content(
                    "{\"connectionType\":\"DOCLING_SERVE\",\"baseUrl\":\""
                        + serverUrl
                        + "/docling\",\"apiKey\":\"falsch\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.outcome").value("KEY_REJECTED"))
        .andExpect(jsonPath("$.failedStage").value("AUTHENTICATION"));
  }

  @Test
  void aVisionModelGetsTheKeyAsBearer() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/models/text-recognition/test")
                .with(devAdmin())
                .content(
                    "{\"connectionType\":\"OPENAI_VISION\",\"baseUrl\":\""
                        + serverUrl
                        + "/vision/v1\",\"model\":\"paddle-vl\",\"apiKey\":\""
                        + KEY
                        + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("READY"));
    assertThat(authorization.get()).isEqualTo("Bearer " + KEY);
    assertThat(apiKeyHeader.get()).isNull();
  }

  @Test
  void anAddressWithCredentialsAnInternalTargetAndARedirectAreRefused() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/admin/models/text-recognition")
                .with(devAdmin())
                .content(
                    "{\"enabled\":true,\"connectionType\":\"DOCLING_SERVE\","
                        + "\"baseUrl\":\"http://nutzer:geheim@ocr.example:5001\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            result ->
                assertThat(result.getResponse().getContentAsString()).doesNotContain("geheim"));
    mockMvc
        .perform(
            put("/api/v1/admin/models/text-recognition")
                .with(devAdmin())
                .content(
                    "{\"enabled\":true,\"connectionType\":\"DOCLING_SERVE\","
                        + "\"baseUrl\":\"http://10.11.12.13:5001\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].code").value("BLOCKED"));
    mockMvc
        .perform(
            post("/api/v1/admin/models/text-recognition/test")
                .with(devAdmin())
                .content(
                    "{\"connectionType\":\"DOCLING_SERVE\",\"baseUrl\":\"http://10.11.12.13:5001\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].code").value("BLOCKED"));
    assertThat(events()).isEmpty();

    mockMvc
        .perform(
            post("/api/v1/admin/models/text-recognition/test")
                .with(devAdmin())
                .content(
                    "{\"connectionType\":\"DOCLING_SERVE\",\"baseUrl\":\""
                        + serverUrl
                        + "/moved\",\"apiKey\":\""
                        + KEY
                        + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("UNREACHABLE"));
    assertThat(conversions).as("the redirect was not followed").hasValue(0);
  }

  @Test
  void onlyTheSystemAdministrationReachesTheRole() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/models/text-recognition").with(devUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            put("/api/v1/admin/models/text-recognition")
                .with(devUser())
                .content("{\"enabled\":false,\"connectionType\":\"DOCLING_SERVE\"}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/admin/models/text-recognition/test")
                .with(devUser())
                .content(
                    "{\"connectionType\":\"DOCLING_SERVE\",\"baseUrl\":\""
                        + serverUrl
                        + "/docling\"}"))
        .andExpect(status().isForbidden());
    assertThat(conversions).hasValue(0);
  }

  @Test
  void theRoleAppearsInTheStatusOverview() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/admin/models/text-recognition")
                .with(devAdmin())
                .content(
                    "{\"enabled\":true,\"connectionType\":\"DOCLING_SERVE\",\"baseUrl\":\""
                        + serverUrl
                        + "/docling\"}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/api/v1/admin/search/status").with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.modelRoles[?(@.role == 'TEXT_RECOGNITION')].endpoint")
                .value(serverUrl + "/docling"));
  }

  private void deleteRows() {
    jdbcTemplate.update("DELETE FROM text_recognition_model");
    jdbcTemplate.update("DELETE FROM audit_log WHERE event_type = ?", EVENT);
    jdbcTemplate.update("DELETE FROM model_role_observations WHERE role = 'TEXT_RECOGNITION'");
    endpoints.invalidateAll();
  }

  private List<Map<String, Object>> events() {
    return jdbcTemplate.queryForList(
        "SELECT object_label, before, after FROM audit_log WHERE event_type = ?"
            + " ORDER BY recorded_at, event_id",
        EVENT);
  }

  private static Map<String, Object> json(Map<String, Object> row, String column) {
    return JSON.readValue((String) row.get(column), new TypeReference<Map<String, Object>>() {});
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
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
