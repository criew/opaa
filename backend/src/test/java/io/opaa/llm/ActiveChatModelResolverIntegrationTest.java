package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.errors.OpenAIIoException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    // #767 review, optional finding 4: without this, a cached client from this test method would
    // keep pointing at serverA/serverB above, both now stopped - the next test method sharing this
    // class's Spring context would see "connection refused" instead of the 503 its own fixtures
    // expect, until it happened to trigger an activation of its own.
    resolver.resetForTest();
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

  /**
   * #767 review, should-finding 1: every other test in this class uses {@code apiKey = null}, so
   * the decrypt-and-send path was entirely untested - a resolver that silently dropped the key
   * (e.g. forgot to decrypt, or built {@link OpenAiChatOptions} without it) would have passed every
   * other test here.
   */
  @Test
  void anApiKeyIsDecryptedAndSentAsTheAuthorizationHeader() throws IOException {
    AtomicReference<String> receivedAuthHeader = new AtomicReference<>();
    AtomicBoolean serverWithAuthTouched = new AtomicBoolean(false);
    HttpServer serverWithAuth =
        startChatCompletionsServer(
            "model-c", "Antwort von Modell C", serverWithAuthTouched, receivedAuthHeader);
    try {
      String baseUrlC = "http://127.0.0.1:" + serverWithAuth.getAddress().getPort() + "/v1";
      LlmModel modelC =
          llmModelService.createModel(
              organizationId,
              userId,
              "Modell C",
              baseUrlC,
              "model-c",
              new BigDecimal("0.70"),
              2000,
              "sk-test-secret");
      llmModelService.activateModel(organizationId, userId, modelC.getId());

      ChatClient chatClient = resolver.resolveChatClient();
      ChatResponse response = chatClient.prompt().user("Hallo").call().chatResponse();

      assertThat(receivedAuthHeader.get()).isEqualTo("Bearer sk-test-secret");
      assertThat(response.getResult().getOutput().getText()).isEqualTo("Antwort von Modell C");
    } finally {
      serverWithAuth.stop(0);
    }
  }

  /**
   * #767 review, should-finding 2: an unreachable active model must fail outright, never silently
   * fall back to another configured model - {@code serverB} above is never contacted.
   *
   * <p><b>Not {@code org.springframework.ai.retry.TransientAiException}.</b> The review assumed the
   * failure would land in {@code io.opaa.api.GlobalExceptionHandler}'s existing {@code
   * TransientAiException}/{@code NonTransientAiException} handling (503/502) - verified here to
   * name what the SDK-based {@code OpenAiChatModel} (Spring AI 2.0, since #766's move off the
   * legacy {@code RestClient}-based implementation) actually throws for a connection failure:
   * {@code com.openai.errors.OpenAIIoException}, a plain {@code RuntimeException} neither of those
   * two extends. Today that falls through to {@code GlobalExceptionHandler}'s generic {@code
   * Exception} handler (a 500 "Interner Serverfehler") rather than the more specific status a
   * caller gets for, say, an OpenAI 4xx/5xx response - a pre-existing gap from the #766 SDK
   * migration, not something #758 introduces or is positioned to fix (mapping the {@code
   * com.openai.errors.*} hierarchy is a change to shared exception handling, not to how the active
   * model is resolved) - reported as a follow-up rather than fixed in this PR. What matters for
   * #758's own acceptance criterion is proven regardless: the call fails loudly, and never reaches
   * {@code serverB}.
   */
  @Test
  void anUnreachableActiveModelFailsWithoutFallingBackToAnotherModel() {
    LlmModel modelA = createModel("Modell A", baseUrlA, "model-a");
    llmModelService.activateModel(organizationId, userId, modelA.getId());
    ChatClient chatClient = resolver.resolveChatClient();
    serverA.stop(0);

    assertThatThrownBy(() -> chatClient.prompt().user("Hallo").call().chatResponse())
        .isInstanceOf(OpenAIIoException.class);
    assertThat(serverBTouched.get()).isFalse();
  }

  /**
   * #767 review, blocking finding: reproduces the race this class's own Javadoc describes - a build
   * already reading the (about to be superseded) active model must not overwrite the invalidation a
   * concurrent activation triggers while that build is still in flight. {@link
   * ActiveChatModelResolver#setTestRaceWindowHook} pauses the build exactly between reading the
   * active model and committing the result to the cache, the same window the generation counter in
   * {@link ActiveChatModelResolver#buildAndCache} closes.
   */
  @Test
  void aBuildInFlightWhenTheActiveModelChangesDoesNotOverwriteTheInvalidation() throws Exception {
    LlmModel modelA = createModel("Modell A", baseUrlA, "model-a");
    llmModelService.activateModel(organizationId, userId, modelA.getId());
    resolver.resolveChatClient();
    resolver.resetForTest(); // clears the cache/counter only - model A stays active in the database

    CountDownLatch modelRead = new CountDownLatch(1);
    CountDownLatch releaseBuild = new CountDownLatch(1);
    resolver.setTestRaceWindowHook(
        () -> {
          modelRead.countDown();
          try {
            assertThat(releaseBuild.await(5, TimeUnit.SECONDS)).isTrue();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<ChatClient> inFlightBuild = executor.submit(resolver::resolveChatClient);
      assertThat(modelRead.await(5, TimeUnit.SECONDS)).isTrue();

      // Activates while Thread A (above) is paused having already read model A - the scenario
      // this class's Javadoc describes: without the generation counter, A's in-flight build for
      // (now superseded) model A would overwrite the cache this activation just cleared.
      LlmModel modelB = createModel("Modell B", baseUrlB, "model-b");
      llmModelService.activateModel(organizationId, userId, modelB.getId());

      releaseBuild.countDown();
      assertThat(inFlightBuild.get(5, TimeUnit.SECONDS)).isNotNull();
    } finally {
      executor.shutdownNow();
    }

    // The superseded build must have been discarded, not cached - proven by the counter staying
    // at zero rather than by "the next call happens to return model B", which a build that
    // *did* overwrite the cache could also satisfy if it ran a second time.
    assertThat(resolver.builtClientCount()).isEqualTo(0);

    resolver.setTestRaceWindowHook(() -> {});
    ChatClient chatClient = resolver.resolveChatClient();
    ChatResponse response = chatClient.prompt().user("Hallo").call().chatResponse();

    assertThat(resolver.builtClientCount()).isEqualTo(1);
    assertThat(serverBTouched.get()).isTrue();
    assertThat(response.getResult().getOutput().getText()).isEqualTo("Antwort von Modell B");
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
    return startChatCompletionsServer(modelIdentifier, answerText, touched, null);
  }

  private HttpServer startChatCompletionsServer(
      String modelIdentifier,
      String answerText,
      AtomicBoolean touched,
      AtomicReference<String> authHeaderCapture)
      throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          touched.set(true);
          if (authHeaderCapture != null) {
            authHeaderCapture.set(exchange.getRequestHeaders().getFirst("Authorization"));
          }
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
