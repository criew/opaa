package io.opaa.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.testcontainers.ollama.OllamaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Provisions one Ollama model on the endpoint a harness run talks to and verifies its content
 * digest (ADR-0011, Entscheidung 4): a fixed tag alone does not stop the tag from being
 * force-pushed upstream, so the digest Ollama reports for the pulled model is pinned and checked as
 * well.
 *
 * <p>Model-agnostic since issue #1085: the embedding model and the chat model the decomposition
 * path needs are provisioned through this same class, so both are pinned the same way rather than
 * through two implementations that can drift.
 */
final class EvalOllamaModel {

  // Non-streaming POST /api/pull only returns once the whole model is on disk; 10 minutes
  // comfortably covers a cold pull over a normal connection without masking a genuinely hung
  // request as a slow one indefinitely.
  private static final Duration PULL_TIMEOUT = Duration.ofMinutes(10);

  private EvalOllamaModel() {}

  /**
   * Makes {@code model} available at {@code endpoint} and returns the digest it actually resolves
   * to, which always equals {@code expectedDigest} — a mismatch throws instead.
   *
   * <p>Checks {@code GET /api/tags} before pulling anything: on a warm model volume (developer
   * machine, or restored from the CI cache in {@code .github/workflows/retrieval-regression.yml})
   * the model is already present with the expected digest and {@code ollama pull} is skipped
   * entirely. Without that check, {@code ollama pull} always reaches out to the model registry to
   * resolve the tag's manifest even when every layer is cached locally, which would contradict this
   * harness's claim (eval/README.md, ADR-0011) of not downloading the model again on a warm cache.
   * Narrowly scoped claim, not "no third-party network access at all": Testcontainers still pulls
   * the {@code pgvector/pgvector} and {@code ollama/ollama} base images from Docker Hub regardless.
   *
   * @param container the Ollama Testcontainer, or {@code null} when {@link
   *     EvalOllamaEndpoint#isExternal()} selects an already-running endpoint (issue #1076) — the
   *     pull then goes through {@code POST /api/pull} instead of {@code ollama pull} in a
   *     container.
   */
  static String ensurePresent(
      String endpoint,
      OllamaContainer container,
      String model,
      String expectedDigest,
      Logger log,
      String cacheHint)
      throws IOException, InterruptedException {
    String cachedDigest = tryFetchDigest(endpoint, model);
    if (cachedDigest != null && expectedDigest.equalsIgnoreCase(cachedDigest)) {
      log.info(
          "{} already present at {} with the expected digest {} — skipping 'ollama pull'.",
          model,
          endpoint,
          cachedDigest);
      return cachedDigest;
    }

    log.info("Pulling {} into the Ollama endpoint at {}{}", model, endpoint, cacheHint);
    if (container == null) {
      pullViaHttp(endpoint, model);
    } else {
      var pullResult = container.execInContainer("ollama", "pull", model);
      if (pullResult.getExitCode() != 0) {
        throw new IllegalStateException(
            "Failed to pull '" + model + "' in the Ollama container: " + pullResult.getStderr());
      }
    }

    String actualDigest = fetchDigest(endpoint, model);
    if (!expectedDigest.equalsIgnoreCase(actualDigest)) {
      throw new IllegalStateException(
          "Model drift detected: '"
              + model
              + "' now resolves to digest "
              + actualDigest
              + ", but this harness pins "
              + expectedDigest
              + ". The tag was force-updated upstream — treat this as a deliberate baseline re-pin "
              + "(new digest constant, new evaluation run, updated numbers in the PR), not a code "
              + "bug.");
    }
    log.info("Model digest verified for {}: {}", model, actualDigest);
    return actualDigest;
  }

  /** {@code null} instead of throwing when the tag is not present yet (fresh/empty volume). */
  private static String tryFetchDigest(String endpoint, String model)
      throws IOException, InterruptedException {
    try {
      return fetchDigest(endpoint, model);
    } catch (IllegalStateException e) {
      return null;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record OllamaTagsResponse(List<OllamaModelTag> models) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record OllamaModelTag(String name, String digest) {}

  private static String fetchDigest(String endpoint, String model)
      throws IOException, InterruptedException {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/api/tags")).GET().build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "GET /api/tags on the Ollama endpoint failed with status " + response.statusCode());
    }
    OllamaTagsResponse tags =
        JsonMapper.builder().build().readValue(response.body(), OllamaTagsResponse.class);
    return tags.models().stream()
        .filter(m -> model.equals(m.name()))
        .map(OllamaModelTag::digest)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Model '" + model + "' not found in /api/tags response: " + tags));
  }

  /**
   * Pulls on an external Ollama endpoint (issue #1076) — {@code execInContainer} only works against
   * a Testcontainer, so this equivalent goes through Ollama's {@code POST /api/pull} HTTP API
   * instead, non-streaming so the call blocks until the pull actually finishes (or fails).
   */
  private static void pullViaHttp(String endpoint, String model)
      throws IOException, InterruptedException {
    HttpClient client = HttpClient.newHttpClient();
    String requestBody =
        JsonMapper.builder().build().writeValueAsString(Map.of("model", model, "stream", false));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint + "/api/pull"))
            .timeout(PULL_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    HttpResponse<String> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (java.net.http.HttpTimeoutException e) {
      throw new IllegalStateException(
          "Timed out after "
              + PULL_TIMEOUT
              + " pulling '"
              + model
              + "' via POST /api/pull on the external Ollama endpoint "
              + endpoint
              + " — check that the endpoint is reachable and has network access to pull the model.",
          e);
    }
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "Failed to pull '"
              + model
              + "' via POST /api/pull on the external Ollama endpoint: HTTP "
              + response.statusCode()
              + " — "
              + response.body());
    }
  }
}
