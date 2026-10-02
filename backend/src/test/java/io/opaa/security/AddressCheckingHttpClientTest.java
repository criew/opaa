package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link AddressCheckingHttpClient} against a local server: the connect-time check refuses a
 * rebinding name and lets an allowed one through, and the {@code java.net.http} contract its
 * callers rely on holds - bodies in both directions, headers, timeouts, status codes, no redirects.
 */
class AddressCheckingHttpClientTest {

  private static final byte[] LARGE_BODY = new byte[200_000];

  static {
    Arrays.fill(LARGE_BODY, (byte) 'x');
  }

  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicReference<String> receivedBody = new AtomicReference<>();
  private HttpServer server;
  private int port;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.createContext("/text", exchange -> respond(exchange, 200, "hallo".getBytes()));
    server.createContext("/large", exchange -> respond(exchange, 200, LARGE_BODY));
    server.createContext(
        "/echo",
        exchange -> {
          receivedBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          respond(exchange, 201, new byte[0]);
        });
    server.createContext(
        "/redirect",
        exchange -> {
          requests.incrementAndGet();
          exchange.getResponseHeaders().add("Location", "/text");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/slow",
        exchange -> {
          requests.incrementAndGet();
          try {
            Thread.sleep(2_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          respond(exchange, 200, new byte[0]);
        });
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
    requests.incrementAndGet();
    exchange.getResponseHeaders().add("X-Opaa-Test", "ja");
    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  private static HttpClient client(TargetAddressValidator validator) {
    return AddressCheckingHttpClient.newBuilder(ConnectionAddressResolver.of(validator))
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  private static HttpClient allowingLocalhost() {
    return client(new TargetAddressValidator(true, List.of("localhost")));
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  @Test
  void aNameThatRebindsAfterTheCheckIsRefusedAtConnectTime() throws Exception {
    RebindingHostLookup lookup = new RebindingHostLookup("localhost");
    TargetAddressValidator validator = new TargetAddressValidator(true, List.of(), lookup);
    validator.validate(uri("/text"));

    assertThatThrownBy(
            () ->
                client(validator)
                    .send(
                        HttpRequest.newBuilder(uri("/text")).build(),
                        HttpResponse.BodyHandlers.ofString()))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class)
        .hasMessageContaining("localhost")
        .hasMessageContaining("gesperrten Adressbereich");
    assertThat(requests).hasValue(0);
    assertThat(lookup.lookups()).isEqualTo(2);
  }

  @Test
  void aHostThatDoesNotResolveIsAnUnknownHostNotARefusal() {
    TargetAddressValidator validator =
        new TargetAddressValidator(
            true,
            List.of(),
            host -> {
              throw new UnknownHostException(host);
            });

    assertThatThrownBy(
            () ->
                client(validator)
                    .send(
                        HttpRequest.newBuilder(uri("/text")).build(),
                        HttpResponse.BodyHandlers.ofString()))
        .isInstanceOf(UnknownHostException.class)
        .isNotInstanceOf(CheckedDnsResolver.RejectedAddressException.class);
  }

  @Test
  void anAllowedTargetAnswersWithStatusHeadersAndBody() throws Exception {
    HttpResponse<String> response =
        allowingLocalhost()
            .send(
                HttpRequest.newBuilder(uri("/text")).build(), HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("hallo");
    assertThat(response.headers().firstValue("x-opaa-test")).contains("ja");
    assertThat(response.uri()).isEqualTo(uri("/text"));
  }

  @Test
  void aLargeBodyStreamsCompletelyThroughAnInputStream() throws Exception {
    HttpResponse<InputStream> response =
        allowingLocalhost()
            .send(
                HttpRequest.newBuilder(uri("/large")).build(),
                HttpResponse.BodyHandlers.ofInputStream());

    try (InputStream body = response.body()) {
      assertThat(body.readAllBytes()).isEqualTo(LARGE_BODY);
    }
  }

  @Test
  void aRequestBodyArrives() throws Exception {
    HttpResponse<String> response =
        allowingLocalhost()
            .send(
                HttpRequest.newBuilder(uri("/echo"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(receivedBody).hasValue("grant_type=client_credentials");
  }

  @Test
  void aRedirectIsReturnedNotFollowed() throws Exception {
    HttpResponse<String> response =
        allowingLocalhost()
            .send(
                HttpRequest.newBuilder(uri("/redirect")).build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location")).contains("/text");
    assertThat(requests).hasValue(1);
  }

  @Test
  void theRequestTimeoutEndsAWaitForTheResponse() {
    assertThatThrownBy(
            () ->
                allowingLocalhost()
                    .send(
                        HttpRequest.newBuilder(uri("/slow"))
                            .timeout(Duration.ofMillis(300))
                            .build(),
                        HttpResponse.BodyHandlers.ofString()))
        .isInstanceOf(HttpTimeoutException.class);
  }

  @Test
  void sendAsyncDeliversTheSameResponse() throws Exception {
    HttpResponse<String> response =
        allowingLocalhost()
            .sendAsync(
                HttpRequest.newBuilder(uri("/text")).build(), HttpResponse.BodyHandlers.ofString())
            .get();

    assertThat(response.body()).isEqualTo("hallo");
  }
}
