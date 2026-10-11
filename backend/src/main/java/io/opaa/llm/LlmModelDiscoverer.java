package io.opaa.llm;

import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.llm.ModelDiscovery.Failure;
import io.opaa.security.SettingsEncryptor;
import io.opaa.sourceaccess.BoundedStreams;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lists the models an OpenAI-compatible endpoint offers ({@code GET {baseUrl}/models}), so the
 * model form can offer them for selection. When the address ends in {@code /v1} and the same origin
 * also answers Ollama's own {@code /api/tags}, each model is enriched from {@code /api/tags} and
 * {@code /api/show}: size, family, parameter count, quantization, context length and capabilities.
 * Every detail call is optional - a failing one degrades to the bare list, never to a failed
 * discovery.
 *
 * <p>Same rules as {@link LlmModelConnectionTester}, through {@link ModelEndpointAccess}: no
 * credentials in the address, the stored key only for its model's own origin, no redirects. All
 * Ollama calls are derived from {@code baseUrl} by path alone and therefore stay on its origin.
 * Every body is read with a byte ceiling and a deadline, and the detail calls share one overall
 * budget, so a slow or hostile endpoint cannot hold the admin request for long.
 */
@Service
public class LlmModelDiscoverer {

  private static final Logger log = LoggerFactory.getLogger(LlmModelDiscoverer.class);

  /** Ceiling for the model list and for {@code /api/tags}. */
  static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

  /** Ceiling per {@code /api/show} answer, which also carries template and license text. */
  private static final int MAX_DETAIL_BYTES = 1024 * 1024;

  /** Models beyond this many keep their list entry but get no {@code /api/show} details. */
  private static final int MAX_DETAILED_MODELS = 100;

  private static final int PARALLEL_DETAIL_CALLS = 6;

  /** Shared by all {@code /api/show} calls of one discovery. */
  static final Duration DETAILS_BUDGET = Duration.ofSeconds(8);

  /** Identifier fragments of models that serve no chat: embedding, speech, image, moderation. */
  private static final List<String> NON_CHAT_MARKERS =
      List.of(
          "embed", "rerank", "whisper", "tts", "transcribe", "dall-e", "gpt-image", "moderation");

  private static final List<String> CONTEXT_LENGTH_FIELDS =
      List.of("context_length", "max_model_len", "context_window");

  private final ModelEndpointAccess access;
  private final ObjectMapper objectMapper;
  private final Duration detailsBudget;

  @Autowired
  public LlmModelDiscoverer(
      LlmModelRepository repository,
      SettingsEncryptor settingsEncryptor,
      ObjectMapper objectMapper) {
    this(repository, settingsEncryptor, objectMapper, DETAILS_BUDGET);
  }

  /** {@code detailsBudget} replaces {@link #DETAILS_BUDGET}, so a test need not wait it out. */
  LlmModelDiscoverer(
      LlmModelRepository repository,
      SettingsEncryptor settingsEncryptor,
      ObjectMapper objectMapper,
      Duration detailsBudget) {
    this.access = new ModelEndpointAccess(repository, settingsEncryptor);
    this.objectMapper = objectMapper;
    this.detailsBudget = detailsBudget;
  }

