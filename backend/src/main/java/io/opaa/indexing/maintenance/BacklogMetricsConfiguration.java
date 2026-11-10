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
 * The queues of the source events, of the indexing runs and of the scans waiting for text
 * recognition as {@code opaa.queue.depth} and {@code opaa.queue.oldest.age} (ADR-0071), with {@code
 * queue} {@code source_events}, {@code indexing} or {@code ocr_backlog}; installation-wide, so a
 * dashboard takes {@code max} over the pods. The scans are {@code NaN} while text recognition is
 * off: they wait, but nothing works on them.
 */
@Configuration(proxyBeanMethods = false)
public class BacklogMetricsConfiguration {

  /** Batches of change events not yet drained, waiting or claimed; their number and oldest age. */
  static final String SOURCE_EVENTS_SQL =
      "SELECT count(*), coalesce(extract(epoch FROM clock_timestamp() - min(created_at)), 0)"
          + " FROM source_event_batches";

  /**
   * Runs accepted and not yet finished, waiting for a thread of a pool or under way: a run is
   * stamped when it is accepted, before a pool picks it up, and has no state of its own for
   * waiting.
   */
  static final String INDEXING_SQL =
      "SELECT count(*), coalesce(extract(epoch FROM clock_timestamp() - min(started_at)), 0)"
          + " FROM indexing_jobs WHERE status = 'RUNNING'";

  private static final String DEPTH = "opaa.queue.depth";
  private static final String DEPTH_DESCRIPTION = "Entries waiting in the queue";
  private static final String AGE = "opaa.queue.oldest.age";
  private static final String AGE_DESCRIPTION =
      "Time since the oldest waiting entry was queued, 0 for an empty queue";

  @Bean
  PolledGauges backlogGauges(
      DataSource dataSource,
      MeterRegistry registry,
      ObjectProvider<TextRecognitionBacklog> textRecognition) {
    JdbcTemplate jdbc = PolledGauges.reads(dataSource);
    return new PolledGauges(registry, Duration.ofMinutes(1))
        .gauges(PolledGauges.sqlRow(jdbc, SOURCE_EVENTS_SQL), queue("source_events"))
        .gauges(PolledGauges.sqlRow(jdbc, INDEXING_SQL), queue("indexing"))
        .gauges(() -> scans(textRecognition), queue("ocr_backlog"));
  }

  /** Depth and oldest age of one queue, filled from one read. */
  static PolledGauges.Spec[] queue(String name) {
    Tags tags = Tags.of("queue", name);
    return new PolledGauges.Spec[] {
      new PolledGauges.Spec(DEPTH, tags, null, DEPTH_DESCRIPTION),
      new PolledGauges.Spec(AGE, tags, "seconds", AGE_DESCRIPTION)
    };
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

  private static double[] scans(ObjectProvider<TextRecognitionBacklog> backlog) {
    TextRecognitionBacklog available = backlog.getIfAvailable();
    if (available == null) {
      return new double[] {Double.NaN, Double.NaN};
    }
    return available
        .backlog()
        .map(scans -> new double[] {scans.waiting(), scans.oldestAgeSeconds()})
        .orElse(new double[] {Double.NaN, Double.NaN});
  }
}
