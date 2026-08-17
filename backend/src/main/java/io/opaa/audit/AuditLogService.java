package io.opaa.audit;

import org.springframework.stereotype.Service;

/**
 * The write interface for services recording an audit entry (#391 acceptance criteria: "so gebaut,
 * dass ein Ereignis nicht verloren geht, wenn der auslösende Vorgang zurückgerollt wird bzw. dass
 * es dann auch nicht geschrieben wird").
 *
 * <p><b>Chosen transaction behaviour: the entry is written in the caller's own transaction, not a
 * separate one.</b> {@link #record} carries no {@code @Transactional} of its own, so a call made
 * from within an ambient transaction simply joins it; a rolled-back triggering operation rolls the
 * audit entry back with it, and a committed one commits it with it. This is the "es wird dann auch
 * nicht geschrieben" branch of the acceptance criterion, deliberately chosen over the alternative
 * (a separate, always-committing transaction that could survive the triggering operation's
 * rollback):
 *
 * <ul>
 *   <li>A separate transaction is exactly the construction that has caused three real, CI-green,
 *       review-only-caught incidents in this project (#280, #297, #299 - see the developer role
 *       contract's Transaktionen section) - visibility of uncommitted rows, wrong commit ordering,
 *       and connection pool exhaustion under load are all real risks it reintroduces here for no
 *       offsetting benefit.
 *   <li>Semantically, an audit entry for an action that itself never took effect (because the
 *       surrounding transaction rolled back) would be actively misleading, not merely superfluous:
 *       e.g. "grant revoked" recorded while the revoke itself never committed. A rejected action
 *       that genuinely happened (validation failure, permission denial) is recorded with {@link
 *       AuditOutcome#DENIED} or {@link AuditOutcome#FAILURE} by the calling service as part of its
 *       own successful flow, not implied by a leftover row from a rolled-back attempt.
 * </ul>
 *
 * <p>Verified by {@code AuditLogServiceIntegrationTest}, against a real transaction manager and
 * real Postgres, not a mocked {@code PlatformTransactionManager} (per the same Transaktionen
 * guidance): committing the surrounding transaction persists the entry, rolling it back leaves no
 * trace of it.
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
