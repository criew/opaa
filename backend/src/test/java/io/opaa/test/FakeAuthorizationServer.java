package io.opaa.test;

import com.sun.net.httpserver.HttpExchange;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A token endpoint on {@code 127.0.0.1} for the client credentials and the JWT bearer grant: it
 * records every request (target, {@code Authorization}, form) and answers with a new access token,
 * or with the OAuth error set by {@link #rejectWith}. It also answers a request sent to it as a
 * proxy, whose target then names another host. {@link #shared()} serves the Spring contexts.
 */
public final class FakeAuthorizationServer implements AutoCloseable {

  private static FakeAuthorizationServer shared;

  /** One request as it arrived. */
  public record Request(URI target, String authorization, Map<String, String> form) {}

  private final HttpServer server;
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private final AtomicInteger issued = new AtomicInteger();
  private volatile String error;
  private volatile int errorStatus;

  public FakeAuthorizationServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/token", this::handle);
    server.start();
  }

  /** The server the test connectors of the Spring contexts are declared with; never stopped. */
  public static synchronized FakeAuthorizationServer shared() {
    if (shared == null) {
      shared = new FakeAuthorizationServer();
    }
    return shared;
  }

  public URI tokenEndpoint() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token");
  }

  /** {@code host:port} of this server, to be named as a proxy. */
  public String address() {
    return "127.0.0.1:" + server.getAddress().getPort();
  }

  /** From now on every request is refused with the OAuth {@code error} and {@code status}. */
  public void rejectWith(int status, String error) {
    this.errorStatus = status;
    this.error = error;
  }

  /** From now on every request gets a token again. */
  public void accept() {
    this.error = null;
  }

  public List<Request> requests() {
    return List.copyOf(requests);
  }

  /** The access token the last successful answer carried, {@code null} before any. */
  public String lastToken() {
    int n = issued.get();
    return n == 0 ? null : issuedToken(n);
  }

  private static String issuedToken(int n) {
    return "fake-access-token-" + n;
  }

  /** Forgets the requests and accepts again; tokens keep counting. */
  public void reset() {
    requests.clear();
    error = null;
  }

  private void handle(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    requests.add(
        new Request(
            exchange.getRequestURI(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            form(body)));
    String rejection = error;
    if (rejection != null) {
      answer(exchange, errorStatus, "{\"error\": \"" + rejection + "\"}");
      return;
    }
    answer(
        exchange,
        200,
        "{\"access_token\": \""
            + issuedToken(issued.incrementAndGet())
            + "\", \"token_type\": \"Bearer\", \"expires_in\": 3600}");
  }

  private static Map<String, String> form(String body) {
    Map<String, String> form = new LinkedHashMap<>();
    if (body.isEmpty()) {
      return form;
    }
    for (String pair : body.split("&")) {
      int equals = pair.indexOf('=');
      String key = equals < 0 ? pair : pair.substring(0, equals);
      String value = equals < 0 ? "" : pair.substring(equals + 1);
      form.put(
          URLDecoder.decode(key, StandardCharsets.UTF_8),
          URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
    return form;
  }

  private static void answer(HttpExchange exchange, int status, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    if (this != shared) {
      server.stop(0);
    }
  }
}
