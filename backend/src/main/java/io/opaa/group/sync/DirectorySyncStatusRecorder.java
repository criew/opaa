package io.opaa.group.sync;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the outcome of a directory synchronisation run in its own transaction, independent of
 * the caller's. A separate bean rather than a private method on {@link DirectorySyncPlanExecutor}
 * or {@link DirectorySyncService} because {@code REQUIRES_NEW} on a method only takes effect
 * through Spring's proxy - a self-invoked private method on the same instance would silently run in
 * whatever transaction (or lack of one) is already active, which is exactly the bug review of PR
 * #297 found: under {@link DirectorySyncService#dryRun}'s read-only transaction, Hibernate's {@code
 * FlushMode.MANUAL} silently dropped the status insert. With this as a dedicated bean, the write
 * commits (or rolls back) on its own regardless of what the caller's transaction is doing, so "the
 * directory being unreachable is durably reported" holds for every entry point, including a dry
 * run.
 */
@Service
public class DirectorySyncStatusRecorder {

  private final DirectorySyncStatusRepository statusRepository;

  public DirectorySyncStatusRecorder(DirectorySyncStatusRepository statusRepository) {
    this.statusRepository = statusRepository;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(
      UUID organizationId,
      Instant runAt,
      DirectorySyncOutcome outcome,
      String message,
      double changedFraction) {
    DirectorySyncStatus status =
        statusRepository
            .findByOrganizationId(organizationId)
            .orElseGet(() -> new DirectorySyncStatus(organizationId));
    status.recordRun(runAt, outcome, message, changedFraction);
    statusRepository.save(status);
  }
}
