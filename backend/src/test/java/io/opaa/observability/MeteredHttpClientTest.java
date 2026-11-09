package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.test.LoopbackTls;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Every {@link OutboundTarget} measured through {@link MeteredHttpClient} against real local
 * servers: success, {@code 401}, a certificate the JVM does not trust, {@code 5xx}, a timeout and
 * {@code 429} each land in {@code opaa.outbound.requests} with their {@code outcome}, and the
 * series carries exactly {@code target}, {@code kind}, {@code outcome} and {@code path_class} - no
 * host, no address (ADR-0071, Entscheidung 2).
 */
class MeteredHttpClientTest {

  private static HttpServer server;
  private static HttpsServer tlsServer;
  private static final CountDownLatch RELEASE = new CountDownLatch(1);

  @BeforeAll
  static void startServers() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    status(server, "/ok", 200);
    status(server, "/unauthorized", 401);
    status(server, "/forbidden", 403);
    status(server, "/not-found", 404);
    status(server, "/server-error", 500);
    status(server, "/too-many", 429);
    server.createContext(
        "/slow-down",
        exchange -> {
          exchange.getResponseHeaders().add("Retry-After", "1");
          exchange.sendResponseHeaders(503, -1);
          exchange.close();
        });
    server.createContext(
        "/hang",
        exchange -> {
          try {
            RELEASE.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    server.start();
    tlsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    tlsServer.setHttpsConfigurator(new HttpsConfigurator(LoopbackTls.serverContext()));
    status(tlsServer, "/ok", 200);
    tlsServer.start();
  }

  @AfterAll
  static void stopServers() {
    RELEASE.countDown();
    server.stop(0);
    tlsServer.stop(0);
  }

  private static void status(HttpServer target, String path, int status) {
    target.createContext(
        path,
        exchange -> {
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
  }

  static Stream<Arguments> targetsAndAnswers() {
    List<Object[]> answers =
        List.of(
            new Object[] {"/ok", OutboundOutcome.SUCCESS},
            new Object[] {"/unauthorized", OutboundOutcome.AUTH_ERROR},
            new Object[] {"/forbidden", OutboundOutcome.AUTH_ERROR},
            new Object[] {"/not-found", OutboundOutcome.CLIENT_ERROR},
            new Object[] {"/server-error", OutboundOutcome.SERVER_ERROR},
            new Object[] {"/too-many", OutboundOutcome.THROTTLED},
            new Object[] {"/slow-down", OutboundOutcome.THROTTLED});
    return Arrays.stream(OutboundTarget.values())
        .flatMap(
            target -> answers.stream().map(answer -> Arguments.of(target, answer[0], answer[1])));
  }

  @ParameterizedTest(name = "{0} {1} -> {2}")
  @MethodSource("targetsAndAnswers")
  void anAnswerLandsInItsOutcome(OutboundTarget target, String path, OutboundOutcome expected)
      throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (HttpClient client = client(registry, target)) {
      client.send(request("http://127.0.0.1:" + port(server) + path), discarding());
    }

    assertCounted(registry, target, expected);
    double throttled =
        registry.find(OutboundMetrics.THROTTLED).counters().stream()
            .mapToDouble(counter -> counter.count())
            .sum();
    assertThat(throttled).isEqualTo(expected == OutboundOutcome.THROTTLED ? 1 : 0);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("targets")
  void aCertificateTheJvmDoesNotTrustIsATlsError(OutboundTarget target) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (HttpClient client = client(registry, target)) {
      Throwable failure =
          catchThrowable(
              () ->
                  client.send(
                      request("https://127.0.0.1:" + port(tlsServer) + "/ok"), discarding()));
      assertThat(failure).isInstanceOf(IOException.class);
    }

    assertCounted(registry, target, OutboundOutcome.TLS_ERROR);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("targets")
  void anAnswerThatDoesNotComeInTimeIsATimeout(OutboundTarget target) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (HttpClient client = client(registry, target)) {
      HttpRequest slow =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port(server) + "/hang"))
              .timeout(Duration.ofMillis(200))
              .build();
      assertThat(catchThrowable(() -> client.send(slow, discarding())))
          .isInstanceOf(IOException.class);
    }

    assertCounted(registry, target, OutboundOutcome.TIMEOUT);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("targets")
  void aRefusedConnectionIsAnIoError(OutboundTarget target) throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (HttpClient client = client(registry, target)) {
      assertThat(
              catchThrowable(
                  () -> client.send(request("http://127.0.0.1:" + closedPort + "/"), discarding())))
          .isInstanceOf(IOException.class);
    }

    assertCounted(registry, target, OutboundOutcome.IO_ERROR);
  }

  @Test
  void anAsynchronousRequestIsMeasuredToo() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (HttpClient client = client(registry, OutboundTarget.OIDC)) {
      client
          .sendAsync(request("http://127.0.0.1:" + port(server) + "/server-error"), discarding())
          .get(10, TimeUnit.SECONDS);
    }

    assertCounted(registry, OutboundTarget.OIDC, OutboundOutcome.SERVER_ERROR);
  }

