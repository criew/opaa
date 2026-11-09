package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opaa.eval.ToolCallingJudge.Outcome;
import io.opaa.eval.ToolCallingRunner.Transcript;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The measurement's real client ({@link ToolCallingChatModel}) against a fake OpenAI-compatible
 * endpoint: the mock tool reaches the wire with its two parameters, the model's tool call comes
 * back parsed, and the harness's answer goes out as a tool message - plain and streamed. Stands in
 * for a run against a real model wherever no endpoint is available.
 */
class ToolCallingWireTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String MODEL = "fake-tool-model";
  private static final String ARGUMENTS = "{\"query\":\"Hundesteuer zweiter Hund\"}";

  private HttpServer server;
  private final List<JsonNode> requests = new ArrayList<>();
  private final Queue<String> responses = new ConcurrentLinkedQueue<>();
  private volatile String contentType = "application/json";

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", this::handle);
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void aToolCallTravelsTheWireAndTheHarnessAnswersIt() throws IOException {
    responses.add(completion(toolCallMessage(), "tool_calls"));
    responses.add(completion("{\"role\":\"assistant\",\"content\":\"144 Euro im Jahr.\"}", "stop"));

    Transcript t = runner(false).run(caseVa01(), model());

    assertThat(ToolCallingJudge.judge(caseVa01(), t).outcome()).isEqualTo(Outcome.PASSED);
    assertThat(t.finalAnswer()).isEqualTo("144 Euro im Jahr.");
    assertToolOfferedAndAnswered();
  }

  @Test
  void aStreamedToolCallInPiecesArrivesWhole() throws IOException {
    contentType = "text/event-stream";
    responses.add(
        sse(
            chunk(
                "{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"search_knowledge\",\"arguments\":\"\"}}]}",
                null),
            chunk(
                "{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"query\\\":\\\"Hundesteuer \"}}]}",
                null),
            chunk(
                "{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"zweiter Hund\\\"}\"}}]}",
                null),
            chunk("{}", "tool_calls")));
    responses.add(
        sse(
            chunk("{\"role\":\"assistant\",\"content\":\"144 Euro \"}", null),
            chunk("{\"content\":\"im Jahr.\"}", null),
            chunk("{}", "stop")));

    Transcript t = runner(true).run(caseVa01(), model());

    assertThat(t.error()).isNull();
    assertThat(t.toolCalls()).hasSize(1);
    assertThat(JSON.readTree(t.toolCalls().getFirst().arguments()).get("query").asString())
        .isEqualTo("Hundesteuer zweiter Hund");
    assertThat(t.finalAnswer()).isEqualTo("144 Euro im Jahr.");
    assertThat(ToolCallingJudge.judge(caseVa01(), t).outcome()).isEqualTo(Outcome.PASSED);
    assertToolOfferedAndAnswered();
  }

  private void assertToolOfferedAndAnswered() {
    assertThat(requests).hasSize(2);
    JsonNode first = requests.getFirst();
    assertThat(first.get("model").asString()).isEqualTo(MODEL);
    assertThat(first.get("messages").get(0).get("role").asString()).isEqualTo("system");
    JsonNode function = first.get("tools").get(0).get("function");
    assertThat(function.get("name").asString()).isEqualTo("search_knowledge");
    assertThat(function.get("parameters").get("properties").propertyNames())
        .containsExactlyInAnyOrder("query", "max_results");
    JsonNode messages = requests.getLast().get("messages");
    JsonNode toolMessage = messages.get(messages.size() - 1);
    assertThat(toolMessage.get("role").asString()).isEqualTo("tool");
    assertThat(toolMessage.get("content").toString()).contains("144 Euro");
  }

  private void handle(HttpExchange exchange) throws IOException {
    requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
    String body = responses.poll();
    byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(body == null ? 500 : 200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private ChatModel model() {
    return ToolCallingChatModel.openAiCompatible(
        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
        MODEL,
        null,
        java.math.BigDecimal.ZERO,
        256,
        Duration.ofSeconds(10));
  }

  private static ToolCallingRunner runner(boolean streaming) throws IOException {
    return new ToolCallingRunner(
        ToolCallingMockTool.load(ToolCallingMockTool.corpusDir()),
        streaming,
        Duration.ofSeconds(10));
  }

  private static ToolCallingCase caseVa01() throws IOException {
    return ToolCallingDatasetTest.cases().stream()
        .filter(c -> c.id().equals("va-01"))
        .findFirst()
        .orElseThrow();
  }

  private static String toolCallMessage() {
    return "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":"
        + "\"function\",\"function\":{\"name\":\"search_knowledge\",\"arguments\":"
        + JSON.writeValueAsString(ARGUMENTS)
        + "}}]}";
  }

  private static String completion(String message, String finishReason) {
    return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\""
        + MODEL
        + "\",\"choices\":[{\"index\":0,\"message\":"
        + message
        + ",\"finish_reason\":\""
        + finishReason
        + "\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
  }

  private static String chunk(String delta, String finishReason) {
    return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\""
        + MODEL
        + "\",\"choices\":[{\"index\":0,\"delta\":"
        + delta
        + ",\"finish_reason\":"
        + (finishReason == null ? "null" : "\"" + finishReason + "\"")
        + "}]}";
  }

  private static String sse(String... chunks) {
    StringBuilder sb = new StringBuilder();
    for (String chunk : chunks) {
      sb.append("data: ").append(chunk).append("\n\n");
    }
    return sb.append("data: [DONE]\n\n").toString();
  }
}
