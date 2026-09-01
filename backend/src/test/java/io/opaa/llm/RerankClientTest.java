package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.opaa.llm.RerankClient.RerankUnavailableException;
import io.opaa.llm.RerankClient.ScoredCandidate;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
          capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
  void aRefusedConnectionIsReportedAsUnavailable() {
    // Port 1 on loopback: nothing listens there, and no DNS lookup is involved.
    RerankProperties unreachable =
        new RerankProperties(true, "http://127.0.0.1:1/v1", "m", "", Duration.ofSeconds(2));

    assertThat(client.probeFailureMessage(unreachable)).isNotBlank();
  }

  @Test
  void aReachableEndpointProbesWithoutAFailureMessage() {
    respond("[{\"index\":0,\"score\":1.0}]", new AtomicReference<>());

    assertThat(client.probeFailureMessage(properties(""))).isNull();
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
          byte[] response =
              "[{\"index\":0,\"score\":0.5}]".getBytes(StandardCharsets.UTF_8);
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

  @Test
  void aBaseUrlWithoutASchemeIsRejected() {
    RerankProperties noScheme =
        new RerankProperties(true, "localhost:8080", "m", "", Duration.ofSeconds(2));

    assertThatThrownBy(() -> client.rerank(noScheme, "Frage", List.of("a")))
        .isInstanceOf(RerankUnavailableException.class)
        .hasMessageContaining("http://");
  }
}
