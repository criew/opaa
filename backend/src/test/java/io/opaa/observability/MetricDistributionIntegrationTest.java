package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.observation.ObservationRegistry;
import io.opaa.test.OpaaIntegrationTest;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.support.DefaultScheduledTaskObservationConvention;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.scheduling.support.ScheduledTaskObservationDocumentation;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What {@code /actuator/prometheus} carries for every meter: the latency timers the SLOs are read
 * from publish histogram buckets, every series names the application and the host it ran on, and
 * the build of the instance is a series of its own.
 */
@OpaaIntegrationTest
class MetricDistributionIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObservationRegistry observationRegistry;
  @Autowired private QueryMetrics queryMetrics;
  @Autowired private IndexingMetrics indexingMetrics;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http.server.requests",
        "opaa.query.duration",
        "opaa.ocr.duration",
        "tasks.scheduled.execution"
      })
  void theLatencyTimersPublishHistogramBuckets(String timer) throws Exception {
    record(timer);

    String prefix = timer.replace('.', '_') + "_seconds_bucket{";
    List<String> buckets = scrape().lines().filter(line -> line.startsWith(prefix)).toList();

    assertThat(buckets)
        .as("buckets of %s", timer)
        .hasSizeGreaterThan(3)
        .anyMatch(line -> line.contains("le=\"+Inf\""));
  }

  /**
   * One sample of {@code timer} with the tag keys production gives it, since Prometheus drops a
   * second tag set under the same name: through the production code for the HTTP, query and OCR
   * timers, and for {@code tasks.scheduled.execution} by observing a method by hand with the
   * observation and convention the scheduler uses, as no scheduled job of the context is due.
   * {@code gen_ai.client.operation} is covered by {@code SpringAiMetricsIntegrationTest}.
   */
  private void record(String timer) throws Exception {
    switch (timer) {
      case "http.server.requests" -> scrape();
      case "opaa.query.duration" -> queryMetrics.queryTimer().record(Duration.ofMillis(300));
      case "opaa.ocr.duration" ->
          indexingMetrics.recordTextRecognition(
              "recognized", "distribution-test", 0, Duration.ofSeconds(2));
      case "tasks.scheduled.execution" -> {
        Method method = MetricDistributionIntegrationTest.class.getDeclaredMethod("scheduledTick");
        ScheduledTaskObservationContext context = new ScheduledTaskObservationContext(this, method);
        ScheduledTaskObservationDocumentation.TASKS_SCHEDULED_EXECUTION
            .observation(
                null,
                new DefaultScheduledTaskObservationConvention(),
                () -> context,
                observationRegistry)
            .observe(this::scheduledTick);
      }
      default -> throw new IllegalArgumentException(timer);
    }
  }

  private void scheduledTick() {}

  @Test
  void theLongTaskTimersBesideTheLatencyTimersPublishNoBuckets() throws Exception {
    record("http.server.requests");
    record("tasks.scheduled.execution");

    String scrape = scrape();
    assertThat(scrape).contains("http_server_requests_active_seconds");
    assertThat(scrape.lines().filter(line -> line.contains("_active_seconds_bucket{"))).isEmpty();
  }

  @Test
  void everySeriesNamesTheApplicationAndTheHost() throws Exception {
    List<String> series =
        scrape().lines().filter(line -> line.startsWith("jvm_memory_used_bytes{")).toList();

    assertThat(series)
        .isNotEmpty()
        .allMatch(line -> line.contains("application=\"opaa\""))
        .allMatch(line -> line.matches(".*host=\"[^\"]+\".*"));
  }

  @Test
  void theBuildIsASeriesWithVersionAndCommit() throws Exception {
    assertThat(scrape().lines().filter(line -> line.startsWith("opaa_build_info{")))
        .singleElement()
        .satisfies(
            line ->
                assertThat(line)
                    .containsPattern("version=\"[^\"]+\"")
                    .containsPattern("commit=\"[^\"]+\"")
                    .containsPattern(" 1(\\.0)?$"));
  }

  private String scrape() throws Exception {
    return mockMvc
        .perform(get("/actuator/prometheus"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }
}
