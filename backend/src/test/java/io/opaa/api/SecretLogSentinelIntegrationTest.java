package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import io.opaa.auth.DevAuthFilter;
import io.opaa.test.LogCapture;
import io.opaa.test.OpaaIntegrationTest;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * No stored or presented secret reaches a log line of any logger at any level: the key of a model,
 * the SMTP password, the credentials of a source and the code and state of a connection consent -
 * on the way in, on a failed connection test against a server that refuses or does not speak the
 * protocol, and on a refused request. Bearer values are covered by {@code
 * McpServerIntegrationTest}, passwords and link tokens of the local accounts by {@code
 * LocalAuthLogPrivacyIntegrationTest}.
 */
@OpaaIntegrationTest
class SecretLogSentinelIntegrationTest {

  private static final String MODEL_KEY = "sk-SENTINELmodellschluessel7Qx";
  private static final String STORED_MODEL_KEY = "sk-SENTINELgespeichert3Kp";
  private static final String SMTP_PASSWORD = "SENTINELsmtpPasswort9Lm";
  private static final String SOURCE_PASSWORD = "SENTINELquellenPasswort4Tz";
  private static final String CONFLUENCE_TOKEN = "SENTINELconfluencePat5Rv";
  private static final String CONSENT_CODE = "SENTINELautorisierungscode2Wd";
  private static final String CONSENT_STATE = "SENTINELzustand8Hb";

  @Autowired private MockMvc mockMvc;

  private HttpServer refusingServer;
  private String refusingBase;

