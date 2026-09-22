package io.opaa.audit;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single call site that invokes {@code opaa_audit_delete_expired_partitions()} - the automatic,
 * monthly retention deletion. {@link AuditRetentionScheduler} is this class's only caller in
 * production; both are kept separate so a test can exercise the deletion itself without depending
 * on Spring's scheduling machinery.
 *
 * <p>This class issues no {@code DROP}/{@code DELETE}/{@code TRUNCATE} of its own and never will -
 * it only calls the one, parameterless database function ("ein Aufruf, der einzelne Sätze entfernen
 * würde, existiert nicht", "das Anwendungskonto kann die Löschung nicht auslösen" other than
 * through this exact, narrow call).
 */
@Service
public class AuditRetentionDeletionService {

  private static final Logger log = LoggerFactory.getLogger(AuditRetentionDeletionService.class);

  private final AuditRetentionSettingsRepository repository;

  public AuditRetentionDeletionService(AuditRetentionSettingsRepository repository) {
    this.repository = repository;
  }

  /**
   * Runs one deletion pass. Idempotent and safe to call more often than the schedule requires - a
   * second call in the same calendar month reaches the same cutoff and finds nothing left.
   *
   * <p>A shortening of the period takes effect with the next pass, in full (changeset 078, #1851);
   * a lengthening takes effect at once and takes nothing back, since {@code last_cutoff} is a
   * high-water mark that never moves backwards.
   */
  @Transactional
  public List<String> runOnce() {
    List<String> droppedPartitions = repository.deleteExpiredPartitions();
    if (!droppedPartitions.isEmpty()) {
      log.info("Audit retention: dropped partitions: {}", droppedPartitions);
    }
    return droppedPartitions;
  }
}
