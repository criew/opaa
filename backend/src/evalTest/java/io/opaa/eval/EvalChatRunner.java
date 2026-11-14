package io.opaa.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts every decomposing measurement run on a freshly loaded chat runner (ADR-0012, Entscheidung
 * 54). The Ollama chat runner keeps the KV state of earlier prompts and reuses it for any later
 * prompt sharing a prefix; a prefix computed in another batch yields slightly different logits, so
 * greedy output at temperature 0 depends on which requests the runner has seen. Unloading the model
 * before each run makes that history the run's own, independent of keep-alive timing.
 */
final class EvalChatRunner {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration UNLOAD_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The runner {@link #startFresh} unloads, or {@code null} when none is registered. */
  private static volatile Target registered;

  private EvalChatRunner() {}

  /** The pinned chat model at an Ollama endpoint this harness run owns. */
  record Target(String ollamaEndpoint, String model) {}

  /** Makes {@code model} at {@code ollamaEndpoint} the runner {@link #startFresh} unloads. */
  static void register(String ollamaEndpoint, String model) {
    registered = new Target(ollamaEndpoint, model);
  }

  /**
   * Forgets the registered runner: an external chat model is not served by an endpoint this run
   * controls, and its Befundlauf is not deterministic anyway.
   */
  static void clear() {
    registered = null;
  }

  /** The registered runner, empty when there is none. */
  static Optional<Target> registered() {
    return Optional.ofNullable(registered);
  }

  /** Unloads the registered chat runner, or does nothing when none is registered. */
  static void startFresh() {
    Target target = registered;
    if (target != null) {
      unload(target.ollamaEndpoint(), target.model());
    }
  }

  /**
   * Asks Ollama to unload {@code model} ({@code keep_alive: 0}) and returns once {@code GET
   * /api/ps} no longer lists it, so the next request loads a runner without any cached prompt.
   * Throws if the endpoint refuses or the model is still loaded after {@link #UNLOAD_TIMEOUT}.
   */
  static void unload(String ollamaEndpoint, String model) {
    HttpClient client = HttpClient.newHttpClient();
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(ollamaEndpoint + "/api/generate"))
                  .timeout(REQUEST_TIMEOUT)
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          JSON.writeValueAsString(Map.of("model", model, "keep_alive", 0))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "Unloading '"
                + model
                + "' via POST /api/generate failed with HTTP "
                + response.statusCode()
                + ": "
                + response.body());
      }
      Instant deadline = Instant.now().plus(UNLOAD_TIMEOUT);
      while (isLoaded(client, ollamaEndpoint, model)) {
        if (Instant.now().isAfter(deadline)) {
          throw new IllegalStateException(
              "'"
                  + model
                  + "' is still loaded "
                  + UNLOAD_TIMEOUT
                  + " after the unload request - the next decomposing run would reuse its cached"
                  + " prompts and measure a different state than a run on a fresh runner.");
        }
        Thread.sleep(POLL_INTERVAL);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Unloading '" + model + "' failed: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while unloading '" + model + "'", e);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record RunningModels(List<RunningModel> models) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record RunningModel(String name, String model) {}

  private static boolean isLoaded(HttpClient client, String ollamaEndpoint, String model)
      throws IOException, InterruptedException {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create(ollamaEndpoint + "/api/ps"))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "GET /api/ps on the Ollama endpoint failed with HTTP " + response.statusCode());
    }
    RunningModels running = JSON.readValue(response.body(), RunningModels.class);
    return running.models() != null
        && running.models().stream()
            .anyMatch(m -> model.equals(m.name()) || model.equals(m.model()));
  }
}
