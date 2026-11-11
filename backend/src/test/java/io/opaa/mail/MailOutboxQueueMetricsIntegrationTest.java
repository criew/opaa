package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.observability.PolledGauges;
import io.opaa.test.OpaaIntegrationTest;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Depth and age of the mail outbox as {@code opaa.queue.*} with {@code queue="mail_outbox"}, read
 * by the queries of {@link MailDeliveryMetrics} against the real schema: {@code 0} and {@code 0}
 * for an empty outbox, the number of rows and the age of the oldest for a filled one. Runs against
 * a temporary copy of the table that shadows the shared one within the transaction, so no row of
 * another test is seen or touched.
 */
@OpaaIntegrationTest
class MailOutboxQueueMetricsIntegrationTest {

  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  void anEmptyOutboxIsZeroDeepAndZeroOldAFilledOneShowsItsRowsAndItsOldest() {
    transactionTemplate.executeWithoutResult(
        status -> {
          status.setRollbackOnly();
          jdbcTemplate.execute(
              "CREATE TEMPORARY TABLE mail_outbox (LIKE mail_outbox INCLUDING DEFAULTS)"
                  + " ON COMMIT DROP");
          SimpleMeterRegistry meters = new SimpleMeterRegistry();
          PolledGauges gauges = MailDeliveryMetrics.queueGauges(dataSource, meters);

          gauges.refresh();
          assertThat(depth(meters)).isZero();
          assertThat(oldestAge(meters)).isZero();

          insert("interval '2 hours'");
          insert("interval '5 minutes'");
          gauges.refresh();

          assertThat(depth(meters)).isEqualTo(2.0);
          assertThat(oldestAge(meters)).isBetween(7_200.0, 7_200.0 + 600);
        });
  }

  private void insert(String age) {
    jdbcTemplate.update(
        "INSERT INTO mail_outbox (id, template_key, recipient_ciphertext, subject_ciphertext,"
            + " body_plain_ciphertext, next_attempt_at, created_at) VALUES (gen_random_uuid(),"
            + " 'test', 'x', 'x', 'x', now() + interval '1 day', now() - "
            + age
            + ")");
  }

  private static double depth(SimpleMeterRegistry meters) {
    return meters.get("opaa.queue.depth").tag("queue", "mail_outbox").gauge().value();
  }

  private static double oldestAge(SimpleMeterRegistry meters) {
    return meters.get("opaa.queue.oldest.age").tag("queue", "mail_outbox").gauge().value();
  }
}
