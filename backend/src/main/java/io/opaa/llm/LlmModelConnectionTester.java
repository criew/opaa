package io.opaa.llm;

import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.compliance.DataFlow;
import io.opaa.security.SettingsEncryptor;
import io.opaa.sourceaccess.BoundedStreams;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Probes a managed chat model's OpenAI-compatible endpoint with a bounded one-token chat completion
 * and reports unreachable, not authenticated and unknown model apart, in German.
 *
 * <p>An address other than the model's stored one passes {@link ModelAddressPolicy} before anything
 * is sent. The stored key is only ever sent to the origin of the model's own stored {@code
 * baseUrl}.
 */
@DataFlow("model-endpoint")
@Service
public class LlmModelConnectionTester {

  private static final Logger log = LoggerFactory.getLogger(LlmModelConnectionTester.class);

  private static final int MAX_REASON_LENGTH = 300;

  /**
   * The most of an answer the probe reads, within {@link ModelEndpointAccess#REQUEST_TIMEOUT}
   * counted from the start of the call: a one-token completion or an error object is far smaller.
   */
  static final int MAX_RESPONSE_BYTES = 256 * 1024;

  private final ModelEndpointAccess access;
  private final ObjectMapper objectMapper;

  public LlmModelConnectionTester(
      LlmModelRepository repository,
      SettingsEncryptor settingsEncryptor,
      ObjectMapper objectMapper,
      ModelAddressPolicy modelAddresses) {
    this.access = new ModelEndpointAccess(repository, settingsEncryptor, modelAddresses);
    this.objectMapper = objectMapper;
  }

  /**
   * Tests {@code baseUrl}/{@code modelIdentifier} with {@code apiKey}. A blank {@code apiKey} with
   * a {@code modelId} uses that model's stored key, but only for a same-origin {@code baseUrl}.
   *
   * @throws NotFoundException 404 when {@code modelId} is set but no such model exists
   * @throws ValidationException 400 when the stored key would have to be sent to an address other
   *     than the model's own, or when {@code baseUrl} itself carries credentials - the probe URI is
   *     logged on failure, so such an address must not reach the probe at all
   */
  public TestOutcome test(
      String baseUrl, String modelIdentifier, String apiKey, UUID modelId, BigDecimal temperature) {
    return test(baseUrl, modelIdentifier, apiKey, modelId, null, temperature);
  }

  /**
   * {@link #test(String, String, String, UUID, BigDecimal)} for the model {@code storedModelId}
   * being edited: its stored endpoint passes the address check unchecked, also while {@code
   * modelId} is withheld because the removal of the stored key is requested.
   */
  public TestOutcome test(
      String baseUrl,
      String modelIdentifier,
      String apiKey,
      UUID modelId,
      UUID storedModelId,
      BigDecimal temperature) {
    return probe(
        baseUrl,
        modelIdentifier,
        access.effectiveApiKey(baseUrl, apiKey, modelId),
        temperature,
        storedModelId != null ? storedModelId : modelId);
  }

  private TestOutcome probe(
      String baseUrl,
      String modelIdentifier,
      String apiKey,
      BigDecimal temperature,
      UUID storedModelId) {
    URI uri;
    try {
      uri = chatCompletionsUri(baseUrl);
    } catch (IllegalArgumentException | URISyntaxException e) {
      return TestOutcome.failure("Die Basis-Adresse ist keine gültige URL.");
    }
    if (!ModelEndpointAccess.isHttp(uri)) {
      return TestOutcome.failure("Die Basis-Adresse muss mit http:// oder https:// beginnen.");
    }
    String blocked = access.blockedAddress(baseUrl, storedModelId);
    if (blocked != null) {
      return TestOutcome.failure(blocked);
    }

    String requestBody = buildRequestBody(modelIdentifier, temperature);
    HttpRequest.Builder requestBuilder =
        ModelEndpointAccess.authorized(
            HttpRequest.newBuilder(uri)
                .timeout(ModelEndpointAccess.REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8)),
            apiKey);

