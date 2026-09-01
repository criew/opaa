package io.opaa.diagnosticaccess;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one protocol entry, deliberately outside whatever transaction the caller holds.
 *
 * <p>{@code Propagation.NOT_SUPPORTED} suspends the ambient transaction for the duration of the
 * call, so the repository's own transaction commits the entry immediately and independently - the
 * same construction {@code AuditEventRecorder#recordAuditLogAccess} uses, and for the same reason:
 * Leitplanke (f) requires an entry for every execution in a foreign rights context, and an entry a
 * later rollback of the calling transaction removes would not be one. Both failure directions are
 * intended: a failing execution keeps its entry, and a failing entry write surfaces at the call
 * that made it.
 */
@Service
class DiagnosticContextLogWriter {

  private final DiagnosticContextLogRepository logRepository;

  DiagnosticContextLogWriter(DiagnosticContextLogRepository logRepository) {
    this.logRepository = logRepository;
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  DiagnosticContextLogEntry record(DiagnosticContextLogEntry entry) {
    return logRepository.save(entry);
  }
}
