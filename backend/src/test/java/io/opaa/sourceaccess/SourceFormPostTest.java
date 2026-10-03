package io.opaa.sourceaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.opaa.security.TargetAddressValidator;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The POST way to a token endpoint: form encoding, target validation before anything is sent, a
 * redirect returned instead of followed, and the bound on the answer.
 */
class SourceFormPostTest {

  private HttpServer server;
  private final List<String> requests = new CopyOnWriteArrayList<>();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          requests.add(
              exchange.getRequestMethod()
                  + " "
                  + exchange.getRequestHeaders().getFirst("Content-Type")
                  + " "
                  + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body = "{\"access_token\":\"t\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/moved",
        exchange -> {
          requests.add("moved");
          exchange.getResponseHeaders().add("Location", base() + "/token");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/large",
        exchange -> {
          byte[] body = new byte[4096];
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void theFormIsPostedEncodedAndTheAnswerRead() throws Exception {
    SourceFormPost.Response response =
        post("/token", Map.of("grant_type", "a:b", "assertion", "x y&z"), 1024);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.bodyText()).contains("access_token");
    assertThat(requests)
        .singleElement()
        .asString()
        .startsWith("POST application/x-www-form-urlencoded ")
        .contains("grant_type=a%3Ab")
        .contains("assertion=x+y%26z");
  }

  @Test
  void aRedirectIsReturnedAndNeverFollowed() throws Exception {
    SourceFormPost.Response response = post("/moved", Map.of("assertion", "geheim"), 1024);

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.isSuccess()).isFalse();
    assertThat(requests).containsExactly("moved");
  }

  @Test
  void aBlockedTargetIsNeverContacted() {
    assertThatThrownBy(
            () ->
                SourceFormPost.post(
                    SourceHttpClientFactory.buildHttpClient(null, -1, false),
                    URI.create(base() + "/token"),
                    Map.of("assertion", "geheim"),
                    Duration.ofSeconds(5),
                    1024,
                    new TargetAddressValidator(true, List.of())))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class);
    assertThat(requests).isEmpty();
  }

  @Test
  void anAnswerOverTheBoundIsRefused() {
    assertThatThrownBy(() -> post("/large", Map.of(), 1024))
        .isInstanceOf(BoundedStreams.LimitExceededException.class);
  }

  private SourceFormPost.Response post(String path, Map<String, String> form, long maxBytes)
      throws Exception {
    return SourceFormPost.post(
        SourceHttpClientFactory.buildHttpClient(null, -1, false),
        URI.create(base() + path),
        form,
        Duration.ofSeconds(5),
        maxBytes,
        TargetAddressValidator.disabled());
  }

  private String base() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }
}