    // Closed per request; the client never follows a redirect (see ModelEndpointAccess).
    long deadline = System.nanoTime() + ModelEndpointAccess.REQUEST_TIMEOUT.toNanos();
    try (HttpClient httpClient = ModelEndpointAccess.newHttpClient()) {
      HttpResponse<InputStream> response =
          httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
      int status = response.statusCode();
      String body = null;
      try (InputStream in = response.body()) {
        // only the answer and the provider's refusal are read; any other status speaks for itself
        if (status == 200 || status == 400) {
          body =
              new String(
                  BoundedStreams.readFullyBefore(in, MAX_RESPONSE_BYTES, deadline),
                  StandardCharsets.UTF_8);
        }
      }
      return interpret(status, body, temperature != null);
    } catch (BoundedStreams.LimitExceededException e) {
      log.warn(
          "Chat model connection test answer from {} exceeds {} bytes", uri, MAX_RESPONSE_BYTES);
      return TestOutcome.failure(
          "Der Endpunkt hat unerwartet viel geantwortet (mehr als "
              + MAX_RESPONSE_BYTES / 1024
              + " KiB). Prüfen Sie die Basis-Adresse.");
    } catch (IOException e) {
      log.warn("Chat model connection test failed for {}: {}", uri, e.getMessage());
      return TestOutcome.failure(ModelEndpointAccess.translateConnectionError(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return TestOutcome.failure("Die Verbindung wurde unterbrochen.");
    }
  }

  private TestOutcome interpret(int status, String body, boolean temperatureSent) {
    if (status == 200) {
      if (LeadingReasoningText.startsWithBlock(answerText(body))) {
        return TestOutcome.success(
            "Verbindung erfolgreich, Modell hat geantwortet. Die Antwort beginnt mit Denktext"
                + " des Modells; OPAA entfernt ihn vor der Weiterverarbeitung.");
      }
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
    if (status == 400) {
      return rejected(body, temperatureSent);
    }
    return TestOutcome.failure(ModelEndpointAccess.unexpectedStatus(status));
  }

  /**
   * A 400 is the provider refusing the request itself - an unsupported parameter, typically. Its
   * own reason ({@code error.message} of the OpenAI error shape) is quoted, cut to a readable
   * length.
   */
  private TestOutcome rejected(String body, boolean temperatureSent) {
    String reason = providerReason(body);
    StringBuilder message = new StringBuilder("Der Anbieter hat die Anfrage abgelehnt (HTTP 400)");
    if (reason == null) {
      message.append('.');
    } else {
      message.append(": „").append(reason).append("“");
    }
    if (temperatureSent
        && reason != null
        && reason.toLowerCase(Locale.ROOT).contains("temperature")) {
      message.append(
          " Dieses Modell akzeptiert offenbar keine Temperatur. Lassen Sie das Feld Temperatur leer.");
    }
    log.warn("Chat model connection test rejected with HTTP 400: {}", reason);
    return TestOutcome.failure(message.toString());
  }

  /** The probe answer's {@code choices[0].message.content}, or {@code null} when absent. */
  private String answerText(String body) {
    if (!StringUtils.hasText(body)) {
      return null;
    }
    try {
      return objectMapper
          .readTree(body)
          .path("choices")
          .path(0)
          .path("message")
          .path("content")
          .asString(null);
    } catch (JacksonException e) {
      return null;
    }
  }

  private String providerReason(String body) {
    if (!StringUtils.hasText(body)) {
      return null;
    }
    try {
      String reason = objectMapper.readTree(body).path("error").path("message").asString(null);
      if (!StringUtils.hasText(reason)) {
        return null;
      }
      return reason.length() > MAX_REASON_LENGTH
          ? reason.substring(0, MAX_REASON_LENGTH) + " …"
          : reason;
    } catch (JacksonException e) {
      return null;
    }
  }

  /**
   * {@code {baseUrl}/chat/completions}; a trailing slash is not doubled and a query string moves to
   * the end. {@link ModelEndpointUri} also rejects credentials in the address.
   */
  private static URI chatCompletionsUri(String baseUrl) throws URISyntaxException {
    return ModelEndpointUri.append(baseUrl, "/chat/completions");
  }

  /**
   * Serialised with Jackson: {@code modelIdentifier} is free text and may hold control characters.
   */
  private String buildRequestBody(String modelIdentifier, BigDecimal temperature) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", "user");
    message.put("content", "Ping");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", modelIdentifier);
    body.put("messages", List.of(message));
    body.put("max_tokens", 1);
    if (temperature != null) {
      body.put("temperature", temperature.doubleValue());
    }
    try {
      return objectMapper.writeValueAsString(body);
    } catch (JacksonException e) {
      // Only ever thrown for a value Jackson cannot serialize at all - none of the values above
      // (Strings, a List, numbers) can trigger that, so this is unreachable in practice.
      throw new IllegalStateException("Der Testaufruf konnte nicht aufgebaut werden", e);
    }
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
