package io.opaa.directory.sync;

import io.opaa.api.types.DirectorySyncOutcome;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Persists the outcome of a directory synchronisation run. Called only from {@link
 * DirectorySyncService}, which is itself not transactional and calls this only once {@link
 * DirectorySyncPlanExecutor}'s transaction has ended - so there is never an ambient transaction to
 * consider here. A failed apply arrives here only as {@code FAILED}, after its transaction rolled
 * back, so no {@code APPLIED} is recorded for a run that did not apply.
 */
@Service
public class DirectorySyncStatusRecorder {

  private final DirectorySyncStatusRepository statusRepository;

  public DirectorySyncStatusRecorder(DirectorySyncStatusRepository statusRepository) {
    this.statusRepository = statusRepository;
  }

  /**
   * Deliberately <b>not</b> {@code @Transactional}. Each {@link DirectorySyncStatusRepository} call
   * below already runs in its own implicit transaction (Spring Data's {@code SimpleJpaRepository}
   * methods are individually {@code @Transactional}), so no explicit demarcation is needed - and
   * one here would actively break {@link #insertOrUpdateOnRaceLost}: inside a shared, still-open
   * transaction the failed insert marks that transaction rollback-only, so the fallback read would
   * run on a poisoned transaction and the caller would still see an exception instead of a recorded
   * status. The same reasoning as {@code UserService#findOrCreateUser}, which handles the identical
   * race on {@code uq_users_subject_issuer}.
   *
   * <p>An inner {@code REQUIRES_NEW} transaction for the insert attempt is deliberately
   * <em>not</em> used: it cannot see an uncommitted {@code users} row, can commit a status ahead of
   * an apply that later rolls back, and makes each caller hold two connections at once.
   *
   * <p>Two callers updating an <em>existing</em> row concurrently remain last-writer-wins. That is
   * the intended semantics of a table holding "the outcome of the most recent run" and is unchanged
   * by this fix; the unserialised concurrent runs behind it are the known gap documented in {@link
   * DirectorySyncService}'s class javadoc.
   */
  public void record(
      UUID organizationId,
      UUID providerId,
      Instant runAt,
      DirectorySyncOutcome outcome,
      String message,
      double changedFraction) {
    Optional<DirectorySyncStatus> existing =
        statusRepository.findByOrganizationIdAndProviderId(organizationId, providerId);
    if (existing.isPresent()) {
      updateExisting(existing.get(), runAt, outcome, message, changedFraction);
      return;
    }
    insertOrUpdateOnRaceLost(organizationId, providerId, runAt, outcome, message, changedFraction);
  }

  /**
   * Creates the organization's status row, tolerating the race of two concurrent <em>first</em>
   * runs for the same provider getting past the {@code findByOrganizationIdAndProviderId} check in
   * {@link #record} together. Only the first run of a provider can reach this method at all; every
   * later run takes {@link #record}'s update branch.
   *
   * <p>Because {@link #record} is deliberately not {@code @Transactional} (see its javadoc), the
   * {@code saveAndFlush} below runs in its own short-lived, implicit transaction. A {@link
   * DataIntegrityViolationException} on {@code uk_directory_sync_status_organization_provider}
   * rolls back only that insert; nothing else is poisoned by it, so the loser of the race can
   * simply update the row the winner has by now committed instead of surfacing the violation to
   * {@code DirectorySyncService.recordStatusSafely}, where it would be logged and the run's outcome
   * lost. Same fallback-read pattern as {@code UserService#createOrFetchUser}.
   */
  private void insertOrUpdateOnRaceLost(
      UUID organizationId,
      UUID providerId,
      Instant runAt,
      DirectorySyncOutcome outcome,
      String message,
      double changedFraction) {
    DirectorySyncStatus status = new DirectorySyncStatus(organizationId, providerId);
    status.recordRun(runAt, outcome, message, changedFraction);
    try {
      // saveAndFlush forces the INSERT to execute (and thus to fail, if it must) here, instead of
      // being deferred to a later flush point where the DataIntegrityViolationException could
      // surface somewhere other than this try block.
      statusRepository.saveAndFlush(status);
    } catch (DataIntegrityViolationException raceLost) {
      DirectorySyncStatus winner =
          statusRepository
              .findByOrganizationIdAndProviderId(organizationId, providerId)
              .orElseThrow(() -> raceLost);
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
