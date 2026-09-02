package io.opaa.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The rerank model role's configuration - an Ebene-2 installation decision in the sense of
 * docs/features/hybrid-retrieval.md#konfigurations-ebenenmodell, sitting on the same level as the
 * chat and embedding roles: endpoint, model identifier, access key.
 *
 * <p><b>{@link #enabled} is deliberately separate from the endpoint fields.</b> Reranking being off
 * must be an operator's statement ("we switched it off"), never an indistinguishable side effect of
 * a missing configuration line - so the switch expresses the intent and the endpoint fields express
 * the how (docs/features/hybrid-retrieval.md, "'Aus' muss eine Aussage sein, kein Zustand"). The
 * contradiction of a set switch with an unbound or unreachable role is reported rather than
 * silently resolved; see {@link RerankModelRole}.
 *
 * @param enabled the explicit switch, {@code OPAA_RERANK_ENABLED}. Default {@code false}: this
 *     project ships reranking as an operator-activated option until a benchmark on its own corpora
 *     shows a gain without regression elsewhere (docs/features/hybrid-retrieval.md, "Die Lehre aus
 *     MMR").
 * @param baseUrl base address of the rerank endpoint, without the {@code /rerank} path segment
 *     ({@code http://localhost:8081/v1}). Blank means the role is unbound.
 * @param model the model identifier sent with every request; blank means the role is unbound.
 * @param apiKey optional bearer token. Write-only in every sense that matters: it is never logged
 *     and never part of {@link RerankRoleStatus}, not even truncated.
 * @param timeout per-request budget for one rerank call. Default 10s: long enough for a CPU
 *     reranker over the candidate window, short enough that an unresponsive endpoint degrades the
 *     query instead of hanging it.
 */
@ConfigurationProperties(prefix = "opaa.rerank")
public record RerankProperties(
    @DefaultValue("false") boolean enabled,
    String baseUrl,
    String model,
    String apiKey,
    @DefaultValue("10s") Duration timeout) {

  public RerankProperties {
    baseUrl = baseUrl == null ? "" : baseUrl.strip();
    model = model == null ? "" : model.strip();
    apiKey = apiKey == null ? "" : apiKey.strip();
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      timeout = Duration.ofSeconds(10);
    }
  }

  /** Whether both endpoint fields are present - the role is bound to a model. */
  public boolean bound() {
    return !baseUrl.isEmpty() && !model.isEmpty();
  }

  /**
   * A description of the configuration that is safe to log, to store and to hand to any caller:
   * endpoint and model, never the key.
   */
  public String describeWithoutSecrets() {
    return bound() ? model + " @ " + baseUrl : "(keine Endpunktangaben)";
  }

  /**
   * Overridden because a record's generated {@code toString} would print {@link #apiKey} verbatim -
   * one accidental {@code log.debug(properties)} away from the key standing in a log file.
   */
  @Override
  public String toString() {
    return "RerankProperties[enabled="
        + enabled
        + ", baseUrl="
        + baseUrl
        + ", model="
        + model
        + ", apiKey=***"
        + ", timeout="
        + timeout
        + "]";
  }
}