  @Test
  void aCallOnARequestThreadIsInteractive() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try (HttpClient client = client(registry, OutboundTarget.OCR)) {
      client.send(request("http://127.0.0.1:" + port(server) + "/ok"), discarding());
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }

    assertThat(
            registry
                .get(OutboundMetrics.REQUESTS)
                .tag("target", "ocr")
                .tag("path_class", "interactive")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void aConnectorOrDirectoryNamesItsKindAndNoOtherTargetDoes() {
    assertThat(
            catchThrowable(
                () -> metrics(new SimpleMeterRegistry(), OutboundTarget.CONNECTOR, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(catchThrowable(() -> metrics(new SimpleMeterRegistry(), OutboundTarget.RERANK, "x")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            catchThrowable(
                () ->
                    metrics(
                        new SimpleMeterRegistry(),
                        OutboundTarget.CONNECTOR,
                        "https://wiki.stadt.example")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Stream<OutboundTarget> targets() {
    return Arrays.stream(OutboundTarget.values());
  }

  private static HttpClient client(SimpleMeterRegistry registry, OutboundTarget target) {
    return MeteredHttpClient.of(
        HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(5))
            .build(),
        metrics(registry, target, target.hasKind() ? "test" : null));
  }

  private static OutboundMetrics metrics(
      SimpleMeterRegistry registry, OutboundTarget target, String kind) {
    return new OutboundMetrics(registry, target, kind);
  }

  private static void assertCounted(
      SimpleMeterRegistry registry, OutboundTarget target, OutboundOutcome expected) {
    List<Meter.Id> ids =
        registry.find(OutboundMetrics.REQUESTS).timers().stream().map(Meter::getId).toList();
    assertThat(ids).hasSize(1);
    Meter.Id id = ids.getFirst();
    Set<String> keys = id.getTags().stream().map(Tag::getKey).collect(Collectors.toSet());
    assertThat(keys).containsExactlyInAnyOrder("target", "kind", "outcome", "path_class");
    assertThat(id.getTag("target")).isEqualTo(target.tagValue());
    assertThat(id.getTag("kind")).isEqualTo(target.hasKind() ? "test" : OutboundMetrics.NO_KIND);
    assertThat(id.getTag("outcome")).isEqualTo(expected.tagValue());
    assertThat(id.getTag("path_class")).isEqualTo("background");
    assertThat(registry.get(OutboundMetrics.REQUESTS).timer().count()).isEqualTo(1);
  }

  private static HttpRequest request(String url) {
    return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).build();
  }

  private static HttpResponse.BodyHandler<Void> discarding() {
    return HttpResponse.BodyHandlers.discarding();
  }

  private static int port(HttpServer target) {
    return target.getAddress().getPort();
  }
}
