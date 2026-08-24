package io.opaa.llm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.test.OpaaMockMvcTest;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * #768: end-to-end reproduction, same building block as {@code
 * ActiveChatModelResolverIntegrationTest} (a local {@link HttpServer} standing in for the managed
 * chat model, started and then immediately stopped so its port refuses the connection the SDK
 * actually attempts) - but driven through the real HTTP entry point ({@code POST /api/v1/query})
 * rather than calling {@code ActiveChatModelResolver} directly. {@code
 * ActiveChatModelResolverIntegrationTest#anUnreachableActiveModelFailsWithoutFallingBackToAnotherModel}
 * already proved the SDK throws {@code com.openai.errors.OpenAIIoException} for this and documented
 * the rest - that it fell through to {@code GlobalExceptionHandler}'s generic {@code 500} - as an
 * open follow-up rather than fixing it there; this class proves that follow-up: the same failure
 * now reaches {@code GlobalExceptionHandler} and turns into a {@code 503} with the same German
 * message {@code TransientAiException} already gets. {@link
 * #providerUnauthorizedResponseReturnsBadGatewayWithGermanMessage} covers the other branch of the
 * mapping - a provider that actually answers, just not successfully.
 */
@OpaaMockMvcTest
class QueryControllerLlmErrorMappingIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LlmModelService llmModelService;
  @Autowired private ActiveChatModelResolver resolver;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private User devAdmin;
  private HttpServer unreachableServer;

  @BeforeEach
  void setUp() throws Exception {
    jdbcTemplate.update("DELETE FROM llm_models");
    resolver.resetForTest();

    // Provisions "dev-admin" (opaa.auth.initial-admin-email, application.yml) via the real
    // UserProvisioningFilter - same technique as LibraryIndexingAuthorizationIntegrationTest.
    mockMvc.perform(get("/api/v1/me").with(devUser(null)));
    devAdmin =
        userRepository.findAll().stream()
            .filter(u -> "admin@opaa.local".equals(u.getEmail()))
            .findFirst()
            .orElseThrow();

    // Started and stopped before use, exactly like ActiveChatModelResolverIntegrationTest's own
    // serverA.stop(0) - the port is real (bound once) but nothing is listening on it anymore, so
    // the SDK's HTTP client gets an actual connection-refused, not a resolvable-but-idle address.
    unreachableServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    unreachableServer.start();
    String baseUrl = "http://127.0.0.1:" + unreachableServer.getAddress().getPort() + "/v1";
    unreachableServer.stop(0);

    LlmModel model =
        llmModelService.createModel(
            devAdmin.getOrganizationId(),
            devAdmin.getId(),
            "Nicht erreichbares Modell",
            baseUrl,
            "model-unreachable",
            new BigDecimal("0.70"),
            2000,
            null);
    llmModelService.activateModel(devAdmin.getOrganizationId(), devAdmin.getId(), model.getId());
  }

  @AfterEach
  void tearDown() {
    resolver.resetForTest();
    jdbcTemplate.update("DELETE FROM llm_models");
  }

  @Test
  void unreachableActiveModelReturnsServiceUnavailableWithGermanMessage() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/query")
                .with(devUser(null))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"Hallo\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("KI-Dienst vorübergehend nicht verfügbar"))
        .andExpect(jsonPath("$.status").value(503));
  }

  /**
   * #768 review, should-finding 3: the acceptance criterion "beide Fälle im HttpServer-Muster"
   * requires the other branch of the mapping, too - not just the connection-level {@code
   * OpenAIIoException} above. A real HTTP 401 response proves {@code OpenAiChatModel} actually
   * surfaces it as {@code com.openai.errors.UnauthorizedException} at {@link
   * GlobalExceptionHandler} rather than the SDK wrapping or swallowing it somewhere on the way.
   */
  @Test
  void providerUnauthorizedResponseReturnsBadGatewayWithGermanMessage() throws Exception {
    HttpServer unauthorizedServer = startUnauthorizedChatCompletionsServer();
    try {
      String baseUrl = "http://127.0.0.1:" + unauthorizedServer.getAddress().getPort() + "/v1";
      LlmModel model =
          llmModelService.createModel(
              devAdmin.getOrganizationId(),
              devAdmin.getId(),
              "Modell mit ungültigem Zugangsschlüssel",
              baseUrl,
              "model-unauthorized",
              new BigDecimal("0.70"),
              2000,
              null);
      llmModelService.activateModel(devAdmin.getOrganizationId(), devAdmin.getId(), model.getId());

      mockMvc
          .perform(
              post("/api/v1/query")
                  .with(devUser(null))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"question\": \"Hallo\"}"))
          .andExpect(status().isBadGateway())
          .andExpect(jsonPath("$.error").value("Fehler im KI-Dienst"))
          .andExpect(jsonPath("$.status").value(502));
    } finally {
      unauthorizedServer.stop(0);
    }
  }

  private HttpServer startUnauthorizedChatCompletionsServer() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          String json =
              """
              {"error": {"message": "Invalid API key", "type": "invalid_request_error"}}
              """;
          byte[] body = json.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(401, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    return server;
  }

  private RequestPostProcessor devUser(String subject) {
    return request -> {
      if (subject != null) {
        request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      }
      return request;
    };
  }
}
