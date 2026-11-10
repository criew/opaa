package io.opaa.query.runlog;

import io.opaa.instance.ScheduledRunLocks;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily, without condition: creates the coming day partitions of the step log and the sandbox log
 * and drops the ones past their retention period. Reads no switch - the deletion is not
 * configurable, only its period ({@link AgentRunLogRetentionService}). One instance runs it under
 * its lock (ADR-0053).
 */
@Component
public class AgentRunLogMaintenance {

  private static final Logger log = LoggerFactory.getLogger(AgentRunLogMaintenance.class);

  private final AgentRunLogRetentionSettingsRepository repository;

  AgentRunLogMaintenance(AgentRunLogRetentionSettingsRepository repository) {
    this.repository = repository;
  }

  @Scheduled(cron = "0 20 2 * * *")
  @SchedulerLock(
      name = "agent-run-log-retention",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_DAILY,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_NIGHTLY)
  @Transactional
  public void maintain() {
    List<String> dropped = repository.maintainPartitions();
    if (!dropped.isEmpty()) {
      log.info("Agent run log retention: dropped partitions: {}", dropped);
    }
  }
}
