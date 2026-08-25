package io.opaa.api.types;

/**
 * The result of the recorded action. Mirrored by the database check constraint {@code
 * chk_audit_log_outcome}; keep both in sync.
 *
 * <p>The rejected action is deliberately a first-class outcome, not merely the absence of a
 * successful entry: "die abgelehnte Verwaltungsaktion ist für eine Prüfung oft die interessantere"
 * (docs/features/security-and-compliance.md#der-protokollsatz).
 */
public enum AuditOutcome {
  SUCCESS,
  DENIED,
  FAILURE
}
