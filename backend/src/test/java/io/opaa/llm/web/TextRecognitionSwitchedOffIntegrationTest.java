package io.opaa.llm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import io.opaa.llm.ModelRoleEndpoints;
import io.opaa.llm.TextRecognitionModelRole;
import io.opaa.test.OpaaIntegrationTest;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Text recognition with the operation's switch at its default, off: the role says so, cannot be
 * switched on or tested, and no service is contacted - not even with an enabled row in the table;
 * what is stored stays.
 */
@OpaaIntegrationTest
class TextRecognitionSwitchedOffIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ModelRoleEndpoints endpoints;
  @Autowired private TextRecognitionModelRole role;

  private HttpServer server;
  private String serverUrl;
  private final AtomicInteger calls = new AtomicInteger();

  @BeforeEach
  void setUp() throws Exception {
    deleteRows();
    mockMvc.perform(get("/api/v1/spaces").with(devAdmin())).andExpect(status().isOk());
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
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
  void theRoleCanBePreparedButNeitherSwitchedOnNorTested() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/models/text-recognition").with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.textRecognitionAvailable").value(false))
        .andExpect(jsonPath("$.state").value("DISABLED"));
    mockMvc
        .perform(
            put("/api/v1/admin/models/text-recognition")
                .with(devAdmin())
                .content(
                    "{\"enabled\":true,\"connectionType\":\"DOCLING_SERVE\",\"baseUrl\":\""
                        + serverUrl
                        + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("textRecognition.enabled"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("BLOCKED"));
    mockMvc
        .perform(
            put("/api/v1/admin/models/text-recognition")
                .with(devAdmin())
                .content(
                    "{\"enabled\":false,\"connectionType\":\"DOCLING_SERVE\",\"baseUrl\":\""
                        + serverUrl
                        + "\",\"apiKey\":\"k\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.apiKeySet").value(true));
    mockMvc
        .perform(
            post("/api/v1/admin/models/text-recognition/test")
                .with(devAdmin())
                .content(
                    "{\"connectionType\":\"DOCLING_SERVE\",\"baseUrl\":\"" + serverUrl + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].code").value("BLOCKED"));

    assertThat(calls).hasValue(0);
  }

  @Test
  void anEnabledRowIsNeitherEvaluatedNorContacted() {
    jdbcTemplate.update(
        "INSERT INTO text_recognition_model (id, enabled, connection_type, base_url, updated_at)"
            + " VALUES (1, true, 'DOCLING_SERVE', ?, now())",
        serverUrl);
    endpoints.invalidateAll();

    assertThat(role.active()).isEmpty();
    role.refresh();
    role.probePeriodically();

    assertThat(role.currentStatus().state().name()).isEqualTo("DISABLED");
    assertThat(calls).hasValue(0);
    assertThat(
            jdbcTemplate.queryForObject("SELECT count(*) FROM text_recognition_model", Long.class))
        .isEqualTo(1L);
  }

  private void deleteRows() {
    jdbcTemplate.update("DELETE FROM text_recognition_model");
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE event_type = 'TEXT_RECOGNITION_MODEL_CHANGED'");
    endpoints.invalidateAll();
  }

  private RequestPostProcessor devAdmin() {
    return request -> {
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }
}
