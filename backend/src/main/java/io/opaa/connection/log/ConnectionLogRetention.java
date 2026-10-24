package io.opaa.connection.log;

import io.opaa.instance.ScheduledRunLocks;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes the connection log's expired monthly partitions, daily and unconditionally: the period is
 * configurable (6-24 months), the deletion is not. Calls only the database function, which is the
 * one way anything leaves the log; a run is idempotent.
 */
@Component
public class ConnectionLogRetention {

  private static final Logger log = LoggerFactory.getLogger(ConnectionLogRetention.class);

  private final ConnectionLogRepository repository;

  ConnectionLogRetention(ConnectionLogRepository repository) {
    this.repository = repository;
  }

  @Scheduled(cron = "0 45 3 * * *")
  @Transactional
  @SchedulerLock(
      name = "connection-log-retention",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_DAILY,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_NIGHTLY)
  public void deleteExpiredPartitions() {
    List<String> dropped = repository.deleteExpiredPartitions();
    if (!dropped.isEmpty()) {
      log.info("Connection log retention: dropped partitions: {}", dropped);
    }
  }
}
