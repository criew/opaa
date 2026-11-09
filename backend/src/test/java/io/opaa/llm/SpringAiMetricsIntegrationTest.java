package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.instance.PostgresInvalidationBus;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.LlmModelCatalogFixtures;
import io.opaa.test.OpaaIntegrationTest;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The {@code gen_ai.*} metrics Spring AI derives from the observations of the chat and embedding
 * clients OPAA builds itself ({@link ActiveChatModelResolver}, {@link
 * OpenAiEmbeddingClientFactory}): operation duration with SLO buckets and token usage, each with
 * tags from a small fixed set only - no prompt, answer, person or library. Also asserts that every
 * content property of Spring AI's observations is off in the running application.
 */
@OpaaIntegrationTest
class SpringAiMetricsIntegrationTest {

  /** Tag keys a {@code gen_ai.*} meter may carry: Spring AI's low-cardinality keys and ours. */
  private static final Set<String> ALLOWED_GEN_AI_TAGS =
      Set.of(
          "gen_ai.operation.name",
          "gen_ai.system",
          "gen_ai.provider.name",
          "gen_ai.request.model",
          "gen_ai.response.model",
          "gen_ai.token.type",
          "error",
          "exception",
          "application",
          "host");

  @Autowired private MockMvc mockMvc;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private Environment environment;
  @Autowired private ActiveChatModelResolver resolver;
  @Autowired private OpenAiEmbeddingClientFactory embeddingClients;
  @Autowired private LlmModelService llmModelService;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private LlmModelCatalogFixtures llmModelCatalogFixtures;
  @Autowired private PostgresInvalidationBus invalidationBus;

  private List<Map<String, Object>> foreignModels;
  private HttpServer server;
  private String baseUrl;
  private UUID organizationId;
  private UUID userId;

  @BeforeEach
  void setUp() throws IOException {
    foreignModels = llmModelCatalogFixtures.takeOverCatalog();
    settle();
    resolver.resetForTest();
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Metrik Org")).getId();
    User user = new User(UUID.randomUUID().toString(), "test-issuer", "metrik@example.com", "M");
    user.setOrganizationId(organizationId);
    userId = userRepository.save(user).getId();
    server = startModelServer();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    llmModelCatalogFixtures.restoreCatalog(foreignModels);
    resolver.resetForTest();
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    userRepository.deleteById(userId);
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void chatAndEmbeddingCallsShowUpAsGenAiMetricsWithBoundedTags() throws Exception {
    LlmModel model =
        llmModelService.createModel(
            organizationId,
            userId,
            "Metrikmodell",
            baseUrl,
            "metrics-chat",
            new BigDecimal("0.70"),
            2000,
            null);
    llmModelService.activateModel(organizationId, userId, model.getId());
    settle();

    resolver.resolveChatClient().prompt().user("Wie lange dauert ein Antrag?").call().content();
    embeddingClients
        .create(new EmbeddingEndpoint(baseUrl, "metrics-embed", null, 512))
        .embed("Ein Abschnitt aus einem Dokument");

    String scrape =
        mockMvc
            .perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(seriesOf(scrape, "gen_ai_client_operation_seconds_count"))
        .anyMatch(line -> line.contains("gen_ai_operation_name=\"chat\""))
        .anyMatch(line -> line.contains("gen_ai_operation_name=\"embedding\""));
    assertThat(seriesOf(scrape, "gen_ai_client_operation_seconds_bucket"))
        .anyMatch(line -> line.contains("gen_ai_operation_name=\"chat\""));
    assertThat(seriesOf(scrape, "gen_ai_client_token_usage_total"))
        .anyMatch(
            line ->
                line.contains("gen_ai_token_type=\"input\"")
                    && line.contains("gen_ai_request_model=\"metrics-chat\""))
        .anyMatch(line -> line.contains("gen_ai_token_type=\"output\""));
    assertThat(scrape).doesNotContain("Wie lange dauert", "Ein Abschnitt aus einem Dokument");

    List<Meter> genAiMeters =
        meterRegistry.getMeters().stream()
            .filter(meter -> meter.getId().getName().startsWith("gen_ai."))
            .toList();
    assertThat(genAiMeters).isNotEmpty();
    for (Meter meter : genAiMeters) {
      Set<String> keys =
          meter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet());
      assertThat(keys).as(meter.getId().getName()).isSubsetOf(ALLOWED_GEN_AI_TAGS);
    }
  }

  @Test
  void everyContentPropertyOfSpringAiIsOff() {
    for (String property : SpringAiContentPropertiesTest.KEYS) {
      assertThat(environment.getProperty(property, Boolean.class)).as(property).isFalse();
    }
  }

  private static List<String> seriesOf(String scrape, String name) {
    return scrape.lines().filter(line -> line.startsWith(name + "{")).toList();
  }

  private void settle() {
    assertThat(invalidationBus.awaitDelivery(Duration.ofSeconds(10))).isTrue();
  }

  /** A chat and an embedding endpoint answering with fixed usage numbers. */
  private static HttpServer startModelServer() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange ->
            answer(
                exchange,
                """
                {"id": "chatcmpl-metrics", "object": "chat.completion", "created": 1700000000,
                 "model": "metrics-chat",
                 "choices": [{"index": 0, "finish_reason": "stop",
                   "message": {"role": "assistant", "content": "Vier Wochen."}}],
                 "usage": {"prompt_tokens": 7, "completion_tokens": 3, "total_tokens": 10}}
                """));
    server.createContext(
        "/v1/embeddings",
        exchange ->
            answer(
                exchange,
                """
                {"object": "list", "model": "metrics-embed",
                 "data": [{"object": "embedding", "index": 0, "embedding": [0.1, 0.2, 0.3]}],
                 "usage": {"prompt_tokens": 6, "total_tokens": 6}}
                """));
    server.start();
    return server;
  }

  private static void answer(com.sun.net.httpserver.HttpExchange exchange, String json)
      throws IOException {
    exchange.getRequestBody().readAllBytes();
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
