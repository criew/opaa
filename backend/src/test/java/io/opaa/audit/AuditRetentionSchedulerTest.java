package io.opaa.audit;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * #395 acceptance criteria: "Automatische Löschung nach Ablauf, monatsweise und ohne Zutun". This
 * unit test only proves the scheduled method delegates to {@link AuditRetentionDeletionService};
 * the schedule itself (monthly, {@code @Scheduled(cron = "0 0 3 1 * *")}) and Spring's
 * {@code @EnableScheduling} wiring (see {@code OpaaApplication}) are declarative and not
 * meaningfully unit-testable beyond reading the annotation.
 */
@ExtendWith(MockitoExtension.class)
class AuditRetentionSchedulerTest {

  @Mock private AuditRetentionDeletionService deletionService;

  @Test
  void theScheduledMethodDelegatesToTheDeletionService() {
    AuditRetentionScheduler scheduler = new AuditRetentionScheduler(deletionService);

    scheduler.deleteExpiredAuditLogPartitions();

    verify(deletionService).runOnce();
  }
}
