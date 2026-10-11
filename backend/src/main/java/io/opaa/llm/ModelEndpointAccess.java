package io.opaa.llm;

import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.outbound.ProxyRefusal;
import io.opaa.security.SettingsEncryptor;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import javax.net.ssl.SSLException;
import org.springframework.util.StringUtils;

/**
 * What every admin-triggered call to a chat model endpoint shares - the connection test and the
 * model discovery alike: the "no credentials in the address" rule, the stored-key rule, one client
 * configuration and one German wording per connection failure.
 *
 * <p><b>The stored key never leaves its own origin.</b> With {@code modelId} and without a given
 * key, the stored key of that model is used only when the requested {@code baseUrl} is same-origin
 * ({@link RedirectFollowingFetcher#sameOrigin}) with the model's stored one; otherwise the request
 * is rejected with 400 before the key is decrypted. Redirects are never followed, so the {@code
 * Authorization} header cannot be replayed to an origin this rule never checked.
 */
final class ModelEndpointAccess {

  /** Short enough that a caller trying several drafts in a row is never left waiting long. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final LlmModelRepository repository;
  private final SettingsEncryptor settingsEncryptor;

  ModelEndpointAccess(LlmModelRepository repository, SettingsEncryptor settingsEncryptor) {
    this.repository = repository;
    this.settingsEncryptor = settingsEncryptor;
  }

  /**
   * The key to send to {@code baseUrl}: {@code apiKey} when given, else the stored key of {@code
   * modelId} under the same-origin rule, else none ({@code null}).
   *
   * @throws ValidationException 400 when {@code baseUrl} carries credentials, or when the stored
   *     key would have to be sent to an origin other than its model's own
   * @throws NotFoundException 404 when {@code modelId} is set but no such model exists
   */
  String effectiveApiKey(String baseUrl, String apiKey, UUID modelId) {
    if (ModelEndpointUri.containsCredentials(baseUrl)) {
      throw new ValidationException(ModelEndpointUri.CREDENTIALS_REJECTED_MESSAGE);
    }
    if (StringUtils.hasText(apiKey) || modelId == null) {
      return apiKey;
    }
    LlmModel model =
        repository
            .findById(modelId)
            .orElseThrow(
                () ->
                    new NotFoundException("Kein Chat-Modell mit der ID " + modelId + " gefunden"));
    if (model.getApiKeyCiphertext() == null) {
      return null;
    }
    if (!sameOrigin(baseUrl, model.getBaseUrl())) {
      throw new ValidationException(
          "Die angegebene Basis-Adresse weicht von der gespeicherten Adresse dieses Modells"
              + " ab. Der gespeicherte Zugangsschlüssel kann nur für die ursprüngliche Adresse"
              + " verwendet werden - bitte den Schlüssel erneut eingeben oder die"
              + " gespeicherte Basis-Adresse verwenden.");
    }
    return settingsEncryptor.decrypt(model.getApiKeyCiphertext());
  }

  private static boolean sameOrigin(String requestBaseUrl, String storedBaseUrl) {
    try {
      return RedirectFollowingFetcher.sameOrigin(
          URI.create(requestBaseUrl), URI.create(storedBaseUrl));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** A client that never follows a redirect; the caller closes it. */
  static HttpClient newHttpClient() {
    return HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  static boolean isHttp(URI uri) {
    String scheme = uri.getScheme();
    return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
  }

  static HttpRequest.Builder authorized(HttpRequest.Builder builder, String apiKey) {
    if (StringUtils.hasText(apiKey)) {
      builder.header("Authorization", "Bearer " + apiKey);
    }
    return builder;
  }

  static String translateConnectionError(IOException e) {
    Optional<String> proxyRefusal = ProxyRefusal.describe(e);
    if (proxyRefusal.isPresent()) {
      return proxyRefusal.get();
    }
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
}
