package io.opaa.chat;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily run of the automatic chat cleanup at 03:50 server time, off business hours. A single
 * instance runs it (ADR-0021), so no distributed lock is needed.
 */
@Component
public class ChatAutoCleanupScheduler {

  private final ChatAutoCleanupService cleanupService;

  public ChatAutoCleanupScheduler(ChatAutoCleanupService cleanupService) {
    this.cleanupService = cleanupService;
  }

  @Scheduled(cron = "0 50 3 * * *")
  public void runDaily() {
    cleanupService.runOnce(Instant.now());
  }
}
