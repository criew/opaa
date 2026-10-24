package io.opaa.instance;

import java.util.UUID;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The lock every {@code @Scheduled} run takes before it starts (ADR-0053, Entscheidung 7): a row of
 * the {@code shedlock} table per run, compared by the database clock, so that one due time of a run
 * executes on one instance only. The lock is taken on the method proxy, so a direct call through
 * the bean is locked too; a run that finds the lock held is skipped, not queued.
 *
 * <p>The lock alone does not promise "exactly once": a run longer than its {@code lockAtMostFor}
 * can start a second time. Every effect a run has outside its own process therefore also stands
 * behind a marker committed in the database. The lock periods and their reasons: {@link
 * ScheduledRunLocks}.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = ScheduledRunLocks.DEFAULT_AT_MOST_FOR)
public class ScheduledRunLockConfiguration {

  /** The lock table of the foundation changelog. */
  static final String TABLE = "shedlock";

  @Bean
  LockProvider scheduledRunLockProvider(
      DataSource dataSource, PlatformTransactionManager transactionManager) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(dataSource))
            .withTransactionManager(transactionManager)
            .withTableName(TABLE)
            .withLockedByValue(lockedBy())
            .usingDbTime()
            .build());
  }

  /**
   * The host name plus a value of this process: two processes on one host must not release each
   * other's locks, which ShedLock matches by this value.
   */
  static String lockedBy() {
    return InstanceConfiguration.hostName() + "/" + UUID.randomUUID();
  }
}
