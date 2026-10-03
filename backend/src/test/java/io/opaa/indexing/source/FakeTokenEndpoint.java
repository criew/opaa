package io.opaa.indexing.source;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An OAuth token endpoint on loopback that answers each form POST with the next scripted answer
 * (the last one repeats) and records the forms it received.
 */
public final class FakeTokenEndpoint implements AutoCloseable {

  /** One answer: status and JSON body. */
  public record Answer(int status, String body) {}

  private final HttpServer server;
  private final List<Map<String, String>> forms = new CopyOnWriteArrayList<>();
  private final List<Answer> answers = new CopyOnWriteArrayList<>();

  public FakeTokenEndpoint() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext(
        "/token",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          forms.add(decode(body));
          Answer answer =
              answers.isEmpty()
                  ? token("ya29.test-token", 3600)
                  : answers.size() == 1 ? answers.get(0) : answers.remove(0);
          byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(answer.status(), bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
  }

  public static Answer token(String accessToken, long expiresIn) {
    return new Answer(
        200,
        "{\"access_token\":\""
            + accessToken
            + "\",\"expires_in\":"
            + expiresIn
            + ",\"token_type\":\"Bearer\"}");
  }

  public static Answer error(int status, String error, String description) {
    return new Answer(
        status, "{\"error\":\"" + error + "\",\"error_description\":\"" + description + "\"}");
  }

  /** Scripts the answers to come, in order; the last one repeats. */
  public void answer(Answer... next) {
    answers.clear();
    answers.addAll(List.of(next));
  }

  public URI uri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token");
  }

  public List<Map<String, String>> forms() {
    return forms;
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private static Map<String, String> decode(String body) {
    Map<String, String> form = new LinkedHashMap<>();
    for (String pair : body.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        form.put(
            URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    return form;
  }
}
