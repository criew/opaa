package io.opaa.llm;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p><b>The access key never appears anywhere but the {@code Authorization} header</b> - not in an
 * exception message, not in a log line, not truncated. Redirects are never followed, so the header
 * cannot be replayed to an address this class never validated.
 */
@Component
public class RerankClient {

  /** Short enough that an unreachable endpoint degrades a query rather than stalling it. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  private final ObjectMapper objectMapper;

  /**
   * Which spelling the configured endpoint speaks, learned from the first successful call. One
   * installation has one rerank role (ADR-0021, single instance), so one remembered value is
   * enough; a changed endpoint costs one further negotiation, not a wrong request forever.
   */
  private final AtomicReference<Dialect> dialect = new AtomicReference<>();

  public RerankClient(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
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

  /** Signals that the endpoint could not be used for this call, with a German, safe message. */
  public static class RerankUnavailableException extends RuntimeException {
    public RerankUnavailableException(String message) {
      super(message);
    }

    public RerankUnavailableException(String message, Throwable cause) {
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

    try (HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()) {
      HttpResponse<String> response =
          httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 400 || response.statusCode() == 422) {
        throw new SchemaMismatchException(describeStatus(response.statusCode()));
      }
      if (response.statusCode() != 200) {
        throw new RerankUnavailableException(describeStatus(response.statusCode()));
      }
      return parse(response.body(), documents.size());
    } catch (IOException e) {
      throw new RerankUnavailableException(describeConnectionError(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RerankUnavailableException("Die Verbindung wurde unterbrochen.", e);
    }
  }

  /**
   * Probes the endpoint with a single, minimal document - the same request shape a real call uses,
   * because an endpoint that answers a health check but rejects a rerank request is not usable.
   *
   * @return the failure message, or {@code null} when the endpoint answered.
   */
  public String probeFailureMessage(RerankProperties properties) {
    try {
      rerank(properties, "Ping", List.of("Ping"));
      return null;
    } catch (RerankUnavailableException e) {
      return e.getMessage();
    }
  }

  private static URI rerankUri(String baseUrl) {
    URI uri;
    try {
      uri = ModelEndpointUri.append(baseUrl, "/rerank");
    } catch (IllegalArgumentException | URISyntaxException e) {
      throw new RerankUnavailableException("Die Basis-Adresse ist keine gültige URL.", e);
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new RerankUnavailableException(
          "Die Basis-Adresse muss mit http:// oder https:// beginnen.");
    }
    return uri;
  }

  private String requestBody(
      String model, String query, List<String> documents, Dialect dialect) {
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
      throw new IllegalStateException("Die Rerank-Anfrage konnte nicht aufgebaut werden", e);
    }
  }

  /**
   * Reads the ranking out of the response. Accepts both spellings the same family of servers uses -
   * {@code results} (Cohere/Jina/vLLM) and a bare top-level array (Text Embeddings Inference) - and
   * both score field names ({@code relevance_score}, {@code score}). An entry whose index is
   * outside the request's range is dropped rather than trusted: it would silently promote a chunk
   * the query never asked about.
   */
  private List<ScoredCandidate> parse(String responseBody, int documentCount) {
    JsonNode root;
    try {
      root = objectMapper.readTree(responseBody);
    } catch (JacksonException e) {
      throw new RerankUnavailableException(
          "Die Antwort des Rerank-Endpunkts ist kein gültiges JSON.", e);
    }
    JsonNode entries = root.isArray() ? root : root.path("results");
    if (!entries.isArray()) {
      throw new RerankUnavailableException(
          "Die Antwort des Rerank-Endpunkts enthält keine Trefferliste.");
    }
    List<ScoredCandidate> scored = new ArrayList<>(entries.size());
    for (JsonNode entry : entries) {
      JsonNode indexNode = entry.path("index");
      JsonNode scoreNode =
          entry.has("relevance_score") ? entry.path("relevance_score") : entry.path("score");
      if (!indexNode.isNumber() || !scoreNode.isNumber()) {
        throw new RerankUnavailableException(
            "Die Antwort des Rerank-Endpunkts enthält einen Eintrag ohne Index oder Bewertung.");
      }
      int index = indexNode.intValue();
      if (index < 0 || index >= documentCount) {
        continue;
      }
      scored.add(new ScoredCandidate(index, scoreNode.doubleValue()));
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
      case 401 ->
          "Die Authentifizierung am Rerank-Endpunkt ist fehlgeschlagen (HTTP 401). Prüfen Sie den"
              + " Zugangsschlüssel.";
      case 403 -> "Der Rerank-Endpunkt hat den Zugriff verweigert (HTTP 403).";
      case 404 ->
          "Der Rerank-Endpunkt kennt weder die Adresse noch die Modell-Kennung (HTTP 404). Prüfen"
              + " Sie Basis-Adresse und Modell-Kennung.";
      case 400, 422 ->
          "Der Rerank-Endpunkt hat die Anfrage abgelehnt (HTTP "
              + status
              + "). Prüfen Sie Modell-Kennung und Endpunkt.";
      default -> "Der Rerank-Endpunkt antwortete mit HTTP " + status + ".";
    };
  }

  private static String describeConnectionError(IOException e) {
    if (e instanceof UnknownHostException) {
      return "Der Host des Rerank-Endpunkts konnte nicht gefunden werden (DNS-Auflösung"
          + " fehlgeschlagen).";
    }
    if (e instanceof ConnectException) {
      return "Die Verbindung zum Rerank-Endpunkt wurde abgelehnt.";
    }
    if (e instanceof HttpTimeoutException || e instanceof SocketTimeoutException) {
      return "Der Rerank-Endpunkt hat das Zeitlimit überschritten.";
    }
    if (e instanceof SSLException) {
      return "Das Zertifikat des Rerank-Endpunkts konnte nicht geprüft werden.";
    }
    return "Der Rerank-Endpunkt ist nicht erreichbar.";
  }
}
