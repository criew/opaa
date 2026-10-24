package io.opaa.audit;

import io.opaa.instance.ScheduledRunLocks;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * The tick of {@link SiemExportService}, only while {@code opaa.siem-export.enabled}: every 30
 * seconds on one instance at a time (ADR-0053, Entscheidung 7), on a scheduler of its own because a
 * delivery waits on the network.
 */
@Component
@ConditionalOnProperty(prefix = "opaa.siem-export", name = "enabled", havingValue = "true")
public class SiemExportSchedule {

  static final String SCHEDULER = "siemExportScheduler";

  private final SiemExportService service;

  SiemExportSchedule(SiemExportService service) {
    this.service = service;
  }

  @Bean(name = SCHEDULER, destroyMethod = "shutdown")
  static TaskScheduler siemExportScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("siem-export-");
    scheduler.setDaemon(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    return scheduler;
  }

  @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S", scheduler = SCHEDULER)
  @SchedulerLock(
      name = "siem-export",
      lockAtLeastFor = "PT25S",
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_SHORT)
  public void export() {
    service.run();
  }
}
