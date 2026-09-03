package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import io.opaa.llm.RerankClient.RerankUnavailableException;
import io.opaa.llm.RerankClient.ScoredCandidate;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link RerankClient} against a real, local {@link HttpServer} - the same building block {@code
 * LlmModelConnectionTesterTest} uses, so request shape, key handling and the two response dialects
 * are proven against real HTTP traffic rather than a mock.
 */
class RerankClientTest {

  private HttpServer server;
  private String baseUrl;
  private RerankClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    client = new RerankClient(new ObjectMapper());
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private RerankProperties properties(String apiKey) {
    return new RerankProperties(true, baseUrl, "bge-reranker", apiKey, Duration.ofSeconds(5));
  }

  private void respond(String body, AtomicReference<String> capturedBody) {
    respond(body, capturedBody, new AtomicReference<>());
  }

  private void respond(
      String body, AtomicReference<String> capturedBody, AtomicReference<String> capturedAuth) {
    server.createContext(
        "/v1/rerank",
        exchange -> {
          capturedBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] response = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
          }
        });
  }

  @Test
  void sendsModelQueryAndDocumentsAndReadsTheCohereShapedResponse() {
    AtomicReference<String> requestBody = new AtomicReference<>();
    respond(
        "{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}]}",
        requestBody);

    List<ScoredCandidate> scored =
        client.rerank(properties(""), "Gebührensatzung", List.of("erster Text", "zweiter Text"));

    assertThat(scored).containsExactly(new ScoredCandidate(1, 0.9), new ScoredCandidate(0, 0.1));
    assertThat(requestBody.get()).contains("\"model\":\"bge-reranker\"");
    assertThat(requestBody.get()).contains("\"query\":\"Gebührensatzung\"");
    assertThat(requestBody.get()).contains("erster Text").contains("zweiter Text");
  }

  /** Text Embeddings Inference answers with a bare array and a {@code score} field. */
  @Test
  void readsTheBareArrayResponseShapeToo() {
    respond("[{\"index\":0,\"score\":2.5},{\"index\":1,\"score\":-1.0}]", new AtomicReference<>());

    List<ScoredCandidate> scored = client.rerank(properties(""), "Frage", List.of("a", "b"));

    assertThat(scored).containsExactly(new ScoredCandidate(0, 2.5), new ScoredCandidate(1, -1.0));
  }

  /** Descending score, whatever order the endpoint happened to answer in. */
  @Test
  void sortsByDescendingScore() {
    respond("[{\"index\":0,\"score\":0.2},{\"index\":1,\"score\":0.8}]", new AtomicReference<>());

    assertThat(client.rerank(properties(""), "Frage", List.of("a", "b")))
        .extracting(ScoredCandidate::index)
        .containsExactly(1, 0);
  }

  /** An index the request never sent would silently promote a chunk nobody asked about. */
  @Test
  void dropsScoresForIndexesOutsideTheRequestedRange() {
    respond("[{\"index\":0,\"score\":0.2},{\"index\":7,\"score\":0.9}]", new AtomicReference<>());

    assertThat(client.rerank(properties(""), "Frage", List.of("a", "b")))
        .containsExactly(new ScoredCandidate(0, 0.2));
  }

  @Test
  void sendsTheAccessKeyOnlyInTheAuthorizationHeader() {
    AtomicReference<String> requestBody = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();
    respond("[{\"index\":0,\"score\":1.0}]", requestBody, authorization);

    client.rerank(properties("s3cret-key"), "Frage", List.of("a"));

    assertThat(authorization.get()).isEqualTo("Bearer s3cret-key");
    assertThat(requestBody.get()).doesNotContain("s3cret");
  }

  @Test
  void anUnauthenticatedEndpointIsReportedWithoutRevealingTheKey() {
    server.createContext(
        "/v1/rerank",
        exchange -> {
          exchange.sendResponseHeaders(401, -1);
          exchange.close();
        });

    assertThatThrownBy(() -> client.rerank(properties("s3cret-key"), "Frage", List.of("a")))
        .isInstanceOf(RerankUnavailableException.class)
        .hasMessageContaining("HTTP 401")
        .hasMessageNotContaining("s3cret");
  }

  @Test
  void anUnparsableResponseIsReportedAsUnavailable() {
    respond("{\"unexpected\":true}", new AtomicReference<>());

    assertThatThrownBy(() -> client.rerank(properties(""), "Frage", List.of("a")))
        .isInstanceOf(RerankUnavailableException.class)
        .hasMessageContaining("no result list");
  }

  @Test
  void aRefusedConnectionIsReportedAsUnavailableButNotAsATimeout() {
    // Port 1 on loopback: nothing listens there, and no DNS lookup is involved.
    RerankProperties unreachable =
        new RerankProperties(true, "http://127.0.0.1:1/v1", "m", "", Duration.ofSeconds(2));

    RerankClient.ProbeFailure failure = client.probe(unreachable);

    assertThat(failure).isNotNull();
    assertThat(failure.message()).isNotBlank();
    assertThat(failure.timedOut()).isFalse();
  }

  @Test
  void aReachableEndpointProbesWithoutAFailure() {
    respond("[{\"index\":0,\"score\":1.0}]", new AtomicReference<>());

    assertThat(client.probe(properties(""))).isNull();
  }

  /**
   * The finding #1154 exists for: an endpoint that is reachable but too slow for the configured
   * budget must be reported as a timeout, not folded into the same "unreachable" bucket as a
   * refused connection - the two need different remedies (raise the timeout vs. fix the endpoint).
   */
  @Test
  void anEndpointThatAnswersAfterTheTimeoutIsReportedAsATimeoutNotARefusedConnection() {
    server.createContext(
        "/v1/rerank",
        exchange -> {
          try {
            Thread.sleep(500);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] response = "[{\"index\":0,\"score\":1.0}]".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
          }
        });
    RerankProperties slow =
        new RerankProperties(true, baseUrl, "bge-reranker", "", Duration.ofMillis(50));

    assertThatThrownBy(() -> client.rerank(slow, "Frage", List.of("a")))
        .isInstanceOf(RerankClient.RerankTimeoutException.class);

    RerankClient.ProbeFailure failure = client.probe(slow);
    assertThat(failure).isNotNull();
    assertThat(failure.timedOut()).isTrue();
  }

  /**
   * regression guard for #1209: {@code HttpRequest.Builder#timeout(Duration)} only bounds the wait
   * for response headers, not the body read that follows. An endpoint that sends headers and then
   * stalls must still be classified as a timeout, and the whole call - not just the header phase -
   * must return within {@code OPAA_RERANK_TIMEOUT}. {@code assertTimeoutPreemptively} is the test's
   * own safety net: without the fix, the call blocks past it and the assertion itself fails with a
   * timeout instead of the test hanging forever.
   */
  @Test
  void aBodyThatStallsAfterHeadersIsReportedAsATimeoutWithinTheConfiguredBudget() {
    server.createContext(
        "/v1/rerank",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          // Chunked (length 0): headers go out immediately, independent of a Content-Length.
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write("[".getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(5000);
          } catch (IOException e) {
            // The client closed the connection on timeout; that is the expected outcome.
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    RerankProperties stallingBody =
        new RerankProperties(true, baseUrl, "bge-reranker", "", Duration.ofMillis(300));

    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> {
          Instant start = Instant.now();
          assertThatThrownBy(() -> client.rerank(stallingBody, "Frage", List.of("a")))
              .isInstanceOf(RerankClient.RerankTimeoutException.class);
          assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(2));
        });
  }

  @Test
  void anEmptyCandidateListIsNotSentAtAll() {
    assertThat(client.rerank(properties(""), "Frage", List.of())).isEmpty();
  }

  /**
   * Text Embeddings Inference rejects the Cohere spelling with 422 and expects {@code texts}. The
   * client must find that out by itself, and must not pay for it on every later call.
   */
  @Test
  void negotiatesTheTextsSpellingWhenTheEndpointRejectsTheCohereOne() {
    List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    server.createContext(
        "/v1/rerank",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          bodies.add(body);
          if (!body.contains("\"texts\"")) {
            exchange.sendResponseHeaders(422, -1);
            exchange.close();
            return;
          }
          byte[] response = "[{\"index\":0,\"score\":0.5}]".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
          }
        });

    assertThat(client.rerank(properties(""), "Frage", List.of("a")))
        .containsExactly(new ScoredCandidate(0, 0.5));
    assertThat(bodies).hasSize(2);

    // The learned spelling is reused: the second call costs one request, not two.
    assertThat(client.rerank(properties(""), "Frage", List.of("a"))).hasSize(1);
    assertThat(bodies).hasSize(3);
    assertThat(bodies.get(2)).contains("texts");
  }

  /**
   * A rerank endpoint may be a cloud provider - a trust boundary. An answer larger than this client
   * reads must degrade the query, not the process: the call sits on the request path of a user's
   * question, where an unbounded read would tie up a request thread and the heap.
   */
  @Test
  void aResponseBeyondTheReadLimitIsRefusedRatherThanRead() {
    server.createContext(
        "/v1/rerank",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] filler = new byte[64 * 1024];
          Arrays.fill(filler, (byte) 'x');
          // Chunked (length 0), so the client cannot decide on Content-Length alone.
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(
                "[{\"index\":0,\"score\":0.5,\"padding\":\"".getBytes(StandardCharsets.UTF_8));
            for (int written = 0; written < RerankClient.MAX_RESPONSE_BYTES + filler.length; ) {
              out.write(filler);
              written += filler.length;
            }
          } catch (IOException e) {
            // The client cut the read short; that is the expected outcome, not a test failure.
          }
        });

    assertThatThrownBy(() -> client.rerank(properties(""), "Frage", List.of("a")))
        .isInstanceOf(RerankUnavailableException.class);
  }

  @Test
  void moreRankingEntriesThanCandidatesWereSentIsRefused() {
    respond(
        "{\"results\":[{\"index\":0,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.8}]}",
        new AtomicReference<>());

    assertThatThrownBy(() -> client.rerank(properties(""), "Frage", List.of("a")))
        .isInstanceOf(RerankUnavailableException.class)
        .hasMessageContaining("2 ranking entries for 1");
  }

  /** All indices out of range is not an empty ranking, it is an unusable answer. */
  @Test
  void aResponseWhoseEveryIndexIsOutOfRangeIsRefused() {
    respond(
        "{\"results\":[{\"index\":7,\"relevance_score\":0.9},{\"index\":-1,\"relevance_score\":0.8}]}",
        new AtomicReference<>());

    assertThatThrownBy(() -> client.rerank(properties(""), "Frage", List.of("a", "b")))
        .isInstanceOf(RerankUnavailableException.class)
        .hasMessageContaining("out of range");
  }

  /**
   * The finding from the #1204 review: {@link HttpConnectTimeoutException} is a JDK subclass of
   * {@link HttpTimeoutException}, so a naive {@code instanceof HttpTimeoutException} check would
   * misclassify "nothing ever accepted the TCP connection" (a genuinely unreachable endpoint,
   * bounded by the client's fixed connect timeout) as a slow-but-reachable one bounded by {@code
   * opaa.rerank.timeout}. An administrator told to raise the request timeout for a dead host would
   * never find the actual cause.
   */
  @Test
  void aConnectTimeoutIsNotClassifiedAsARequestTimeout() {
    assertThat(RerankClient.isRequestTimeout(new HttpConnectTimeoutException("connect timed out")))
        .isFalse();
  }

  @Test
  void aRequestTimeoutOrASocketReadTimeoutIsClassifiedAsARequestTimeout() {
    assertThat(RerankClient.isRequestTimeout(new HttpTimeoutException("request timed out")))
        .isTrue();
    assertThat(RerankClient.isRequestTimeout(new SocketTimeoutException("read timed out")))
        .isTrue();
  }

  @Test
  void aRefusedConnectionIsNotClassifiedAsARequestTimeout() {
    assertThat(RerankClient.isRequestTimeout(new ConnectException("connection refused"))).isFalse();
  }

  @Test
  void aBaseUrlWithoutASchemeIsRejected() {
    RerankProperties noScheme =
        new RerankProperties(true, "localhost:8080", "m", "", Duration.ofSeconds(2));

    assertThatThrownBy(() -> client.rerank(noScheme, "Frage", List.of("a")))
        .isInstanceOf(RerankUnavailableException.class)
        .hasMessageContaining("http://");
  }
}
