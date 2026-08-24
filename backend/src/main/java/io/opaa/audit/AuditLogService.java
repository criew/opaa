package io.opaa.audit;

import org.springframework.stereotype.Service;

/**
 * The write interface for services recording an audit entry ("so gebaut, dass ein Ereignis nicht
 * verloren geht, wenn der auslösende Vorgang zurückgerollt wird bzw. dass es dann auch nicht
 * geschrieben wird").
 *
 * <p><b>Chosen transaction behaviour: the entry is written in the caller's own transaction, not a
 * separate one.</b> {@link #record} carries no {@code @Transactional} of its own, so a call made
 * from within an ambient transaction simply joins it; a rolled-back triggering operation rolls the
 * audit entry back with it, and a committed one commits it with it. This is deliberately chosen
 * over a separate, always-committing transaction: an audit entry for an action that itself never
 * took effect would be actively misleading, e.g. "grant revoked" recorded while the revoke itself
 * never committed. A rejected action that genuinely happened is recorded with {@link
 * AuditOutcome#DENIED} or {@link AuditOutcome#FAILURE} by the calling service as part of its own
 * successful flow, not implied by a leftover row from a rolled-back attempt.
 *
 * <p>Verified by {@code AuditLogServiceIntegrationTest}, against a real transaction manager and
 * real Postgres, not a mocked one: committing the surrounding transaction persists the entry,
 * rolling it back leaves no trace of it.
 */
@Service
public class AuditLogService {

  private final AuditLogRepository auditLogRepository;

  public AuditLogService(AuditLogRepository auditLogRepository) {
    this.auditLogRepository = auditLogRepository;
  }

  /**
   * Persists {@code entry}. Participates in the caller's ambient transaction if one is open (see
   * class Javadoc); if none is open, the write commits immediately on its own, like any other plain
   * repository call in this codebase.
   */
  public AuditLogEntry record(AuditLogEntry entry) {
    return auditLogRepository.save(entry);
  }
}
