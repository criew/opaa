package io.opaa.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves before any measurement that the eval Ollama embeds a long input continuously across the
 * batch boundary of {@value #BATCH_BOUNDARY_TOKENS} tokens (ADR-0012, Nachtrag Ollama-Fassung). An
 * Ollama that evaluates such an input in several batches computes a bidirectional encoder wrongly:
 * appending ten characters across the boundary turns the vector instead of nudging it. The probe
 * embeds growing prefixes of one fixed corpus text that straddle the boundary and requires every
 * neighbouring pair to stay at or above {@value #MIN_NEIGHBOUR_COSINE}.
 */
final class EvalEmbeddingContinuity {

  static final int BATCH_BOUNDARY_TOKENS = 512;

  /**
   * Neighbouring prefixes differ by {@value #PREFIX_STEP_CHARS} characters, a few tokens out of
   * several hundred; a continuous embedding keeps them above 0.99, a split one fell to 0.41.
   */
  static final double MIN_NEIGHBOUR_COSINE = 0.98;

  static final String PROBE_FILE =
      "corpus/verwaltung/verwaltung-0019_gebuehrenordnung-ordnungsamt.md";

  static final int FIRST_PREFIX_CHARS = 1100;
  static final int LAST_PREFIX_CHARS = 1500;
  static final int PREFIX_STEP_CHARS = 20;

  private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

  private EvalEmbeddingContinuity() {}

  /** One embedded prefix: its length in characters, its token count and its vector. */
  record Probe(int chars, int tokens, double[] vector) {}

  /**
   * Embeds the probe prefixes of {@link #PROBE_FILE} with {@code model} at {@code endpoint} and
   * returns the lowest neighbour cosine; throws if the embedding is not continuous.
   */
  static double require(String endpoint, String model, Logger log)
      throws IOException, InterruptedException {
    String text = probeText(RepoPaths.evalDir().resolve(PROBE_FILE));
    HttpClient client = HttpClient.newHttpClient();
    List<Probe> probes = new ArrayList<>();
    for (int chars = FIRST_PREFIX_CHARS; chars <= LAST_PREFIX_CHARS; chars += PREFIX_STEP_CHARS) {
      probes.add(embed(client, endpoint, model, chars, text.substring(0, chars)));
    }
    double worst = requireContinuous(probes, model);
    String line =
        "Embedding continuity across "
            + BATCH_BOUNDARY_TOKENS
            + " tokens: lowest neighbour cosine "
            + String.format(Locale.ROOT, "%.4f", worst)
            + " over "
            + probes.size()
            + " prefixes ("
            + probes.getFirst().tokens()
            + " to "
            + probes.getLast().tokens()
            + " tokens) of "
            + PROBE_FILE;
    log.info(line);
    System.out.println(line);
    return worst;
  }

  /** The probe file with line endings normalized, so a CRLF checkout probes the same prefixes. */
  static String probeText(Path file) throws IOException {
    String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    if (text.length() < LAST_PREFIX_CHARS) {
      throw new IllegalStateException(
          "Probe file "
              + file
              + " has "
              + text.length()
              + " characters, needs "
              + LAST_PREFIX_CHARS);
    }
    return text;
  }

  /**
   * Returns the lowest cosine between neighbouring probes. Throws if the probes do not straddle the
   * batch boundary (the check would prove nothing) or if any neighbouring pair falls below {@link
   * #MIN_NEIGHBOUR_COSINE}.
   */
  static double requireContinuous(List<Probe> probes, String model) {
    if (probes.size() < 2
        || probes.getFirst().tokens() >= BATCH_BOUNDARY_TOKENS
        || probes.getLast().tokens() <= BATCH_BOUNDARY_TOKENS) {
      throw new IllegalStateException(
          "The embedding continuity probe must straddle "
              + BATCH_BOUNDARY_TOKENS
              + " tokens, but its prefixes span "
              + (probes.isEmpty()
                  ? "nothing"
                  : probes.getFirst().tokens() + " to " + probes.getLast().tokens() + " tokens")
              + " - adjust the prefix range in EvalEmbeddingContinuity.");
    }
    double worst = 1.0;
    Probe worstAfter = null;
    for (int i = 1; i < probes.size(); i++) {
      double cosine = cosine(probes.get(i - 1).vector(), probes.get(i).vector());
      if (cosine < worst) {
        worst = cosine;
        worstAfter = probes.get(i);
      }
    }
    if (worst < MIN_NEIGHBOUR_COSINE) {
      throw new IllegalStateException(
          String.format(
              Locale.ROOT,
              "The eval Ollama (%s) embeds '%s' discontinuously: the prefix of %d characters (%d"
                  + " tokens) has cosine %.4f to the one %d characters shorter, below %.2f. This"
                  + " Ollama evaluates long inputs in several batches, which a bidirectional"
                  + " encoder cannot; every chunk beyond %d tokens gets a wrong vector, so no run"
                  + " on it is a measurement (ADR-0012, Nachtrag Ollama-Fassung).",
              EvalOllamaEndpoint.describeImageOrEndpoint(),
              model,
              worstAfter.chars(),
              worstAfter.tokens(),
              worst,
              PREFIX_STEP_CHARS,
              MIN_NEIGHBOUR_COSINE,
              BATCH_BOUNDARY_TOKENS));
    }
    return worst;
  }

  static double cosine(double[] a, double[] b) {
    if (a.length != b.length) {
      throw new IllegalArgumentException(
          "Vectors of different dimension: " + a.length + " and " + b.length);
    }
    double dot = 0;
    double normA = 0;
    double normB = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      normA += a[i] * a[i];
      normB += b[i] * b[i];
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record EmbedResponse(
      List<double[]> embeddings, @JsonProperty("prompt_eval_count") Integer promptEvalCount) {}

  private static Probe embed(
      HttpClient client, String endpoint, String model, int chars, String input)
      throws IOException, InterruptedException {
    JsonMapper mapper = JsonMapper.builder().build();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint + "/api/embed"))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    mapper.writeValueAsString(Map.of("model", model, "input", input))))
            .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "POST /api/embed on the eval Ollama failed with HTTP "
              + response.statusCode()
              + ": "
              + response.body());
    }
    EmbedResponse body = mapper.readValue(response.body(), EmbedResponse.class);
    if (body.embeddings() == null
        || body.embeddings().size() != 1
        || body.promptEvalCount() == null) {
      throw new IllegalStateException(
          "POST /api/embed returned no single embedding with a token count: " + response.body());
    }
    return new Probe(chars, body.promptEvalCount(), body.embeddings().getFirst());
  }
}
