package io.opaa.llm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import io.opaa.TestcontainersConfiguration;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

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
 * message {@code TransientAiException} already gets.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
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

  private RequestPostProcessor devUser(String subject) {
    return request -> {
      if (subject != null) {
        request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      }
      return request;
    };
  }
}
