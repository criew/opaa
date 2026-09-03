package io.opaa.llm;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Talks to a rerank endpoint over the one connection path this project supports for model roles
 * (docs/features/llm-integration.md#ein-anbindungsweg-nicht-zwei): {@code POST {baseUrl}/rerank},
 * the endpoint vLLM, Text Embeddings Inference, Infinity, Jina, Cohere and Voyage all serve. There
 * is no provider switch, exactly as for chat and embedding.
 *
 * <p><b>Two request spellings, negotiated once.</b> The same endpoint is served with two field
 * namings: {@code {model, query, documents}} (Cohere, Jina, vLLM, Infinity) and {@code {query,
 * texts}} (Text Embeddings Inference, the reference server for the CPU rerankers this project
 * targets). Sending both namings in one body would break every server that rejects unknown fields,
 * so the client tries the first, falls back to the second on a schema rejection, and remembers
 * which one the configured endpoint speaks - one wasted request per process, not per query. The
 * response is read from either shape either way.
 *
 * <p><b>The response is read bounded, in every dimension.</b> A rerank endpoint may be a cloud
 * provider - a trust boundary, not an own service - and this call sits on the request path of a
 * user's question, where an unbounded read would tie up a request thread and the heap ({@code
 * OutOfMemoryError} is an {@link Error}, which no {@code catch (Exception)} on that path would
 * catch). Capped are the bytes read from the body ({@link #MAX_RESPONSE_BYTES}), the number of
 * ranking entries (never more than candidates were sent), the indices they may name, and the
 * wall-clock time spent reading it: {@link HttpRequest.Builder#timeout(Duration)} only bounds the
 * wait for response headers, so a body that stalls after headers arrive is read on a separate
 * thread with its own deadline, carved out of whatever remained of {@link
 * RerankProperties#timeout()} after the headers came in. Every breach is a {@link
 * RerankUnavailableException} - the same fallback as an unreachable endpoint, so the query degrades
 * to the order it already had instead of failing.
 *
 * <p><b>The access key never appears anywhere but the {@code Authorization} header</b> - not in an
 * exception message, not in a log line, not truncated. Redirects are never followed, so the header
 * cannot be replayed to an address this class never validated. Every failure message is technical
 * English: it travels into {@link RerankRoleStatus#diagnostic()}, not onto a screen.
 */
@Component
public class RerankClient {

  /** Short enough that an unreachable endpoint degrades a query rather than stalling it. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /**
   * The most this client reads from a rerank response. A well-behaved answer is two numbers per
   * candidate and {@code opaa.query.rerank-candidate-count} is capped at 200, so a few kilobytes;
   * the headroom covers an endpoint that echoes the submitted texts back despite being asked not
   * to. Anything beyond is not a large ranking, it is a broken or hostile endpoint.
   */
  static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

  private final ObjectMapper objectMapper;

  /**
   * Which spelling the configured endpoint speaks, learned from the first successful call. One
   * installation has one rerank role (ADR-0021, single instance), so one remembered value is
   * enough; a changed endpoint costs one further negotiation, not a wrong request forever.
   */
  private final AtomicReference<Dialect> dialect = new AtomicReference<>();

  /**
   * One client for the whole application, not one per call: reranking is on the path of every query
   * of an installation that switched it on, and a fresh TCP (and TLS) handshake per query is both
   * wasted latency and, against a busy endpoint, a connect timeout waiting to happen. Redirects are
   * never followed, so the {@code Authorization} header cannot be replayed to an address this class
   * never validated.
   */
  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .connectTimeout(CONNECT_TIMEOUT)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  /**
   * Runs the body read of one call so it can be bounded by a deadline: {@link
   * HttpRequest.Builder#timeout(Duration)} only covers the wait for response headers, so a body
   * that stalls afterward would otherwise block the calling query thread forever. Virtual threads,
   * not a fixed pool - a stalled read is unblocked by closing its stream (see {@link
   * #readBounded(InputStream, Duration)}), so no thread accumulates even under a permanently
   * stalling endpoint, and no pool size needs to trade query concurrency against that case.
   */
  private final ExecutorService bodyReadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  public RerankClient(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @PreDestroy
  void closeHttpClient() {
    httpClient.close();
    bodyReadExecutor.close();
  }

  /**
   * One candidate's score as the reranker returned it.
   *
   * @param index position of the candidate in the request's {@code documents} list.
   * @param score the reranker's relevance score. Comparable only within one response - never with a
   *     similarity or a fusion score (the confusion #912 was rooted in).
   */
  public record ScoredCandidate(int index, double score) {}

  /** The two field namings of the same endpoint - see this class's Javadoc. */
  enum Dialect {
    /** {@code {model, query, documents, top_n}} - Cohere, Jina, vLLM, Infinity. */
    COHERE,
    /** {@code {query, texts}} - Text Embeddings Inference. */
    TEXTS;

    Dialect other() {
      return this == COHERE ? TEXTS : COHERE;
    }
  }

  /**
   * Signals that the endpoint could not be used for this call. The message is technical and English
   * - it ends up in {@link RerankRoleStatus#diagnostic()}, whose German wording is the presenting
   * layer's business - and it never contains the access key.
   */
  public static class RerankUnavailableException extends RuntimeException {
    public RerankUnavailableException(String message) {
      super(message);
    }

    public RerankUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * The request ran into {@link RerankProperties#timeout()} before the endpoint answered - distinct
   * from every other {@link RerankUnavailableException}, whose causes (refused connection, unknown
   * host, TLS failure) mean the endpoint could not be reached at all. A timeout means the opposite:
   * something answered the connection, it simply did not finish within budget (#1154) - a CPU
   * reranker under its normal candidate load is the expected case, not a failure of the same kind.
   */
  public static class RerankTimeoutException extends RerankUnavailableException {
    public RerankTimeoutException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Scores {@code documents} against {@code query}, best first.
   *
   * @return one entry per document the endpoint scored, sorted by descending score. An endpoint may
   *     return fewer entries than it was given; callers must not assume completeness.
   * @throws RerankUnavailableException when the endpoint is unreachable, refuses the request, or
   *     answers something this method cannot read as a ranking.
   */
  public List<ScoredCandidate> rerank(
      RerankProperties properties, String query, List<String> documents) {
    if (documents.isEmpty()) {
      return List.of();
    }
    Dialect known = dialect.get();
    Dialect first = known == null ? Dialect.COHERE : known;
    try {
      List<ScoredCandidate> scored = send(properties, query, documents, first);
      dialect.set(first);
      return scored;
    } catch (SchemaMismatchException e) {
      Dialect fallback = first.other();
      List<ScoredCandidate> scored = send(properties, query, documents, fallback);
      dialect.set(fallback);
      return scored;
    }
  }

  private List<ScoredCandidate> send(
      RerankProperties properties, String query, List<String> documents, Dialect dialect) {
    URI uri = rerankUri(properties.baseUrl());
    String body = requestBody(properties.model(), query, documents, dialect);
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri)
            .timeout(properties.timeout())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (StringUtils.hasText(properties.apiKey())) {
      request.header("Authorization", "Bearer " + properties.apiKey());
    }

    // nanoTime, not Instant/currentTimeMillis: a wall-clock jump (e.g. an NTP correction) must not
    // distort the budget carved out for the body read below.
    long callStartNanos = System.nanoTime();
    try {
      HttpResponse<InputStream> response =
          httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream responseBody = response.body()) {
        if (response.statusCode() == 400 || response.statusCode() == 422) {
          throw new SchemaMismatchException(describeStatus(response.statusCode()));
        }
        if (response.statusCode() != 200) {
          throw new RerankUnavailableException(describeStatus(response.statusCode()));
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - callStartNanos);
        Duration remaining = properties.timeout().minus(elapsed);
        return parse(readBounded(responseBody, remaining), documents.size());
      }
    } catch (IOException e) {
      String message = describeConnectionError(e);
      if (isRequestTimeout(e)) {
        throw new RerankTimeoutException(message, e);
      }
      throw new RerankUnavailableException(message, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RerankUnavailableException("the call was interrupted", e);
    }
  }

  /**
   * A probe's outcome, carrying whether the failure was a timeout rather than a genuinely
   * unreachable endpoint - see {@link RerankTimeoutException}.
   */
  public record ProbeFailure(String message, boolean timedOut) {}

  /**
   * Probes the endpoint with a single, minimal document - the same request shape a real call uses,
   * because an endpoint that answers a health check but rejects a rerank request is not usable.
   *
   * @return the failure, or {@code null} when the endpoint answered.
   */
  public ProbeFailure probe(RerankProperties properties) {
    try {
      rerank(properties, "Ping", List.of("Ping"));
      return null;
    } catch (RerankTimeoutException e) {
      return new ProbeFailure(e.getMessage(), true);
    } catch (RerankUnavailableException e) {
      return new ProbeFailure(e.getMessage(), false);
    }
  }

  /**
   * Reads the body within {@code remaining} - what is left of {@link RerankProperties#timeout()}
   * after headers arrived - on {@link #bodyReadExecutor}, so the calling thread never blocks past
   * the deadline even though a JDK {@link InputStream} read has no timeout parameter of its own.
   * {@code remaining} may already be spent (the header wait alone can exhaust the whole budget);
   * that is a timeout too, not a zero-length read.
   *
   * <p>A breach closes {@code body} to unblock the read: for {@link
   * HttpResponse.BodyHandlers#ofInputStream()}, closing before the body is fully consumed drops the
   * underlying connection rather than returning it to the pool, so the endpoint sees the read end
   * and the reading thread is freed by the resulting {@link IOException} instead of sitting on the
   * executor forever.
   */
  private String readBounded(InputStream body, Duration remaining) throws IOException {
    if (remaining.isNegative() || remaining.isZero()) {
      closeQuietly(body);
      throw new SocketTimeoutException("no time left to read the response body");
    }
    Future<String> read = bodyReadExecutor.submit(() -> readBoundedBytes(body));
    try {
      return read.get(remaining.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      read.cancel(true);
      closeQuietly(body);
      throw new SocketTimeoutException("timed out reading the response body");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException io) {
        throw io;
      }
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IOException("failed reading the response body", cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      closeQuietly(body);
      throw new InterruptedIOException("interrupted while reading the response body");
    }
  }

  private static void closeQuietly(InputStream body) {
    try {
      body.close();
    } catch (IOException ignored) {
      // The read is being abandoned anyway; a failure to close carries no further information.
    }
  }

  /**
   * Reads at most {@link #MAX_RESPONSE_BYTES} of the body and refuses anything longer, rather than
   * materializing whatever the endpoint decides to send. The stream is closed by the caller's
   * try-with-resources, which also discards the remainder of an over-long response unread.
   */
  private static String readBoundedBytes(InputStream body) throws IOException {
    byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
    if (bytes.length > MAX_RESPONSE_BYTES) {
      throw new RerankUnavailableException(
          "endpoint response exceeds the " + MAX_RESPONSE_BYTES + " byte limit this client reads");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static URI rerankUri(String baseUrl) {
    URI uri;
    try {
      uri = ModelEndpointUri.append(baseUrl, "/rerank");
    } catch (IllegalArgumentException | URISyntaxException e) {
      throw new RerankUnavailableException("base URL is not a valid URI", e);
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new RerankUnavailableException("base URL must start with http:// or https://");
    }
    return uri;
  }

  private String requestBody(String model, String query, List<String> documents, Dialect dialect) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", query);
    if (dialect == Dialect.COHERE) {
      body.put("model", model);
      body.put("documents", documents);
      // Every candidate must come back scored: the stage decides the cut, not the endpoint.
      body.put("top_n", documents.size());
    } else {
      body.put("texts", documents);
      body.put("return_text", false);
    }
    try {
      return objectMapper.writeValueAsString(body);
    } catch (JacksonException e) {
      throw new IllegalStateException("failed to build the rerank request body", e);
    }
  }

  /**
   * Reads the ranking out of the response. Accepts both spellings the same family of servers uses -
   * {@code results} (Cohere/Jina/vLLM) and a bare top-level array (Text Embeddings Inference) - and
   * both score field names ({@code relevance_score}, {@code score}).
   *
   * <p><b>The response may name only what the request contained.</b> More entries than candidates
   * were sent is refused outright, an entry whose index lies outside the request's range is dropped
   * - it would otherwise silently promote a chunk the query never asked about - and a response in
   * which no entry named a candidate that was sent is refused as well, because accepting it would
   * pass an empty ranking off as a successful call. A repeated index is harmless: the caller takes
   * the first occurrence and ignores the rest.
   */
  private List<ScoredCandidate> parse(String responseBody, int documentCount) {
    JsonNode root;
    try {
      root = objectMapper.readTree(responseBody);
    } catch (JacksonException e) {
      throw new RerankUnavailableException("endpoint response is not valid JSON", e);
    }
    JsonNode entries = root.isArray() ? root : root.path("results");
    if (!entries.isArray()) {
      throw new RerankUnavailableException("endpoint response contains no result list");
    }
    if (entries.size() > documentCount) {
      throw new RerankUnavailableException(
          "endpoint response contains "
              + entries.size()
              + " ranking entries for "
              + documentCount
              + " submitted candidate(s)");
    }
    List<ScoredCandidate> scored = new ArrayList<>(entries.size());
    for (JsonNode entry : entries) {
      JsonNode indexNode = entry.path("index");
      JsonNode scoreNode =
          entry.has("relevance_score") ? entry.path("relevance_score") : entry.path("score");
      if (!indexNode.isNumber() || !scoreNode.isNumber()) {
        throw new RerankUnavailableException(
            "endpoint response contains an entry without an index or a score");
      }
      int index = indexNode.intValue();
      if (index < 0 || index >= documentCount) {
        continue;
      }
      scored.add(new ScoredCandidate(index, scoreNode.doubleValue()));
    }
    if (scored.isEmpty() && !entries.isEmpty()) {
      throw new RerankUnavailableException(
          "endpoint response names no candidate that was submitted; every index was out of range");
    }
    scored.sort((left, right) -> Double.compare(right.score(), left.score()));
    return List.copyOf(scored);
  }

  /** A rejection of the request's field naming, not of the request itself - see {@link Dialect}. */
  private static class SchemaMismatchException extends RerankUnavailableException {
    SchemaMismatchException(String message) {
      super(message);
    }
  }

  private static String describeStatus(int status) {
    return switch (status) {
      case 401 -> "HTTP 401: authentication failed, check the access key";
      case 403 -> "HTTP 403: the endpoint refused access";
      case 404 -> "HTTP 404: unknown path or model identifier";
      case 400, 422 -> "HTTP " + status + ": the endpoint rejected the request";
      default -> "HTTP " + status + " from the rerank endpoint";
    };
  }

  private static String describeConnectionError(IOException e) {
    if (e instanceof UnknownHostException) {
      return "host not found (DNS resolution failed)";
    }
    if (e instanceof ConnectException) {
      return "connection refused";
    }
    if (e instanceof HttpConnectTimeoutException) {
      return "connection attempt timed out (endpoint did not accept the connection)";
    }
    if (isRequestTimeout(e)) {
      return "request timed out";
    }
    if (e instanceof SSLException) {
      return "TLS handshake failed";
    }
    if (e instanceof InterruptedIOException) {
      // Checked after isRequestTimeout: SocketTimeoutException is itself an
      // InterruptedIOException subclass and must keep its own, more specific message above.
      return "request interrupted";
    }
    return "endpoint not reachable";
  }

  /**
   * Whether {@code e} is the request running past {@link RerankProperties#timeout()} - as opposed
   * to {@link HttpConnectTimeoutException}, a JDK subclass of {@link HttpTimeoutException} that
   * fires when nothing ever accepted the TCP connection within {@link #CONNECT_TIMEOUT}. The two
   * need to stay apart: a connect timeout means the endpoint could not be reached at all (a dead
   * host, a firewall drop), the same as a refused connection, while a request timeout means it was
   * reached and simply did not finish scoring in time (#1154's CPU case). Classifying the former as
   * the latter would send an administrator to raise a timeout that was never the problem.
   */
  static boolean isRequestTimeout(IOException e) {
    return (e instanceof HttpTimeoutException && !(e instanceof HttpConnectTimeoutException))
        || e instanceof SocketTimeoutException;
  }
}
