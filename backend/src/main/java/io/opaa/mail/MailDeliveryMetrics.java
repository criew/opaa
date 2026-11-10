package io.opaa.mail;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.opaa.observability.PolledGauges;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The outcome of every delivery attempt of the mail outbox as {@code opaa.mail.sent} with {@code
 * result} ({@code sent}, {@code retry}, {@code failed} for a mail dropped for good), and the outbox
 * as the queue {@code mail_outbox} (ADR-0071). No recipient, template or address in any series.
 */
final class MailDeliveryMetrics {

  static final String SENT = "opaa.mail.sent";

  /** Rows waiting in the outbox, retries included. */
  static final String DEPTH_SQL = "SELECT count(*) FROM mail_outbox";

  /** Time since the oldest waiting row was queued, 0 for an empty outbox. */
  static final String OLDEST_AGE_SQL =
      "SELECT coalesce(extract(epoch FROM clock_timestamp() - min(created_at)), 0) FROM mail_outbox";

  private final Counter sent;
  private final Counter retry;
  private final Counter failed;

  MailDeliveryMetrics(MeterRegistry registry) {
    this.sent = counter(registry, "sent");
    this.retry = counter(registry, "retry");
    this.failed = counter(registry, "failed");
  }

  private static Counter counter(MeterRegistry registry, String result) {
    return Counter.builder(SENT)
        .tag("result", result)
        .description("Delivery attempts of the mail outbox by outcome")
        .register(registry);
  }

  void sent() {
    sent.increment();
  }

  void retry() {
    retry.increment();
  }

  void failed() {
    failed.increment();
  }

  /** Depth and age of the outbox as {@code opaa.queue.*} with {@code queue="mail_outbox"}. */
  static PolledGauges queueGauges(DataSource dataSource, MeterRegistry registry) {
    JdbcTemplate jdbc = PolledGauges.reads(dataSource);
    Tags queue = Tags.of("queue", "mail_outbox");
    return new PolledGauges(registry, Duration.ofSeconds(30))
        .gauge(
            "opaa.queue.depth",
            queue,
            null,
            "Entries waiting in the queue",
            PolledGauges.sql(jdbc, DEPTH_SQL))
        .gauge(
            "opaa.queue.oldest.age",
            queue,
            "seconds",
            "Time since the oldest waiting entry was queued, 0 for an empty queue",
            PolledGauges.sql(jdbc, OLDEST_AGE_SQL));
  }
}
