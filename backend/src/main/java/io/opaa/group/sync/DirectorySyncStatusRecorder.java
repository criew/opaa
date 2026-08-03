package io.opaa.group.sync;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the outcome of a directory synchronisation run. Called only from {@link
 * DirectorySyncService}, which is itself not transactional and calls this only after {@link
 * DirectorySyncPlanExecutor#planAndApply}/{@code planOnly} has already returned successfully - so
 * there is never an ambient transaction to consider here, and a failed apply (its transaction
 * rolled back) never reaches this class at all. An earlier version of this class ran under {@code
 * REQUIRES_NEW} to escape the caller's transaction; that requirement disappeared along with the
 * caller ever having one - see {@link DirectorySyncPlanExecutor}'s class javadoc for the defect
 * that motivated moving the call here (review of PR #297: a REQUIRES_NEW status write could commit
 * before the surrounding apply transaction later rolled back, durably recording {@code APPLIED} for
 * a run that never actually applied).
 */
@Service
public class DirectorySyncStatusRecorder {

  private final DirectorySyncStatusRepository statusRepository;

  public DirectorySyncStatusRecorder(DirectorySyncStatusRepository statusRepository) {
    this.statusRepository = statusRepository;
  }

  @Transactional
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
