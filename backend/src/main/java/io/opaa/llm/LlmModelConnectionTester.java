package io.opaa.llm;

import io.opaa.indexing.AutoindexCrawlerService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
 *
 * <p><b>The stored key never leaves its own origin (#757 review, key-exfiltration finding).</b> An
 * earlier version of {@link #test} decrypted {@code modelId}'s stored key and then probed whatever
 * {@code baseUrl} the request carried - a {@code SYSTEM_ADMIN} could submit {@code modelId} for a
 * genuine model together with an attacker-controlled {@code baseUrl} and have the stored plaintext
 * key sent to that address in the {@code Authorization} header, which made the key not actually
 * write-only despite {@link LlmModel#getApiKeyCiphertext()} never being returned by any response.
 * {@link #test} now only ever uses the stored key when the request's {@code baseUrl} is same-origin
 * ({@link AutoindexCrawlerService#sameOrigin}) with the model's own stored {@code baseUrl}; any
 * other combination is rejected with 400 before the key is even decrypted.
 */
@Service
public class LlmModelConnectionTester {

  private static final Logger log = LoggerFactory.getLogger(LlmModelConnectionTester.class);

  /** Short enough that a caller testing several drafts in a row is never left waiting long. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final LlmModelRepository repository;
  private final SettingsEncryptor settingsEncryptor;
  private final ObjectMapper objectMapper;

  public LlmModelConnectionTester(
      LlmModelRepository repository,
      SettingsEncryptor settingsEncryptor,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.settingsEncryptor = settingsEncryptor;
    this.objectMapper = objectMapper;
  }

  /**
   * Tests {@code baseUrl}/{@code modelIdentifier} with {@code apiKey} as given. When {@code apiKey}
   * is omitted or blank and {@code modelId} is set, the stored, decrypted key of that model is used
   * instead - lets a caller re-test an already saved model's connection without having to re-enter
   * a key the API never returns to begin with. That fallback only ever fires when {@code baseUrl}
   * is same-origin with {@code modelId}'s own stored {@code baseUrl} (see this class's own Javadoc)
   * - otherwise the request is rejected with 400 rather than silently probing without a key or,
   * worse, sending the stored key to an address it was never meant for.
   *
   * @throws ResponseStatusException 404 when {@code modelId} is set but no such model exists; 400
   *     when the stored key would have to be sent to an address other than the model's own
   */
  public TestOutcome test(String baseUrl, String modelIdentifier, String apiKey, UUID modelId) {
    String effectiveApiKey = apiKey;
    if (!StringUtils.hasText(apiKey) && modelId != null) {
      LlmModel model =
          repository
              .findById(modelId)
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.NOT_FOUND,
                          "Kein Chat-Modell mit der ID " + modelId + " gefunden"));
      if (model.getApiKeyCiphertext() == null) {
        effectiveApiKey = null;
      } else {
        if (!sameOrigin(baseUrl, model.getBaseUrl())) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "Die angegebene Basis-Adresse weicht von der gespeicherten Adresse dieses Modells"
                  + " ab. Der gespeicherte Zugangsschlüssel kann nur für die ursprüngliche Adresse"
                  + " verwendet werden - bitte den Schlüssel erneut eingeben oder die"
                  + " gespeicherte Basis-Adresse zum Testen verwenden.");
        }
        effectiveApiKey = settingsEncryptor.decrypt(model.getApiKeyCiphertext());
      }
    }
    return probe(baseUrl, modelIdentifier, effectiveApiKey);
  }

  private static boolean sameOrigin(String requestBaseUrl, String storedBaseUrl) {
    try {
      return AutoindexCrawlerService.sameOrigin(
          URI.create(requestBaseUrl), URI.create(storedBaseUrl));
    } catch (IllegalArgumentException e) {
      return false;
    }
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
    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
    if (StringUtils.hasText(apiKey)) {
      requestBuilder.header("Authorization", "Bearer " + apiKey);
    }

    // #757 review: HttpClient implements AutoCloseable since Java 21 (this project's runtime, see
    // ADR-0002) - closed here rather than left to the garbage collector, same as any other
    // per-request resource. Redirect.NEVER is explicit rather than relying on HttpClient's own
    // default (also NEVER, but not a guarantee this class should depend on staying that way): the
    // Authorization header carrying a real access key must never be replayed to a redirect target
    // this class never validated, the same reasoning io.opaa.indexing.AutoindexCrawlerService's own
    // manual redirect handling documents for its own Authorization header.
    try (HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()) {
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
   * baseUrl} is tolerated, never doubled. Any query string on {@code baseUrl} (e.g. an Azure {@code
   * api-version} parameter) is preserved and moved to the end of the resulting URI rather than
   * ending up in the middle of the path (#757 review).
   */
  private static URI chatCompletionsUri(String baseUrl) throws URISyntaxException {
    String trimmed = baseUrl.strip();
    int queryIndex = trimmed.indexOf('?');
    String withoutQuery = queryIndex >= 0 ? trimmed.substring(0, queryIndex) : trimmed;
    String query = queryIndex >= 0 ? trimmed.substring(queryIndex) : "";
    String withoutTrailingSlash =
        withoutQuery.endsWith("/")
            ? withoutQuery.substring(0, withoutQuery.length() - 1)
            : withoutQuery;
    return new URI(withoutTrailingSlash + "/chat/completions" + query);
  }

  /**
   * Built with Jackson rather than hand-assembled string concatenation (#757 review) - {@code
   * modelIdentifier} is operator-entered free text and a hand-escaped string missed control
   * characters a real JSON serializer never would.
   */
  private String buildRequestBody(String modelIdentifier) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", "user");
    message.put("content", "Ping");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", modelIdentifier);
    body.put("messages", List.of(message));
    body.put("max_tokens", 1);
    try {
      return objectMapper.writeValueAsString(body);
    } catch (JacksonException e) {
      // Only ever thrown for a value Jackson cannot serialize at all - none of the values above
      // (Strings, a List, an int) can trigger that, so this is unreachable in practice.
      throw new IllegalStateException("Der Testaufruf konnte nicht aufgebaut werden", e);
    }
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
