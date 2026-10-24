package io.opaa.chat;

import io.opaa.instance.ScheduledRunLocks;
import java.time.Clock;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily run of the automatic chat cleanup at 03:50 server time, off business hours. The
 * scheduled-run lock lets one instance run it per night, so two deletion batches of different
 * instances never lock against each other.
 */
@Component
public class ChatAutoCleanupScheduler {

  private final ChatAutoCleanupService cleanupService;
  private final Clock clock;

  public ChatAutoCleanupScheduler(ChatAutoCleanupService cleanupService, Clock clock) {
    this.cleanupService = cleanupService;
    this.clock = clock;
  }

  @Scheduled(cron = "0 50 3 * * *")
  @SchedulerLock(
      name = "chat-auto-cleanup",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_DAILY,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_NIGHTLY)
  public void runDaily() {
    cleanupService.runOnce(Instant.now(clock));
  }
}
