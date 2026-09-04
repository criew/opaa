package io.opaa.eval;

import io.opaa.llm.ActiveChatModelResolver;
import java.math.BigDecimal;
import java.util.UUID;
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

  /** Temperatur 0: the least nondeterministic setting this endpoint offers (issue #1085). */
  private static final BigDecimal TEMPERATURE = new BigDecimal("0.00");

  /**
   * A hard latency bound, not a production-equivalent value: a decomposition answer is at most
   * {@code opaa.query.max-sub-queries} short lines, so a model that starts writing prose instead
   * costs seconds per query rather than minutes, three times over per Mehrfachlauf variant.
   */
  private static final int MAX_TOKENS = 512;

  private EvalChatModel() {}

  /**
   * Proves the installed model actually answers before a decomposing run measures anything.
   *
   * <p>Not redundant with the prerequisite checks: {@code QueryDecompositionService#decompose}
   * swallows every LLM failure and returns an empty list, which {@code QueryService} treats as
   * "retrieve for the single original query". A run against an unreachable endpoint would therefore
   * report "with decomposition" while measuring without it — the exact silent degradation the
   * prerequisites exist to prevent, one layer deeper than "is a model configured at all".
   */
  static void requireUsable(ActiveChatModelResolver resolver) {
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
    if (reply == null || reply.isBlank()) {
      throw new IllegalStateException(
          "The eval chat model '"
              + MODEL
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
    jdbcTemplate.update("DELETE FROM llm_models");
    jdbcTemplate.update(
        "INSERT INTO llm_models (id, display_name, base_url, model_identifier, temperature,"
            + " max_tokens, api_key_ciphertext, active, created_at, updated_at) VALUES (?, ?, ?, ?,"
            + " ?, ?, NULL, true, now(), now())",
        UUID.randomUUID(),
        "Eval-Chat-Modell",
        ollamaEndpoint + "/v1",
        MODEL,
        TEMPERATURE,
        MAX_TOKENS);
  }
}
