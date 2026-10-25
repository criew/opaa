package io.opaa.llm;

import io.opaa.compliance.DataFlow;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link EmbeddingProbe} as one {@code POST {baseUrl}/embeddings} over the OpenAI-compatible
 * protocol: {@link ModelEndpointAccess#newHttpClient()} follows no redirect, so a probe never
 * reaches an address the target check did not see. The whole exchange - connection, headers and
 * body - ends after {@value #TIMEOUT_SECONDS} seconds: a server that answers slowly is cut off by
 * closing the client. The request is not repeated and reads at most {@value #MAX_RESPONSE_BYTES}
 * bytes of the answer. The log names status and failure class only, never a body or the key.
 */
@DataFlow("embedding-model")
@Component
class HttpEmbeddingProbe implements EmbeddingProbe {

  static final String PROBE_TEXT = "OPAA Probeeinbettung";
  static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
  static final long TIMEOUT_SECONDS = 10;

  private static final Logger log = LoggerFactory.getLogger(HttpEmbeddingProbe.class);

  private final ObjectMapper objectMapper;

  HttpEmbeddingProbe(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public EmbeddingModelService.ProbeOutcome probe(EmbeddingEndpoint endpoint) {
    URI uri;
    try {
      uri = ModelEndpointUri.append(endpoint.baseUrl(), "/embeddings");
    } catch (IllegalArgumentException | URISyntaxException e) {
      return failure("Die Basis-Adresse ist keine gültige URL.");
    }
    if (!ModelEndpointAccess.isHttp(uri)) {
      return failure("Die Basis-Adresse muss mit http:// oder https:// beginnen.");
    }
    String body =
        objectMapper.writeValueAsString(
            Map.of("model", endpoint.modelIdentifier(), "input", List.of(PROBE_TEXT)));
    HttpRequest request;
    try {
      request =
          ModelEndpointAccess.authorized(
                  HttpRequest.newBuilder(uri)
                      .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                      .header("Content-Type", "application/json")
                      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)),
                  endpoint.apiKey())
              .build();
    } catch (IllegalArgumentException e) {
      return failure("Der Zugangsschlüssel enthält unzulässige Zeichen.");
    }
    HttpClient client = ModelEndpointAccess.newHttpClient();
    ExecutorService exchange = Executors.newVirtualThreadPerTaskExecutor();
    try {
      Future<EmbeddingModelService.ProbeOutcome> outcome =
          exchange.submit(() -> exchange(client, request, endpoint));
      return outcome.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      log.warn(
          "Probe embedding at {} took longer than {} s",
          ModelEndpointUri.withoutSecrets(endpoint.baseUrl()),
          TIMEOUT_SECONDS);
      return failure("Die Verbindung ist in ein Zeitlimit gelaufen.");
    } catch (ExecutionException e) {
      log.warn(
          "Probe embedding at {} failed: {}",
          ModelEndpointUri.withoutSecrets(endpoint.baseUrl()),
          e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
      return failure("Die Adresse ist nicht erreichbar.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return failure("Die Verbindung wurde unterbrochen.");
    } finally {
      // closing the client ends a body still being read; the exchange thread then gives up
      client.shutdownNow();
      exchange.shutdownNow();
    }
  }

  private EmbeddingModelService.ProbeOutcome exchange(
      HttpClient client, HttpRequest request, EmbeddingEndpoint endpoint)
      throws InterruptedException {
    try {
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream in = response.body()) {
        if (response.statusCode() != 200) {
          log.warn(
              "Probe embedding at {} answered HTTP {}",
              ModelEndpointUri.withoutSecrets(endpoint.baseUrl()),
              response.statusCode());
          return failure(statusMessage(response.statusCode()));
        }
        byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
          return failure("Die Antwort der Probeeinbettung ist unerwartet groß.");
        }
        return interpret(bytes);
      }
    } catch (IOException e) {
      log.warn(
          "Probe embedding at {} failed: {}",
          ModelEndpointUri.withoutSecrets(endpoint.baseUrl()),
          e.getClass().getSimpleName());
      return failure(ModelEndpointAccess.translateConnectionError(e));
    }
  }

  private EmbeddingModelService.ProbeOutcome interpret(byte[] bytes) {
    JsonNode vector;
    try {
      vector = objectMapper.readTree(bytes).path("data").path(0).path("embedding");
    } catch (JacksonException e) {
      return failure("Die Antwort der Probeeinbettung ist kein gültiges JSON.");
    }
    if (!vector.isArray() || vector.isEmpty()) {
      return failure("Das Modell hat bei der Probeeinbettung keinen Vektor geliefert.");
    }
    return new EmbeddingModelService.ProbeOutcome(true, null, vector.size());
  }

  private static String statusMessage(int status) {
    return switch (status) {
      case 401 ->
          "Die Authentifizierung ist fehlgeschlagen (HTTP 401). Prüfen Sie den Zugangsschlüssel.";
      case 403 -> "Der Zugriff wurde vom Server verweigert (HTTP 403).";
      case 404 ->
          "Endpunkt oder Modell wurde nicht gefunden (HTTP 404). Prüfen Sie Basis-Adresse und"
              + " Modell-Kennung.";
      default -> ModelEndpointAccess.unexpectedStatus(status);
    };
  }

  private static EmbeddingModelService.ProbeOutcome failure(String message) {
    return new EmbeddingModelService.ProbeOutcome(false, message, null);
  }
}
