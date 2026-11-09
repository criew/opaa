package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Every meter registered in the running application - eagerly at start-up and by whatever the
 * classes sharing this context have done before - carries none of the {@link ForbiddenMetricTags}.
 * Meters registered only on a rare path are covered by {@link MetricTagSourceTest}.
 */
@OpaaIntegrationTest
class MeterTagCardinalityIntegrationTest {

  @Autowired private MeterRegistry meterRegistry;

  @Test
  void noRegisteredMeterCarriesAForbiddenTag() {
    List<String> offending =
        meterRegistry.getMeters().stream()
            .map(Meter::getId)
            .flatMap(
                id ->
                    id.getTags().stream()
                        .map(Tag::getKey)
                        .filter(key -> ForbiddenMetricTags.isForbidden(id.getName(), key))
                        .map(key -> id.getName() + " [" + key + "]"))
            .distinct()
            .toList();

    assertThat(meterRegistry.getMeters()).isNotEmpty();
    assertThat(offending).isEmpty();
  }
}
