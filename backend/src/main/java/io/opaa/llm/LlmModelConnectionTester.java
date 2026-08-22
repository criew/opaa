package io.opaa.llm;

import io.opaa.security.SettingsEncryptor;
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
import java.util.UUID;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Probes a managed chat model's OpenAI-compatible endpoint (Stufe 1, #757,
 * docs/features/llm-integration.md#stufe-1-verwaltete-chat-modelle-in-umsetzung: "Verbindungstest
 * je Eintrag. Eine falsch eingetragene Adresse fällt sonst erst dem nächsten fragenden Menschen
 * auf") with a short, bounded chat completion request - the same shape {@code
 * io.opaa.library.SourceConnectionTestService} already uses for library sources: a synchronous
 * probe with a tight timeout, German user-facing outcomes, and a clean separation between
 * "unreachable", "not authenticated" and "model unknown", per the issue's technical hint.
 *
 * <p><b>No target-address blocking here, deliberately</b> - unlike {@code
 * io.opaa.indexing.TargetAddressValidator}, which exists to stop a crawl from walking a public URL
 * onto an internal address it was never told to reach. A managed chat model's baseUrl is entered
 * directly by {@code SYSTEM_ADMIN} and is expected to routinely be an address on the operator's own
 * network - {@code http://ollama:11434/v1} is the specification's own example
 * (docs/features/llm-integration.md#ein-anbindungsweg-nicht-zwei) - so blocking private ranges here
 * would break the primary supported case rather than guard against one.
 */
@Service
public class LlmModelConnectionTester {

  private static final Logger log = LoggerFactory.getLogger(LlmModelConnectionTester.class);

  /** Short enough that a caller testing several drafts in a row is never left waiting long. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final LlmModelRepository repository;
  private final SettingsEncryptor settingsEncryptor;

  public LlmModelConnectionTester(
      LlmModelRepository repository, SettingsEncryptor settingsEncryptor) {
    this.repository = repository;
    this.settingsEncryptor = settingsEncryptor;
  }

  /**
   * Tests {@code baseUrl}/{@code modelIdentifier} with {@code apiKey} as given. When {@code apiKey}
   * is {@code null} and {@code modelId} is set, the stored, decrypted key of that model is used
   * instead - lets a caller re-test an already saved model's connection without having to re-enter
   * a key the API never returns to begin with.
   *
   * @throws ResponseStatusException 404 when {@code modelId} is set but no such model exists
   */
  public TestOutcome test(String baseUrl, String modelIdentifier, String apiKey, UUID modelId) {
    String effectiveApiKey = apiKey;
    if (effectiveApiKey == null && modelId != null) {
      LlmModel model =
          repository
              .findById(modelId)
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.NOT_FOUND,
                          "Kein Chat-Modell mit der ID " + modelId + " gefunden"));
      effectiveApiKey =
          model.getApiKeyCiphertext() == null
              ? null
              : settingsEncryptor.decrypt(model.getApiKeyCiphertext());
    }
    return probe(baseUrl, modelIdentifier, effectiveApiKey);
  }

  private TestOutcome probe(String baseUrl, String modelIdentifier, String apiKey) {
    URI uri;
    try {
      uri = chatCompletionsUri(baseUrl);
    } catch (IllegalArgumentException | URISyntaxException e) {
      return TestOutcome.failure("Die Basis-Adresse ist keine gültige URL.");
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      return TestOutcome.failure("Die Basis-Adresse muss mit http:// oder https:// beginnen.");
    }

    String requestBody = buildRequestBody(modelIdentifier);
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
    if (apiKey != null && !apiKey.isBlank()) {
      requestBuilder.header("Authorization", "Bearer " + apiKey);
    }

    try {
      HttpResponse<String> response =
          httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
      return interpret(response);
    } catch (IOException e) {
      log.warn("Chat model connection test failed for {}: {}", uri, e.getMessage());
      return TestOutcome.failure(translateConnectionError(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return TestOutcome.failure("Die Verbindung wurde unterbrochen.");
    }
  }

  private TestOutcome interpret(HttpResponse<String> response) {
    int status = response.statusCode();
    if (status == 200) {
      return TestOutcome.success("Verbindung erfolgreich, Modell hat geantwortet.");
    }
    if (status == 401) {
      return TestOutcome.failure(
          "Die Authentifizierung ist fehlgeschlagen (HTTP 401). Prüfen Sie den Zugangsschlüssel.");
    }
    if (status == 403) {
      return TestOutcome.failure("Der Zugriff wurde vom Server verweigert (HTTP 403).");
    }
    if (status == 404) {
      return TestOutcome.failure(
          "Die Modell-Kennung wurde am Endpunkt nicht gefunden (HTTP 404). Prüfen Sie die"
              + " Modell-Kennung.");
    }
    return TestOutcome.failure("Der Server antwortete mit HTTP " + status + ".");
  }

  /**
   * Chat completions live at {@code {baseUrl}/chat/completions} for every OpenAI-compatible server
   * this feature targets, including Ollama's own {@code /v1} endpoint
   * (docs/features/llm-integration.md#ein-anbindungsweg-nicht-zwei) - a trailing slash on {@code
   * baseUrl} is tolerated, never doubled.
   */
  private static URI chatCompletionsUri(String baseUrl) throws URISyntaxException {
    String trimmed = baseUrl.strip();
    String withoutTrailingSlash =
        trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    return new URI(withoutTrailingSlash + "/chat/completions");
  }

  private static String buildRequestBody(String modelIdentifier) {
    String escapedModel = modelIdentifier.replace("\\", "\\\\").replace("\"", "\\\"");
    return "{\"model\":\""
        + escapedModel
        + "\",\"messages\":[{\"role\":\"user\",\"content\":\"Ping\"}],\"max_tokens\":1}";
  }

  private static String translateConnectionError(IOException e) {
    if (e instanceof UnknownHostException) {
      return "Der Host konnte nicht gefunden werden (DNS-Auflösung fehlgeschlagen).";
    }
    if (e instanceof ConnectException) {
      return "Die Verbindung wurde vom Server abgelehnt.";
    }
    if (e instanceof HttpTimeoutException || e instanceof SocketTimeoutException) {
      return "Die Verbindung ist in ein Zeitlimit gelaufen.";
    }
    if (e instanceof SSLException) {
      return "Das Zertifikat des Servers konnte nicht geprüft werden.";
    }
    return "Die Adresse ist nicht erreichbar.";
  }

  /** The outcome of a connection test - German, user-facing {@link #message()} either way. */
  public record TestOutcome(boolean success, String message) {
    static TestOutcome success(String message) {
      return new TestOutcome(true, message);
    }

    static TestOutcome failure(String message) {
      return new TestOutcome(false, message);
    }
  }
}
