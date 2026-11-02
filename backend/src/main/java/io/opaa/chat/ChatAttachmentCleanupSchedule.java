package io.opaa.chat;

import io.opaa.instance.ScheduledRunLocks;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * The hourly tick of {@link ChatAttachmentCleanup}, on a scheduler of its own: removing originals
 * walks the upload storage, which may be an object store. One instance runs it per hour (ADR-0053).
 * Runs with the chat-upload feature switched off as well: the attachments stored before still end
 * with their chats and their retention.
 */
@Component
public class ChatAttachmentCleanupSchedule {

  static final String SCHEDULER = "chatAttachmentCleanupScheduler";

  private final ChatAttachmentCleanup cleanup;

  public ChatAttachmentCleanupSchedule(ChatAttachmentCleanup cleanup) {
    this.cleanup = cleanup;
  }

  @Bean(name = SCHEDULER, destroyMethod = "shutdown")
  static TaskScheduler chatAttachmentCleanupScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("chat-attachment-cleanup-");
    scheduler.setDaemon(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    return scheduler;
  }

  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M", scheduler = SCHEDULER)
  @SchedulerLock(
      name = "chat-attachment-cleanup",
      lockAtLeastFor = ScheduledRunLocks.AT_LEAST_FOR_HOURLY,
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_SHORT)
  public void hourly() {
    cleanup.runOnce();
  }
}
