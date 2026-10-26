package io.opaa.eval;

import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.llm.ChatResponses;
import io.opaa.llm.ModelEndpointUri;
import io.opaa.security.SettingsEncryptor;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The chat model the pipeline measurement path uses for Teilfragen-Zerlegung (issue #1085,
 * docs/features/retrieval-benchmark.md, "Offene Punkte" 3): a small local Instruct model served by
 * the same Ollama endpoint the embedding model comes from, pinned by tag <b>and</b> content digest
 * like the embedding model (ADR-0011, Entscheidung 4) and run at temperature 0.
 *
 * <p><b>Wired through the production path, not around it.</b> {@code QueryDecompositionService}
 * resolves its {@code ChatClient} from the systemwide active row in {@code llm_models} via {@code
 * ActiveChatModelResolver}; {@link #installAsSystemwideActiveModel} writes exactly such a row, so
 * the harness measures the same resolution, options and HTTP path a real request runs through
 * instead of a harness-only {@code ChatModel} bean.
 *
 * <p>Replaces whatever {@code LlmModelSeeder} put there at context startup. Safe at that point
 * because nothing resolves a chat client before the first decomposing query: {@code
 * ActiveChatModelResolver} builds its client lazily, so no client can have been cached from the
 * seeded row.
 */
final class EvalChatModel {

  static final String MODEL = "qwen2.5:1.5b-instruct";

  // Captured with `ollama pull qwen2.5:1.5b-instruct` against a freshly started ollama/ollama:0.6.5
  // container and read back from GET /api/tags (the "digest" field) on 2026-09-04. A different
  // digest for this exact tag is model drift, not a harness bug — see EvalOllamaModel#ensurePresent
  // and ADR-0011, Entscheidung 4.
  static final String EXPECTED_DIGEST =
      "65ec06548149b04c096a120e4a6da9d4017ea809c91734ea5631e89f96ddc57b";

  /** Temperatur 0: the least nondeterministic setting this endpoint offers. */
  private static final BigDecimal TEMPERATURE = new BigDecimal("0.00");

  /**
   * A hard latency bound, not a production-equivalent value: a decomposition answer is at most
   * {@code opaa.query.max-sub-queries} short lines, so a model that starts writing prose instead
   * costs seconds per query rather than minutes, three times over per Mehrfachlauf variant.
   */
  private static final int MAX_TOKENS = 512;

  /**
   * Base URL and model identifier of an <b>external</b> chat model this run measures with instead
   * of {@link #MODEL} - the OpenAI-compatible endpoint of a production-grade provider, e.g. {@code
   * https://api.anthropic.com/v1} and {@code claude-haiku-4-5}. Both must be set together.
   *
   * <p>Such a run answers a question the pinned model cannot: the Gesprächsnotiz and the
   * Teilfragen-Zerlegung are <em>model output</em>, not retrieval parameters, so a 1.5B stand-in
   * does not measure them on anyone's behalf. It is a Befundlauf, never a baseline - the {@code
   * chatModel} fixed point carries the external identifier, which makes every committed baseline
   * incomparable to it by construction, and the nightly job keeps the pinned model precisely
   * because regression detection needs the determinism it provides.
   */
  private static final String BASE_URL_PROPERTY = "opaa.eval.chatBaseUrl";

  private static final String MODEL_PROPERTY = "opaa.eval.chatModel";

  /**
   * The sampling temperature of an external chat model, for a Befundlauf that has to reproduce the
   * options of an installation. Refused without {@link #BASE_URL_PROPERTY}: the pinned model stays
   * at {@link #TEMPERATURE}, which is what its baseline's determinism rests on.
   */
  private static final String TEMPERATURE_PROPERTY = "opaa.eval.chatTemperature";

  /**
   * The API key of that endpoint, <b>from the environment only</b>: a {@code -D} value is visible
   * in the process list, which is why {@code backend/build.gradle.kts} deliberately omits {@code
   * a rerank key from its passthrough list as well. Optional: an endpoint without
   * authentication, such as an Ollama in the own network, runs without it.
   */
  private static final String API_KEY_ENVIRONMENT_VARIABLE = "OPAA_EVAL_CHAT_API_KEY";

  private EvalChatModel() {}

  /** The external chat model this run was asked to measure with, empty for the pinned one. */
  static Optional<External> external() {
    String baseUrl = System.getProperty(BASE_URL_PROPERTY);
    String model = System.getProperty(MODEL_PROPERTY);
    if (baseUrl == null && model == null) {
      return Optional.empty();
    }
    if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()) {
      throw new IllegalArgumentException(
          BASE_URL_PROPERTY
              + " and "
              + MODEL_PROPERTY
              + " belong together - a run with only one of them would silently measure the pinned "
              + "model while reporting nothing about it.");
    }
    refuseSecretsInAddress(BASE_URL_PROPERTY, baseUrl.strip());
    String apiKey = System.getenv(API_KEY_ENVIRONMENT_VARIABLE);
    return Optional.of(
        new External(
            baseUrl.strip(),
            model.strip(),
            apiKey == null || apiKey.isBlank() ? null : apiKey.strip(),
            requestedTemperature()));
  }

  /**
   * Refuses an address of {@code property} that carries user information or a query: either may
   * hold a key, and the address reaches the model row, the client's log line and the report as it
   * is. A key belongs in {@value #API_KEY_ENVIRONMENT_VARIABLE} only.
   */
  static void refuseSecretsInAddress(String property, String address) {
    if (ModelEndpointUri.containsCredentials(address)
        || address.indexOf('?') >= 0
        || address.indexOf('#') >= 0) {
      throw new IllegalArgumentException(
          property
              + " must not carry user information, a query or a fragment - a key there would"
              + " reach log and report. Pass the key in "
              + API_KEY_ENVIRONMENT_VARIABLE
              + " instead.");
    }
  }

  /**
   * {@code endpoint} as a report may name it: scheme, host, port and path only, so neither user
   * information nor a query parameter - where some providers accept a key - reaches the report.
   */
  static String endpointWithoutSecrets(String endpoint) {
    if (endpoint == null) {
      return null;
    }
    try {
      URI uri = new URI(endpoint.strip());
      if (uri.getHost() == null) {
        return "(Adresse nicht lesbar)";
      }
      return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null)
          .toString();
    } catch (URISyntaxException e) {
      return "(Adresse nicht lesbar)";
    }
  }

  /** {@value #TEMPERATURE_PROPERTY}, or {@link #TEMPERATURE} when unset. */
  static BigDecimal requestedTemperature() {
    String requested = System.getProperty(TEMPERATURE_PROPERTY);
    if (requested == null) {
      return TEMPERATURE;
    }
    BigDecimal temperature;
    try {
      temperature = new BigDecimal(requested.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          TEMPERATURE_PROPERTY + " must be a decimal number, got: " + requested, e);
    }
    if (temperature.signum() < 0 || temperature.compareTo(BigDecimal.TWO) > 0) {
      throw new IllegalArgumentException(
          TEMPERATURE_PROPERTY + " must lie between 0 and 2, got: " + requested);
    }
    return temperature;
  }

  /**
   * Refuses {@value #TEMPERATURE_PROPERTY} for a run with the pinned model, which measures at
   * {@link #TEMPERATURE} only.
   */
  static void refuseTemperatureWithoutExternalModel() {
    if (System.getProperty(TEMPERATURE_PROPERTY) != null) {
      throw new IllegalArgumentException(
          TEMPERATURE_PROPERTY
              + " applies to an external chat model only ("
              + BASE_URL_PROPERTY
              + "). The pinned model measures at temperature "
              + TEMPERATURE
              + ", which its committed baselines depend on.");
    }
  }

  /** The temperature this run's chat model runs at - {@link #TEMPERATURE} for the pinned model. */
  static BigDecimal activeTemperature() {
    return external().map(External::temperature).orElse(TEMPERATURE);
  }

  /** The model identifier this run measures with - the run's {@code chatModel} fixed point. */
  static String activeModelIdentifier() {
    return external().map(External::model).orElse(MODEL);
  }

  /**
   * An external chat model of a Befundlauf. {@code apiKey} is never logged and never reaches a
   * report; only {@code model} does, as the run's fixed point. {@code apiKey} is {@code null} for
   * an endpoint without authentication.
   */
  record External(String baseUrl, String model, String apiKey, BigDecimal temperature) {}

  /**
   * Proves the installed model actually answers before a decomposing run measures anything.
   *
   * <p>Not redundant with the prerequisite checks: {@code QueryDecompositionService#decompose}
   * swallows every LLM failure and returns an empty list, which the pipeline treats as "retrieve
   * for the single original query". A run against an unreachable endpoint would therefore report
   * "with decomposition" while measuring without it — the exact silent degradation the
   * prerequisites exist to prevent, one layer deeper than "is a model configured at all".
   */
  static void requireUsable(ActiveChatModelResolver resolver, Logger log) {
    ChatResponse response =
        resolver
            .resolveChatClient()
            .prompt()
            .user("Antworte mit dem Wort: bereit")
            .call()
            .chatResponse();
    String reply =
        response == null || response.getResult() == null || response.getResult().getOutput() == null
            ? null
            : response.getResult().getOutput().getText();
    // The fixed point carries what was *ordered*; a provider alias can point at different
    // snapshots over time, so the run also states what answered.
    if (response != null) {
      log.info(
          "Eval-Chat-Modell: angefordert '{}' bei Temperatur {}, geantwortet hat '{}'",
          activeModelIdentifier(),
          external().map(External::temperature).orElse(TEMPERATURE),
          ChatResponses.model(response));
    }
    if (reply == null || reply.isBlank()) {
      throw new IllegalStateException(
          "The eval chat model '"
              + activeModelIdentifier()
              + "' returned no usable answer. A decomposing run would silently fall back to "
              + "single-query retrieval and report itself as decomposing — check the Ollama "
              + "endpoint before measuring.");
    }
  }

  /**
   * Makes {@link #MODEL} at {@code ollamaEndpoint} the systemwide active chat model.
   *
   * @param ollamaEndpoint the endpoint without the {@code /v1} suffix — the same base URL the
   *     embedding configuration uses; {@code llm_models} always stores the OpenAI-compatible
   *     endpoint (docs/features/llm-integration.md, "Ein Anbindungsweg, nicht zwei").
   */
  static void installAsSystemwideActiveModel(JdbcTemplate jdbcTemplate, String ollamaEndpoint) {
    refuseTemperatureWithoutExternalModel();
    if (external().isPresent()) {
      throw new IllegalStateException(
          "This harness has no multi-turn path and therefore no use for an external chat model, "
              + "but "
              + BASE_URL_PROPERTY
              + " is set. It would measure a model nothing here reports - remove the property or "
              + "run the verwaltung harness.");
    }
    install(jdbcTemplate, ollamaEndpoint + "/v1", MODEL, null, TEMPERATURE);
  }

  /**
   * The same, for the harness that carries the multi-turn path: installs the external chat model
   * when one was requested, the pinned one otherwise. The key is encrypted with the running
   * application's own {@link SettingsEncryptor}, so the row is exactly what an operator-configured
   * model looks like and {@code ActiveChatModelResolver} needs no special case.
   */
  static void installAsSystemwideActiveModel(
      JdbcTemplate jdbcTemplate, String ollamaEndpoint, SettingsEncryptor settingsEncryptor) {
    Optional<External> external = external();
    if (external.isEmpty()) {
      refuseTemperatureWithoutExternalModel();
      install(jdbcTemplate, ollamaEndpoint + "/v1", MODEL, null, TEMPERATURE);
      return;
    }
    External chatModel = external.get();
    install(
        jdbcTemplate,
        chatModel.baseUrl(),
        chatModel.model(),
        chatModel.apiKey() == null ? null : settingsEncryptor.encrypt(chatModel.apiKey()),
        chatModel.temperature());
  }

  private static void install(
      JdbcTemplate jdbcTemplate,
      String baseUrl,
      String model,
      String apiKeyCiphertext,
      BigDecimal temperature) {
    jdbcTemplate.update("DELETE FROM llm_models");
    jdbcTemplate.update(
        "INSERT INTO llm_models (id, display_name, base_url, model_identifier, temperature,"
            + " max_tokens, api_key_ciphertext, active, created_at, updated_at) VALUES (?, ?, ?, ?,"
            + " ?, ?, ?, true, now(), now())",
        UUID.randomUUID(),
        "Eval-Chat-Modell",
        baseUrl,
        model,
        temperature,
        MAX_TOKENS,
        apiKeyCiphertext);
  }
}
