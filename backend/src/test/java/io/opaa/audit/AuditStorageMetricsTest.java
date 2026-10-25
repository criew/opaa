package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A scrape reads only what {@link AuditStorageMetrics#refresh} read last, and a value that cannot
 * be read is {@code NaN} with one warning per failure, not one per read.
 */
class AuditStorageMetricsTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private Logger logger;

  @BeforeEach
  void captureLog() {
    appender.start();
    logger = (Logger) LoggerFactory.getLogger(AuditStorageMetrics.class);
    logger.addAppender(appender);
  }

  @AfterEach
  void releaseLog() {
    logger.detachAppender(appender);
  }

  @Test
  void aScrapeBeforeTheFirstReadAndAScrapeAfterItNeverQueryTheDatabase() {
    when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(36.0);
    AuditStorageMetrics metrics = new AuditStorageMetrics(jdbc, registry);

    assertThat(gauge("opaa.audit.retention.period")).isNaN();
    verify(jdbc, never()).queryForObject(anyString(), eq(Double.class));

    metrics.refresh();

    assertThat(gauge("opaa.audit.retention.period")).isEqualTo(36.0);
  }

  @Test
  void aScrapeAfterAReadShowsThatReadUntilTheNextOne() {
    when(jdbc.queryForObject(AuditStorageMetrics.RETENTION_MONTHS_SQL, Double.class))
        .thenReturn(36.0)
        .thenReturn(37.0);
    AuditStorageMetrics metrics = new AuditStorageMetrics(jdbc, registry);

    metrics.refresh();

    assertThat(gauge("opaa.audit.retention.period")).isEqualTo(36.0);
    assertThat(gauge("opaa.audit.retention.period")).isEqualTo(36.0);
    metrics.refresh();
    assertThat(gauge("opaa.audit.retention.period")).isEqualTo(37.0);
  }

  @Test
  void theReadsAreBoundedByAQueryTimeoutOfTheirOwn() {
    JdbcTemplate reads = AuditStorageMetrics.reads(mock(DataSource.class));

    assertThat(reads.getQueryTimeout()).isEqualTo(AuditStorageMetrics.QUERY_TIMEOUT_SECONDS);
  }

  @Test
  void aFailedReadIsNanAndWarnsOnceUntilItSucceedsAgain() {
    when(jdbc.queryForObject(AuditStorageMetrics.LAST_RUN_AGE_SQL, Double.class))
        .thenThrow(new DataAccessResourceFailureException("pool exhausted"))
        .thenThrow(new DataAccessResourceFailureException("pool exhausted"))
        .thenReturn(3600.0)
        .thenThrow(new DataAccessResourceFailureException("pool exhausted"));
    AuditStorageMetrics metrics = new AuditStorageMetrics(jdbc, registry);

    metrics.refresh();
    metrics.refresh();

    assertThat(gauge("opaa.audit.retention.last.run.age")).isNaN();
    assertThat(warnings()).isEqualTo(1);

    metrics.refresh();
    assertThat(gauge("opaa.audit.retention.last.run.age")).isEqualTo(3600.0);

    metrics.refresh();
    assertThat(gauge("opaa.audit.retention.last.run.age")).isNaN();
    assertThat(warnings()).isEqualTo(2);
  }

  private long warnings() {
    return appender.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .filter(event -> event.getFormattedMessage().contains("opaa.audit.retention.last.run.age"))
        .count();
  }

  private double gauge(String name) {
    return registry.get(name).gauge().value();
  }
}
