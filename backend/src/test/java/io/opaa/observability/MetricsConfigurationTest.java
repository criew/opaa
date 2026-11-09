package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** The emergency brake on tags whose values an administrator chooses, such as the model name. */
class MetricsConfigurationTest {

  @Test
  void aModelTagStopsAddingSeriesAtItsLimit() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    registry.config().meterFilter(MetricsConfiguration.limitedTagValues(3));

    for (int model = 0; model < 10; model++) {
      Counter.builder("gen_ai.client.token.usage")
          .tag("gen_ai.request.model", "model-" + model)
          .tag("gen_ai.token.type", "input")
          .register(registry)
          .increment();
    }

    assertThat(registry.find("gen_ai.client.token.usage").counters()).hasSize(3);
  }

  @Test
  void aMeterWithoutALimitedTagIsUntouched() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    registry.config().meterFilter(MetricsConfiguration.limitedTagValues(3));

    for (int status = 0; status < 10; status++) {
      Counter.builder("opaa.query.count").tag("status", "s" + status).register(registry);
    }

    assertThat(registry.find("opaa.query.count").counters()).hasSize(10);
  }
}
