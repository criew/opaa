package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.opaa.TestcontainersConfiguration;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link ActiveChatModelResolver} against a real Postgres (same
 * {@code @SpringBootTest}/{@code @Import}/{@code @ActiveProfiles} signature as {@link
 * LlmModelServiceIntegrationTest} - see that class's own Javadoc for why that signature must not
 * change lightly) and two real, local {@link HttpServer} instances standing in for two managed chat
 * models (#758) - the same building block {@link LlmModelConnectionTesterTest} uses.
 *
 * <p>Proves what {@link LlmModelServiceIntegrationTest} cannot: that {@link LlmModelService} taking
 * effect at the database level actually changes which endpoint {@link
 * ActiveChatModelResolver#resolveChatClient()} talks to - and, crucially, exactly when. {@link
 * #repeatedResolutionWithoutAChangeReusesTheCachedClient()} is the reproduction-proof half of the
 * acceptance criterion "wiederholte Anfragen ohne zwischenzeitliche Änderung bauen den ChatClient
 * nicht neu": it asserts {@link ActiveChatModelResolver#builtClientCount()}, a call counter, not a
 * timing measurement - a resolver that (incorrectly) rebuilt on every call would still be fast
 * enough to pass a time-based assertion, so only the counter actually distinguishes "rebuilt, but
 * cheap" from "never rebuilt at all".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
class ActiveChatModelResolverIntegrationTest {

  @Autowired private ActiveChatModelResolver resolver;
  @Autowired private LlmModelService llmModelService;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private HttpServer serverA;
  private HttpServer serverB;
  private String baseUrlA;
  private String baseUrlB;
  private AtomicBoolean serverATouched;
  private AtomicBoolean serverBTouched;
  private UUID organizationId;
  private UUID userId;

  @BeforeEach
  void setUp() throws IOException {
    jdbcTemplate.update("DELETE FROM llm_models");
    // The DELETE above bypasses LlmModelService, so no ActiveChatModelChangedEvent fires - without
    // this, the resolver would keep serving whatever client an earlier test method (sharing this
    // class's Spring context) built.
    resolver.resetForTest();

    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Resolver Test Org"))
            .getId();
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "resolver@example.com", "Test");
    user.setOrganizationId(organizationId);
    userId = userRepository.save(user).getId();

    serverATouched = new AtomicBoolean(false);
    serverBTouched = new AtomicBoolean(false);
    serverA = startChatCompletionsServer("model-a", "Antwort von Modell A", serverATouched);
    serverB = startChatCompletionsServer("model-b", "Antwort von Modell B", serverBTouched);
    baseUrlA = "http://127.0.0.1:" + serverA.getAddress().getPort() + "/v1";
    baseUrlB = "http://127.0.0.1:" + serverB.getAddress().getPort() + "/v1";
  }

  @AfterEach
  void tearDown() {
    serverA.stop(0);
    serverB.stop(0);
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM llm_models");
    userRepository.deleteById(userId);
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void resolvesTheChatClientOfTheActiveModel() {
    LlmModel modelA = createModel("Modell A", baseUrlA, "model-a");
    llmModelService.activateModel(organizationId, userId, modelA.getId());

    ChatClient chatClient = resolver.resolveChatClient();
    ChatResponse response = chatClient.prompt().user("Hallo").call().chatResponse();

    assertThat(serverATouched.get()).isTrue();
    assertThat(serverBTouched.get()).isFalse();
    assertThat(response.getResult().getOutput().getText()).isEqualTo("Antwort von Modell A");
  }

  @Test
  void repeatedResolutionWithoutAChangeReusesTheCachedClient() {
    LlmModel modelA = createModel("Modell A", baseUrlA, "model-a");
    llmModelService.activateModel(organizationId, userId, modelA.getId());

    resolver.resolveChatClient();
    int afterFirstCall = resolver.builtClientCount();
    resolver.resolveChatClient();
    resolver.resolveChatClient();

    assertThat(afterFirstCall).isEqualTo(1);
    assertThat(resolver.builtClientCount()).isEqualTo(1);
  }

  @Test
  void activatingADifferentModelInvalidatesTheCachedClientOnTheNextResolution() {
    LlmModel modelA = createModel("Modell A", baseUrlA, "model-a");
    llmModelService.activateModel(organizationId, userId, modelA.getId());
    resolver.resolveChatClient();
    assertThat(resolver.builtClientCount()).isEqualTo(1);

    LlmModel modelB = createModel("Modell B", baseUrlB, "model-b");
    llmModelService.activateModel(organizationId, userId, modelB.getId());

    ChatClient chatClient = resolver.resolveChatClient();
    ChatResponse response = chatClient.prompt().user("Hallo").call().chatResponse();

    assertThat(resolver.builtClientCount()).isEqualTo(2);
    assertThat(serverBTouched.get()).isTrue();
    assertThat(response.getResult().getOutput().getText()).isEqualTo("Antwort von Modell B");
  }

  @Test
  void updatingTheActiveModelInvalidatesTheCachedClientOnTheNextResolution() {
    LlmModel modelA = createModel("Modell A", baseUrlA, "model-a");
    llmModelService.activateModel(organizationId, userId, modelA.getId());
    resolver.resolveChatClient();
    assertThat(resolver.builtClientCount()).isEqualTo(1);

    llmModelService.updateModel(
        organizationId,
        userId,
        modelA.getId(),
        "Modell A (umbenannt)",
        baseUrlB,
        "model-b",
        new BigDecimal("0.70"),
        2000,
        null);

    ChatClient chatClient = resolver.resolveChatClient();
    ChatResponse response = chatClient.prompt().user("Hallo").call().chatResponse();

    assertThat(resolver.builtClientCount()).isEqualTo(2);
    assertThat(serverBTouched.get()).isTrue();
    assertThat(response.getResult().getOutput().getText()).isEqualTo("Antwort von Modell B");
  }

  @Test
  void resolvingWithoutAnActiveModelFailsWithAGermanServiceUnavailableMessage() {
    assertThatThrownBy(() -> resolver.resolveChatClient())
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.SERVICE_UNAVAILABLE)
        .hasMessageContaining("kein aktives Chat-Modell");
  }

  @Test
  void resolvingDescriptionWithoutAnActiveModelAlsoFails() {
    assertThatThrownBy(() -> resolver.resolveDescription())
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void resolvedDescriptionNamesTheActiveModelsBaseUrlAndIdentifier() {
    LlmModel modelA = createModel("Modell A", baseUrlA, "model-a");
    llmModelService.activateModel(organizationId, userId, modelA.getId());

    ActiveChatModelDescription description = resolver.resolveDescription();

    assertThat(description.baseUrl()).isEqualTo(baseUrlA);
    assertThat(description.modelIdentifier()).isEqualTo("model-a");
  }

  private LlmModel createModel(String displayName, String baseUrl, String modelIdentifier) {
    return llmModelService.createModel(
        organizationId,
        userId,
        displayName,
        baseUrl,
        modelIdentifier,
        new BigDecimal("0.70"),
        2000,
        null);
  }

  private HttpServer startChatCompletionsServer(
      String modelIdentifier, String answerText, AtomicBoolean touched) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          touched.set(true);
          String json =
              """
              {
                "id": "chatcmpl-test",
                "object": "chat.completion",
                "created": 1700000000,
                "model": "%s",
                "choices": [
                  {
                    "index": 0,
                    "message": {"role": "assistant", "content": "%s"},
                    "finish_reason": "stop"
                  }
                ],
                "usage": {"prompt_tokens": 5, "completion_tokens": 5, "total_tokens": 10}
              }
              """
                  .formatted(modelIdentifier, answerText);
          byte[] body = json.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    return server;
  }
}
