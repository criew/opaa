package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** {@code opaa.model.tokens} by purpose, token type and configured model (ADR-0071). */
class ModelTokenMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  void aCallCountsItsInputAndOutputTokensUnderPurposeAndModel() {
    ModelTokenMetrics.record(registry, ModelCallPurpose.DECOMPOSE, "llama-3.3-70b", 400, 25);
    ModelTokenMetrics.record(registry, ModelCallPurpose.DECOMPOSE, "llama-3.3-70b", 100, 5);

    assertThat(tokens("decompose", "input", "llama-3.3-70b")).isEqualTo(500);
    assertThat(tokens("decompose", "output", "llama-3.3-70b")).isEqualTo(30);
  }

  @Test
  void everyPurposeHasItsTagValue() {
    for (ModelCallPurpose purpose : ModelCallPurpose.values()) {
      ModelTokenMetrics.record(registry, purpose, "m", 1, 1);
    }

    assertThat(
            registry.find(ModelTokenMetrics.NAME).counters().stream()
                .map(counter -> counter.getId().getTag("purpose"))
                .distinct())
        .containsExactlyInAnyOrder("answer", "decompose", "title", "note", "metadata", "agent");
  }

  @Test
  void anAbsentCountRecordsNothingAndAnAbsentModelIsUnknown() {
    ModelTokenMetrics.record(registry, ModelCallPurpose.TITLE, null, 12, null);
    ModelTokenMetrics.record(registry, ModelCallPurpose.TITLE, "m", 0, null);

    assertThat(tokens("title", "input", "unknown")).isEqualTo(12);
    assertThat(registry.find(ModelTokenMetrics.NAME).tag("token_type", "output").counters())
        .isEmpty();
    assertThat(registry.find(ModelTokenMetrics.NAME).tag("model", "m").counters()).isEmpty();
  }

  @Test
  void theModelTagIsLimitedLikeTheModelTagsOfSpringAi() {
    registry.config().meterFilter(MetricsConfiguration.limitedTagValues(2));

    ModelTokenMetrics.record(registry, ModelCallPurpose.ANSWER, "a", 1, null);
    ModelTokenMetrics.record(registry, ModelCallPurpose.ANSWER, "b", 1, null);
    ModelTokenMetrics.record(registry, ModelCallPurpose.ANSWER, "c", 1, null);

    assertThat(registry.find(ModelTokenMetrics.NAME).counters()).hasSize(2);
  }

  private double tokens(String purpose, String tokenType, String model) {
    return registry
        .get(ModelTokenMetrics.NAME)
        .tags("purpose", purpose, "token_type", tokenType, "model", model)
        .counter()
        .count();
  }
}