  /**
   * Lists the models at {@code baseUrl}. {@code apiKey} and {@code modelId} follow the rules of
   * {@link LlmModelConnectionTester#test}.
   *
   * @throws ValidationException 400 when {@code baseUrl} carries credentials, or when the stored
   *     key of {@code modelId} would have to leave its model's origin
   * @throws NotFoundException 404 when {@code modelId} is set but no such model exists
   */
  public ModelDiscovery discover(String baseUrl, String apiKey, UUID modelId) {
    String key = access.effectiveApiKey(baseUrl, apiKey, modelId);
    URI modelsUri;
    try {
      modelsUri = ModelEndpointUri.append(baseUrl, "/models");
    } catch (IllegalArgumentException | URISyntaxException e) {
      return ModelDiscovery.failed(
          Failure.INVALID_ADDRESS, "Die Basis-Adresse ist keine gültige URL.");
    }
    if (!ModelEndpointAccess.isHttp(modelsUri) || modelsUri.getHost() == null) {
      return ModelDiscovery.failed(
          Failure.INVALID_ADDRESS, "Die Basis-Adresse muss mit http:// oder https:// beginnen.");
    }

    try (HttpClient client = ModelEndpointAccess.newHttpClient()) {
      Fetched list = get(client, modelsUri, key, MAX_RESPONSE_BYTES);
      ModelDiscovery failure = failureFor(list.status(), key);
      if (failure != null) {
        return failure;
      }
      Map<String, Integer> listed = parseModelList(list.body());
      if (listed == null) {
        return noModelList();
      }
      OllamaDetails ollama = ollamaDetails(client, baseUrl, key, listed.keySet());
      return ModelDiscovery.found(ollama != null, toModels(listed, ollama));
    } catch (BoundedStreams.LimitExceededException e) {
      return ModelDiscovery.failed(
          Failure.UNEXPECTED_RESPONSE,
          "Die Modellliste ist zu groß (mehr als " + MAX_RESPONSE_BYTES / (1024 * 1024) + " MiB).");
    } catch (IOException e) {
      log.warn("Model discovery failed for {}: {}", modelsUri, e.getMessage());
      return ModelDiscovery.failed(
          Failure.UNREACHABLE, ModelEndpointAccess.translateConnectionError(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ModelDiscovery.failed(Failure.UNREACHABLE, "Die Verbindung wurde unterbrochen.");
    }
  }

  private static ModelDiscovery failureFor(int status, String key) {
    if (status == 200) {
      return null;
    }
    if (status == 401) {
      return ModelDiscovery.failed(
          Failure.UNAUTHENTICATED,
          key == null || key.isBlank()
              ? "Der Endpunkt verlangt einen Zugangsschlüssel (HTTP 401). Tragen Sie ihn ein und"
                  + " rufen Sie die Modelle erneut ab."
              : "Der Endpunkt hat den Zugangsschlüssel abgelehnt (HTTP 401). Prüfen Sie den"
                  + " Schlüssel.");
    }
    if (status == 403) {
      return ModelDiscovery.failed(
          Failure.UNAUTHENTICATED,
          "Der Zugriff wurde vom Server verweigert (HTTP 403). Prüfen Sie den Zugangsschlüssel.");
    }
    if (status == 404 || status == 405) {
      return noModelList();
    }
    if (status >= 300 && status < 400) {
      return ModelDiscovery.failed(
          Failure.UNEXPECTED_RESPONSE,
          "Der Endpunkt leitet auf eine andere Adresse um (HTTP "
              + status
              + "). Tragen Sie die Zieladresse direkt ein.");
    }
    return ModelDiscovery.failed(
        Failure.UNEXPECTED_RESPONSE, "Der Server antwortete mit HTTP " + status + ".");
  }

  private static ModelDiscovery noModelList() {
    return ModelDiscovery.failed(
        Failure.NO_MODEL_LIST,
        "Der Endpunkt bietet keine Modellliste an. Tragen Sie die Modell-Kennung von Hand ein.");
  }

  /**
   * The identifiers of an OpenAI-style list ({@code {"data":[{"id":...}]}}) in their listed order,
   * each with the context length the entry reports, if any; {@code null} when the body is no such
   * list.
   */
  private Map<String, Integer> parseModelList(byte[] body) {
    JsonNode root = readJson(body);
    if (root == null || !root.path("data").isArray()) {
      return null;
    }
    Map<String, Integer> listed = new LinkedHashMap<>();
    for (JsonNode entry : root.path("data")) {
      JsonNode id = entry.path("id");
      if (id.isString() && !id.asString().isBlank()) {
        listed.putIfAbsent(id.asString(), contextLengthOf(entry));
      }
    }
    return listed;
  }

  private static Integer contextLengthOf(JsonNode entry) {
    for (String field : CONTEXT_LENGTH_FIELDS) {
      JsonNode value = entry.path(field);
      if (value.isIntegralNumber() && value.asLong() > 0 && value.canConvertToInt()) {
        return value.asInt();
      }
    }
    return null;
  }

  private JsonNode readJson(byte[] body) {
    try {
      return objectMapper.readTree(body);
    } catch (JacksonException e) {
      return null;
    }
  }

  // --- Ollama ---

  private record Tag(
      Long sizeBytes,
      String family,
      List<String> families,
      String parameterSize,
      String quantization) {}

  private record Show(Integer contextLength, List<String> capabilities) {}

  private record OllamaDetails(Map<String, Tag> tags, Map<String, Show> shows) {}

  /** {@code null} when the endpoint is not recognized as Ollama. */
  private OllamaDetails ollamaDetails(
      HttpClient client, String baseUrl, String key, Iterable<String> identifiers)
      throws InterruptedException {
    String root = ollamaRoot(baseUrl);
    if (root == null) {
      return null;
    }
    Map<String, Tag> tags;
    try {
      Fetched fetched =
          get(client, ModelEndpointUri.append(root, "/api/tags"), key, MAX_RESPONSE_BYTES);
      tags = fetched.status() == 200 ? parseTags(fetched.body()) : null;
    } catch (IOException | URISyntaxException | IllegalArgumentException e) {
      log.debug("No Ollama tag list at {}: {}", root, e.getMessage());
      tags = null;
    }
    if (tags == null) {
      return null;
    }
    return new OllamaDetails(tags, shows(client, root, key, identifiers));
  }

  /** Ollama serves its OpenAI-compatible API under {@code /v1} beside its own {@code /api}. */
  private static String ollamaRoot(String baseUrl) {
    String trimmed = baseUrl.strip();
    int query = trimmed.indexOf('?');
    if (query >= 0) {
      trimmed = trimmed.substring(0, query);
    }
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed.toLowerCase(Locale.ROOT).endsWith("/v1")
        ? trimmed.substring(0, trimmed.length() - "/v1".length())
        : null;
  }

  private Map<String, Tag> parseTags(byte[] body) {
    JsonNode root = readJson(body);
    if (root == null || !root.path("models").isArray()) {
      return null;
    }
    Map<String, Tag> tags = new HashMap<>();
    for (JsonNode entry : root.path("models")) {
      JsonNode details = entry.path("details");
      Tag tag =
          new Tag(
              entry.path("size").isIntegralNumber() ? entry.path("size").asLong() : null,
              text(details.path("family")),
              strings(details.path("families")),
              text(details.path("parameter_size")),
              text(details.path("quantization_level")));
      for (String name :
          List.of(entry.path("name").asString(""), entry.path("model").asString(""))) {
        if (!name.isBlank()) {
          tags.putIfAbsent(name, tag);
        }
      }
    }
    return tags;
  }

  /**
   * One {@code /api/show} per model, a few at a time, within the details budget overall. A model
   * whose call fails or does not finish in time simply has no entry.
   */
  private Map<String, Show> shows(
      HttpClient client, String root, String key, Iterable<String> identifiers)
      throws InterruptedException {
    URI showUri;
    try {
      showUri = ModelEndpointUri.append(root, "/api/show");
    } catch (URISyntaxException | IllegalArgumentException e) {
      return Map.of();
    }
    Semaphore permits = new Semaphore(PARALLEL_DETAIL_CALLS);
    Map<String, Future<Show>> pending = new LinkedHashMap<>();
    long deadline = System.nanoTime() + detailsBudget.toNanos();
    // Not closed via try-with-resources: close() would await calls still hanging after the budget
    // or an interrupt. They are cancelled and the executor abandoned without waiting.
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      int submitted = 0;
      for (String identifier : identifiers) {
        if (submitted++ >= MAX_DETAILED_MODELS) {
          break;
        }
        pending.put(
            identifier,
            executor.submit(
                () -> {
                  permits.acquire();
                  try {
                    return show(client, showUri, key, identifier);
                  } finally {
                    permits.release();
                  }
                }));
      }
      Map<String, Show> shows = new HashMap<>();
      for (Map.Entry<String, Future<Show>> entry : pending.entrySet()) {
        Show show = await(entry.getValue(), deadline);
        if (show != null) {
          shows.put(entry.getKey(), show);
        }
      }
      return shows;
    } finally {
      pending.values().forEach(future -> future.cancel(true));
      executor.shutdownNow();
    }
  }

  private static Show await(Future<Show> future, long deadline) throws InterruptedException {
    try {
      return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (ExecutionException | TimeoutException e) {
      return null;
    }
  }

  private Show show(HttpClient client, URI showUri, String key, String identifier)
      throws IOException, InterruptedException {
    byte[] request = objectMapper.writeValueAsBytes(Map.of("model", identifier));
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(showUri)
            .timeout(ModelEndpointAccess.REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(request));
    Fetched fetched = send(client, ModelEndpointAccess.authorized(builder, key), MAX_DETAIL_BYTES);
    JsonNode root = fetched.status() == 200 ? readJson(fetched.body()) : null;
    if (root == null || !root.isObject()) {
      return null;
    }
    return new Show(
        contextLengthFromModelInfo(root.path("model_info")), strings(root.path("capabilities")));
  }

  /**
   * {@code model_info} names the context length {@code <architecture>.context_length}; the key of
   * the model's own architecture wins over any other.
   */
  private static Integer contextLengthFromModelInfo(JsonNode modelInfo) {
    String architecture = text(modelInfo.path("general.architecture"));
    if (architecture != null) {
      JsonNode own = modelInfo.path(architecture + ".context_length");
      if (own.isIntegralNumber() && own.canConvertToInt()) {
        return own.asInt();
      }
    }
    for (Map.Entry<String, JsonNode> field : modelInfo.properties()) {
      if (field.getKey().endsWith(".context_length")
          && field.getValue().isIntegralNumber()
          && field.getValue().canConvertToInt()) {
        return field.getValue().asInt();
      }
    }
    return null;
  }

  // --- Assembly ---

  private static List<DiscoveredModel> toModels(Map<String, Integer> listed, OllamaDetails ollama) {
    List<DiscoveredModel> models = new ArrayList<>();
    listed.forEach(
        (identifier, listedContextLength) -> {
          Tag tag = ollama == null ? null : ollama.tags().get(identifier);
          Show show = ollama == null ? null : ollama.shows().get(identifier);
          List<String> capabilities = show == null ? List.of() : show.capabilities();
          Integer contextLength =
              show != null && show.contextLength() != null
                  ? show.contextLength()
                  : listedContextLength;
          models.add(
              new DiscoveredModel(
                  identifier,
                  tag == null ? null : tag.family(),
                  tag == null ? null : tag.parameterSize(),
                  tag == null ? null : tag.quantization(),
                  tag == null ? null : tag.sizeBytes(),
                  contextLength,
                  capabilities,
                  chatSuitable(identifier, capabilities, tag)));
        });
    models.sort(
        Comparator.comparing((DiscoveredModel model) -> !model.chatSuitable())
            .thenComparing(DiscoveredModel::identifier));
    return models;
  }

  /**
   * Reported capabilities decide when there are any; otherwise an embedding family ({@code bert}
   * and its descendants) or an identifier naming a non-chat model rules a model out.
   */
  private static boolean chatSuitable(String identifier, List<String> capabilities, Tag tag) {
    if (!capabilities.isEmpty()) {
      return capabilities.contains("completion");
    }
    if (tag != null) {
      List<String> families = new ArrayList<>(tag.families());
      if (tag.family() != null) {
        families.add(tag.family());
      }
      if (families.stream().anyMatch(family -> family.toLowerCase(Locale.ROOT).contains("bert"))) {
        return false;
      }
    }
    String lower = identifier.toLowerCase(Locale.ROOT);
    return NON_CHAT_MARKERS.stream().noneMatch(lower::contains);
  }

  // --- HTTP and JSON helpers ---

  private record Fetched(int status, byte[] body) {}

  private Fetched get(HttpClient client, URI uri, String key, int maxBytes)
      throws IOException, InterruptedException {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(ModelEndpointAccess.REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .GET();
    return send(client, ModelEndpointAccess.authorized(builder, key), maxBytes);
  }

  /**
   * Reads the body only for a 200, within {@code maxBytes} and the request timeout counted from the
   * start of the call - {@link HttpRequest.Builder#timeout} alone ends at the headers.
   */
  private static Fetched send(HttpClient client, HttpRequest.Builder builder, int maxBytes)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + ModelEndpointAccess.REQUEST_TIMEOUT.toNanos();
    HttpResponse<InputStream> response =
        client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream body = response.body()) {
      if (response.statusCode() != 200) {
        return new Fetched(response.statusCode(), new byte[0]);
      }
      return new Fetched(200, BoundedStreams.readFullyBefore(body, maxBytes, deadline));
    }
  }

  private static String text(JsonNode node) {
    return node.isString() && !node.asString().isBlank() ? node.asString() : null;
  }

  private static List<String> strings(JsonNode node) {
    if (!node.isArray()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (JsonNode element : node) {
      if (element.isString() && !element.asString().isBlank()) {
        values.add(element.asString());
      }
    }
    return List.copyOf(values);
  }
}
