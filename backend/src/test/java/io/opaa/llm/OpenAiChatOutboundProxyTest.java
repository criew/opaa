package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.opaa.test.OutboundProxyRoute;
import io.opaa.test.RecordingHttpProxy;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * The chat client of a managed model (Spring AI on OkHttp, built like {@code
 * ActiveChatModelResolver} builds it) has no proxy of its own and takes the outbound proxy from the
 * JVM's default selector. {@code models.invalid} exists only behind the proxy.
 */
class OpenAiChatOutboundProxyTest {

  @Test
  void aChatRequestGoesThroughTheOutboundProxy() throws Exception {
    HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] body =
              """
              {"id": "chatcmpl-test", "object": "chat.completion", "created": 1700000000,
               "model": "model-p",
               "choices": [{"index": 0, "message": {"role": "assistant", "content": "Über den Proxy"},
                            "finish_reason": "stop"}],
               "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}
              """
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    upstream.start();
    int port = upstream.getAddress().getPort();
    try (RecordingHttpProxy proxy =
            new RecordingHttpProxy().route("models.invalid", upstream.getAddress());
        OutboundProxyRoute route = OutboundProxyRoute.through(proxy.address())) {
      OpenAiChatModel chatModel =
          OpenAiChatModel.builder()
              .options(
                  OpenAiChatOptions.builder()
                      .baseUrl("http://models.invalid:" + port + "/v1")
                      .apiKey("test-key")
                      .model("model-p")
                      .build())
              .build();

      String answer = chatModel.call("Hallo");

      assertThat(answer).isEqualTo("Über den Proxy");
      assertThat(proxy.requests())
          .containsExactly("POST http://models.invalid:" + port + "/v1/chat/completions");
    } finally {
      upstream.stop(0);
    }
  }
}
