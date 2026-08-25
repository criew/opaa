package io.opaa.group.sync;

import io.opaa.api.types.DirectorySyncOutcome;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

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

  /**
   * Deliberately <b>not</b> {@code @Transactional} (#300). Each {@link
   * DirectorySyncStatusRepository} call below already runs in its own implicit transaction (Spring
   * Data's {@code SimpleJpaRepository} methods are individually {@code @Transactional}), so no
   * explicit demarcation is needed - and one here would actively break {@link
   * #insertOrUpdateOnRaceLost}: inside a shared, still-open transaction the failed insert marks
   * that transaction rollback-only, so the fallback read would run on a poisoned transaction and
   * the caller would still see an exception instead of a recorded status. The same reasoning as
   * {@code UserService#findOrCreateUser} (#293), which handles the identical race on {@code
   * uq_users_subject_issuer}.
   *
   * <p>Note that the alternative of an inner {@code REQUIRES_NEW} transaction for the insert
   * attempt is deliberately <em>not</em> used: that construction has caused a defect twice in this
   * project already (#280's foreign-key violation against an uncommitted {@code users} row, #297's
   * status write committing ahead of an apply that later rolled back), and it would additionally
   * make each caller hold two connections at once - the pool-exhaustion mode found in the review of
   * #299.
   *
   * <p>Two callers updating an <em>existing</em> row concurrently remain last-writer-wins. That is
   * the intended semantics of a table holding "the outcome of the most recent run" and is unchanged
   * by this fix; the unserialised concurrent runs behind it are the known gap documented in {@link
   * DirectorySyncService}'s class javadoc.
   */
  public void record(
      UUID organizationId,
      Instant runAt,
      DirectorySyncOutcome outcome,
      String message,
      double changedFraction) {
    Optional<DirectorySyncStatus> existing = statusRepository.findByOrganizationId(organizationId);
    if (existing.isPresent()) {
      updateExisting(existing.get(), runAt, outcome, message, changedFraction);
      return;
    }
    insertOrUpdateOnRaceLost(organizationId, runAt, outcome, message, changedFraction);
  }

  /**
   * Creates the organization's status row, tolerating the race of two concurrent <em>first</em>
   * runs for the same organization getting past the {@code findByOrganizationId} check in {@link
   * #record} together (#300). Only the first run of an organization can reach this method at all;
   * every later run takes {@link #record}'s update branch.
   *
   * <p>Because {@link #record} is deliberately not {@code @Transactional} (see its javadoc), the
   * {@code saveAndFlush} below runs in its own short-lived, implicit transaction. A {@link
   * DataIntegrityViolationException} on {@code uk_directory_sync_status_organization} rolls back
   * only that insert; nothing else is poisoned by it, so the loser of the race can simply update
   * the row the winner has by now committed instead of surfacing the violation to {@code
   * DirectorySyncService.recordStatusSafely}, where it would be logged and the run's outcome lost.
   * Same fallback-read pattern as {@code UserService#createOrFetchUser} (#293).
   */
  private void insertOrUpdateOnRaceLost(
      UUID organizationId,
      Instant runAt,
      DirectorySyncOutcome outcome,
      String message,
      double changedFraction) {
    DirectorySyncStatus status = new DirectorySyncStatus(organizationId);
    status.recordRun(runAt, outcome, message, changedFraction);
    try {
      // saveAndFlush forces the INSERT to execute (and thus to fail, if it must) here, instead of
      // being deferred to a later flush point where the DataIntegrityViolationException could
      // surface somewhere other than this try block.
      statusRepository.saveAndFlush(status);
    } catch (DataIntegrityViolationException raceLost) {
      DirectorySyncStatus winner =
          statusRepository.findByOrganizationId(organizationId).orElseThrow(() -> raceLost);
      updateExisting(winner, runAt, outcome, message, changedFraction);
    }
  }

  private void updateExisting(
      DirectorySyncStatus status,
      Instant runAt,
      DirectorySyncOutcome outcome,
      String message,
      double changedFraction) {
    status.recordRun(runAt, outcome, message, changedFraction);
    statusRepository.save(status);
  }
}
