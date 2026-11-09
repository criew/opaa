package io.opaa.query.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opaa.instance.InvalidationBus;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.llm.LlmModel;
import io.opaa.llm.LlmModelRepository;
import io.opaa.security.SettingsEncryptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A streamed answer asks the model for its usage ({@code stream_options.include_usage}) and counts
 * it in {@code opaa.model.tokens}, against a local server speaking the OpenAI streaming protocol.
 */
class AnswerStreamingUsageTest {

  private static final String STREAM =
      """
      data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"provider-x",\
      "choices":[{"index":0,"delta":{"role":"assistant","content":"Antwort"},\
      "finish_reason":null}]}

      data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"provider-x",\
      "choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

      data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"provider-x",\
      "choices":[],"usage":{"prompt_tokens":42,"completion_tokens":7,"total_tokens":49}}

      data: [DONE]

      """;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", this::answer);
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void aStreamedAnswerAsksForItsUsageAndCountsIt() {
    AnswerGenerationService service =
        new AnswerGenerationService(
            resolver(), MessageWindowChatMemory.builder().build(), new ActiveAnswerStreams());

    service.streamAnswer(
        "Was kostet der Ausweis?",
        List.of(),
        "conv-stream-usage",
        List.of(),
        false,
        true,
        null,
        new AnswerStreamListener() {
          @Override
          public void started() {}

          @Override
          public void provisional(String text, boolean replace) {}

          @Override
          public void idle() {}
        });

    JsonNode request = JsonMapper.builder().build().readTree(requestBody.get());
    assertThat(request.path("stream").asBoolean()).isTrue();
    assertThat(request.path("stream_options").path("include_usage").asBoolean()).isTrue();
    assertThat(tokens("input")).isEqualTo(42);
    assertThat(tokens("output")).isEqualTo(7);
  }

  private double tokens(String tokenType) {
    return meterRegistry
        .get("opaa.model.tokens")
        .tags("purpose", "answer", "token_type", tokenType, "model", "model-s")
        .counter()
        .count();
  }

  private void answer(HttpExchange exchange) throws IOException {
    try (InputStream in = exchange.getRequestBody()) {
      requestBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    byte[] bytes = STREAM.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private ActiveChatModelResolver resolver() {
    LlmModelRepository repository = mock(LlmModelRepository.class);
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    when(repository.findAllByActiveTrue())
        .thenReturn(
            List.of(new LlmModel("Modell S", baseUrl, "model-s", BigDecimal.ZERO, 256, null)));
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("meterRegistry", meterRegistry);
    return new ActiveChatModelResolver(
        repository,
        mock(SettingsEncryptor.class),
        new StaticListableBeanFactory().getBeanProvider(ObservationRegistry.class),
        beans.getBeanProvider(MeterRegistry.class),
        mock(InvalidationBus.class));
  }
}
