package io.opaa.audit;

import io.opaa.instance.ScheduledRunLocks;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The tick of {@link AuditChainService}: every ten minutes on one instance at a time (ADR-0053,
 * Entscheidung 7). A short database run, so it stays on the shared scheduler thread; what a run
 * writes stands behind the primary keys of checkpoint and seal, not behind this lock.
 */
@Component
public class AuditChainScheduler {

  private final AuditChainService service;

  AuditChainScheduler(AuditChainService service) {
    this.service = service;
  }

  @Scheduled(cron = "0 */10 * * * *")
  @SchedulerLock(
      name = "audit-chain",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_TEN_MINUTES,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_SHORT)
  public void chain() {
    service.run();
  }
}
