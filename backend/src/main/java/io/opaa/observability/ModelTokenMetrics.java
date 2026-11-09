package io.opaa.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@code opaa.model.tokens}: the tokens of every chat model call by {@link ModelCallPurpose},
 * {@code token_type} ({@code input}, {@code output}) and {@code model}, the identifier configured
 * in the model administration - never the name a response reports. The purpose is what the {@code
 * gen_ai.*} metrics of Spring AI cannot tell (ADR-0071, Entscheidung 3). The values of {@code
 * model} are limited by {@link MetricsConfiguration}.
 */
public final class ModelTokenMetrics {

  static final String NAME = "opaa.model.tokens";

  private static final String UNKNOWN_MODEL = "unknown";

  private ModelTokenMetrics() {}

  /** Counts one call's tokens; an absent or non-positive count records nothing. */
  public static void record(
      MeterRegistry registry,
      ModelCallPurpose purpose,
      String model,
      Integer inputTokens,
      Integer outputTokens) {
    String shownModel = model == null || model.isBlank() ? UNKNOWN_MODEL : model;
    increment(registry, purpose, shownModel, "input", inputTokens);
    increment(registry, purpose, shownModel, "output", outputTokens);
  }

  private static void increment(
      MeterRegistry registry,
      ModelCallPurpose purpose,
      String model,
      String tokenType,
      Integer tokens) {
    if (tokens == null || tokens <= 0) {
      return;
    }
    Counter.builder(NAME)
        .tag("purpose", purpose.tag())
        .tag("token_type", tokenType)
        .tag("model", model)
        .description("Tokens of the chat model calls by purpose")
        .register(registry)
        .increment(tokens);
  }
}