  /** Answers every request with 401 and a body that names no secret. */
  @BeforeEach
  void startRefusingServer() throws Exception {
    refusingServer =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    refusingServer.createContext(
        "/",
        exchange -> {
          byte[] body =
              "{\"error\":{\"message\":\"invalid credentials\"}}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(401, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    refusingServer.start();
    refusingBase = "http://127.0.0.1:" + refusingServer.getAddress().getPort();
  }

  @AfterEach
  void stopRefusingServer() {
    refusingServer.stop(0);
  }

  @Test
  void aModelKeyReachesNoLogLine() throws Exception {
    try (LogCapture logs = LogCapture.ofAllLoggers()) {
      mockMvc.perform(
          admin(post("/api/v1/admin/models/test"))
              .content(
                  """
                  {"baseUrl": "%s/v1", "modelIdentifier": "modell", "apiKey": "%s"}
                  """
                      .formatted(refusingBase, MODEL_KEY)));
      mockMvc.perform(
          admin(post("/api/v1/admin/models/test"))
              .content(
                  """
                  {"baseUrl": "https://modelle.example.invalid/v1", "modelIdentifier": "modell",
                   "apiKey": "%s"}
                  """
                      .formatted(MODEL_KEY)));
      mockMvc.perform(
          admin(post("/api/v1/admin/models/test"))
              .content(
                  """
                  {"baseUrl": "%s/v1", "modelIdentifier": "modell", "apiKey": "%s\\nX-Zusatz: 1"}
                  """
                      .formatted(refusingBase, MODEL_KEY)));
      mockMvc
          .perform(
              admin(post("/api/v1/admin/models"))
                  .content(
                      """
                      {"displayName": "", "baseUrl": "https://modelle.example.invalid/v1",
                       "modelIdentifier": "modell", "maxTokens": 0, "apiKey": "%s"}
                      """
                          .formatted(MODEL_KEY)))
          .andExpect(status().isBadRequest());
      String created =
          mockMvc
              .perform(
                  admin(post("/api/v1/admin/models"))
                      .content(
                          """
                          {"displayName": "Wächterprobe", "baseUrl": "%s/v1",
                           "modelIdentifier": "modell", "maxTokens": 100, "apiKey": "%s"}
                          """
                              .formatted(refusingBase, STORED_MODEL_KEY)))
              .andExpect(status().isCreated())
              .andReturn()
              .getResponse()
              .getContentAsString();
      String modelId = JsonPath.read(created, "$.id");
      try {
        mockMvc.perform(
            admin(post("/api/v1/admin/models/test"))
                .content(
                    """
                    {"baseUrl": "%s/v1", "modelIdentifier": "modell", "modelId": "%s"}
                    """
                        .formatted(refusingBase, modelId)));
      } finally {
        mockMvc.perform(admin(delete("/api/v1/admin/models/" + modelId)));
      }

      assertThat(logs.events()).anyMatch(event -> event.getLoggerName().startsWith("io.opaa"));
      assertThat(logs.linesNaming(List.of(MODEL_KEY, STORED_MODEL_KEY))).isEmpty();
    }
  }

  @Test
  void theSmtpPasswordReachesNoLogLine() throws Exception {
    try (LogCapture logs = LogCapture.ofAllLoggers()) {
      mockMvc
          .perform(
              admin(put("/api/v1/system/mail-settings"))
                  .content(
                      """
                      {"enabled": true, "host": "127.0.0.1", "port": %d, "username": "opaa",
                       "password": "%s", "encryption": "NONE",
                       "fromAddress": "opaa@stadt.example"}
                      """
                          .formatted(refusingServer.getAddress().getPort(), SMTP_PASSWORD)))
          .andExpect(status().is2xxSuccessful());
      mockMvc.perform(admin(post("/api/v1/system/mail-settings/test")));
      mockMvc
          .perform(
              admin(put("/api/v1/system/mail-settings"))
                  .content(
                      """
                      {"enabled": true, "host": "127.0.0.1", "port": "keine Zahl",
                       "password": "%s", "encryption": "NONE"}
                      """
                          .formatted(SMTP_PASSWORD)))
          .andExpect(status().isBadRequest());

      assertThat(logs.events()).anyMatch(event -> event.getLoggerName().startsWith("io.opaa"));
      assertThat(logs.linesNaming(List.of(SMTP_PASSWORD))).isEmpty();
    }
  }

  @Test
  void sourceCredentialsReachNoLogLine() throws Exception {
    try (LogCapture logs = LogCapture.ofAllLoggers()) {
      mockMvc.perform(
          admin(post("/api/v1/libraries/source-test"))
              .content(
                  """
                  {"sourceType": "HTTP_DIRECTORY", "sourceUrl": "%s/dokumente/",
                   "sourceCredentials": "nutzer:%s"}
                  """
                      .formatted(refusingBase, SOURCE_PASSWORD)));
      mockMvc.perform(
          admin(post("/api/v1/libraries/source-test"))
              .content(
                  """
                  {"sourceType": "HTTP_DIRECTORY", "sourceUrl": "https://quelle.example.invalid/",
                   "sourceCredentials": "nutzer:%s"}
                  """
                      .formatted(SOURCE_PASSWORD)));
      mockMvc.perform(
          admin(post("/api/v1/libraries/source-test"))
              .content(
                  """
                  {"sourceType": "CONFLUENCE", "sourceUrl": "%s/confluence",
                   "sourceCredentials": "%s\\nX-Zusatz"}
                  """
                      .formatted(refusingBase, CONFLUENCE_TOKEN)));

      assertThat(logs.events()).anyMatch(event -> event.getLoggerName().startsWith("io.opaa"));
      assertThat(
              logs.linesNaming(
                  List.of(SOURCE_PASSWORD, "nutzer:" + SOURCE_PASSWORD, CONFLUENCE_TOKEN)))
          .isEmpty();
    }
  }

  @Test
  void theCodeAndStateOfAConsentReachNoLogLine() throws Exception {
    try (LogCapture logs = LogCapture.ofAllLoggers()) {
      mockMvc
          .perform(
              admin(post("/api/v1/connections/authorizations/complete"))
                  .content(
                      """
                      {"state": "%s", "code": "%s", "iss": "https://anbieter.example.invalid"}
                      """
                          .formatted(CONSENT_STATE, CONSENT_CODE)))
          .andExpect(status().is4xxClientError());

      assertThat(logs.linesNaming(List.of(CONSENT_CODE, CONSENT_STATE))).isEmpty();
    }
  }

  private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, "dev-admin")
        .contentType(MediaType.APPLICATION_JSON);
  }
}
