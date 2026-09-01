package io.opaa.diagnosticaccess;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single call site of {@code opaa_diagnostic_context_delete_expired_partitions()}. Issues no
 * {@code DROP}/{@code DELETE}/{@code TRUNCATE} of its own and takes no argument that could restrict
 * or extend a run - the database function's own configuration and forward-only cap decide what a
 * call removes, and the application account could not remove a single row even if this class tried.
 */
@Service
public class DiagnosticContextRetentionDeletionService {

  private static final Logger log =
      LoggerFactory.getLogger(DiagnosticContextRetentionDeletionService.class);

  private final DiagnosticContextRetentionSettingsRepository repository;

  public DiagnosticContextRetentionDeletionService(
      DiagnosticContextRetentionSettingsRepository repository) {
    this.repository = repository;
  }

  /** Runs one deletion pass; idempotent and safe to call more often than the schedule requires. */
  @Transactional
  public List<String> runOnce() {
    List<String> dropped = repository.deleteExpiredPartitions();
    if (!dropped.isEmpty()) {
      log.info("Diagnostic context retention: dropped partitions: {}", dropped);
    }
    return dropped;
  }
}
