package io.opaa.indexing.maintenance;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.opaa.observability.PolledGauges;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The queues of the source events and of the scans waiting for text recognition as {@code
 * opaa.queue.depth} and {@code opaa.queue.oldest.age} (ADR-0071), with {@code queue} {@code
 * source_events} or {@code ocr_backlog}; installation-wide, so a dashboard takes {@code max} over
 * the pods. The scans are {@code NaN} while text recognition is off: they wait, but nothing works
 * on them.
 */
@Configuration(proxyBeanMethods = false)
public class BacklogMetricsConfiguration {

  /** Batches of change events not yet drained, waiting or claimed. */
  static final String SOURCE_EVENTS_DEPTH_SQL = "SELECT count(*) FROM source_event_batches";

  static final String SOURCE_EVENTS_OLDEST_AGE_SQL =
      "SELECT coalesce(extract(epoch FROM clock_timestamp() - min(created_at)), 0)"
          + " FROM source_event_batches";

  @Bean
  PolledGauges backlogGauges(
      DataSource dataSource,
      MeterRegistry registry,
      ObjectProvider<TextRecognitionBacklog> textRecognition) {
    JdbcTemplate jdbc = PolledGauges.reads(dataSource);
    Tags sourceEvents = Tags.of("queue", "source_events");
    Tags ocrBacklog = Tags.of("queue", "ocr_backlog");
    return new PolledGauges(registry, Duration.ofMinutes(1))
        .gauge(
            "opaa.queue.depth",
            sourceEvents,
            null,
            "Entries waiting in the queue",
            PolledGauges.sql(jdbc, SOURCE_EVENTS_DEPTH_SQL))
        .gauge(
            "opaa.queue.oldest.age",
            sourceEvents,
            "seconds",
            "Time since the oldest waiting entry was queued, 0 for an empty queue",
            PolledGauges.sql(jdbc, SOURCE_EVENTS_OLDEST_AGE_SQL))
        .gauge(
            "opaa.queue.depth",
            ocrBacklog,
            null,
            "Entries waiting in the queue",
            () -> scans(textRecognition, true))
        .gauge(
            "opaa.queue.oldest.age",
            ocrBacklog,
            "seconds",
            "Time since the oldest waiting entry was queued, 0 for an empty queue",
            () -> scans(textRecognition, false));
  }

  /** The re-indexing worker as {@code executor_*} with {@code name="embedding-reindex"}. */
  @Bean
  MeterBinder embeddingReindexExecutorMetrics(ObjectProvider<EmbeddingReindexService> reindex) {
    return registry ->
        reindex.ifAvailable(
            service ->
                new ExecutorServiceMetrics(service.executor(), "embedding-reindex", Tags.empty())
                    .bindTo(registry));
  }

  private static double scans(ObjectProvider<TextRecognitionBacklog> backlog, boolean depth) {
    TextRecognitionBacklog available = backlog.getIfAvailable();
    if (available == null) {
      return Double.NaN;
    }
    return available
        .backlog()
        .map(scans -> depth ? (double) scans.waiting() : scans.oldestAgeSeconds())
        .orElse(Double.NaN);
  }
}
