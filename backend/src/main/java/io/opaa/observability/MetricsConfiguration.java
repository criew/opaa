package io.opaa.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Metrics every instance publishes beyond those of its features (ADR-0070): the build it runs as
 * {@code opaa.build.info}, and an emergency brake on tags whose values come from configuration
 * rather than from a fixed set. Histogram buckets and the common tags {@code application} and
 * {@code host} are properties under {@code management.metrics} in {@code application.yml}.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfiguration {

  /** How many values a tag of {@link #LIMITED_TAGS} may take before further series are dropped. */
  static final int MAXIMUM_TAG_VALUES = 20;

  /** Meter name prefix and tag key of every tag whose values an administrator chooses. */
  static final List<List<String>> LIMITED_TAGS =
      List.of(
          List.of("gen_ai.client", "gen_ai.request.model"),
          List.of("gen_ai.client", "gen_ai.response.model"),
          List.of("spring.ai.tool", "spring.ai.tool.definition.name"));

  /** The value of {@code version} or {@code commit} the build does not name. */
  static final String UNKNOWN = "unknown";

  private static final Logger log = LoggerFactory.getLogger(MetricsConfiguration.class);

  @Bean
  MeterFilter limitedTagValues() {
    return limitedTagValues(MAXIMUM_TAG_VALUES);
  }

  /** One filter over all {@link #LIMITED_TAGS}, each allowing {@code maximum} values. */
  static MeterFilter limitedTagValues(int maximum) {
    List<MeterFilter> filters =
        LIMITED_TAGS.stream().map(tag -> limit(tag.get(0), tag.get(1), maximum)).toList();
    return new MeterFilter() {
      @Override
      public MeterFilterReply accept(Meter.Id id) {
        for (MeterFilter filter : filters) {
          MeterFilterReply reply = filter.accept(id);
          if (reply != MeterFilterReply.NEUTRAL) {
            return reply;
          }
        }
        return MeterFilterReply.NEUTRAL;
      }
    };
  }

  private static MeterFilter limit(String prefix, String tagKey, int maximum) {
    AtomicBoolean warned = new AtomicBoolean();
    return MeterFilter.maximumAllowableTags(
        prefix,
        tagKey,
        maximum,
        MeterFilter.deny(
            id -> {
              if (warned.compareAndSet(false, true)) {
                log.warn(
                    "Metric tag {} of {} reached {} values; further series are dropped",
                    tagKey,
                    prefix,
                    maximum);
              }
              return true;
            }));
  }

  /**
   * {@code opaa.build.info}, always 1, with the {@code version} and {@code commit} of this
   * instance's build: during a rolling update two versions show up side by side.
   */
  @Bean
  MeterBinder buildInfoMetric(
      ObjectProvider<BuildProperties> buildProperties, Environment environment) {
    String version =
        buildProperties.stream()
            .map(BuildProperties::getVersion)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(UNKNOWN);
    String commit = environment.getProperty("opaa.build.commit", "").trim();
    String shownCommit = commit.isEmpty() ? UNKNOWN : commit;
    return registry ->
        Gauge.builder("opaa.build.info", () -> 1)
            .tag("version", version)
            .tag("commit", shownCommit)
            .description("The build this instance runs, always 1")
            .register(registry);
  }
}
