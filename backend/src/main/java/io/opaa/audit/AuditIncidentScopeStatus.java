package io.opaa.audit;

/**
 * Lifecycle of an {@link AuditIncidentScopeGrant}. Mirrored by a database check constraint; keep
 * both in sync.
 *
 * <p>Deliberately only two states: a grant is either awaiting the required second, different
 * approver ({@code PENDING}) or usable ({@code APPROVED}). There is no {@code REJECTED} state - an
 * unwanted request is simply never approved, and no {@code REVOKED} state - revocation of an
 * already-approved grant is out of scope (the grant's own time range is the only technical bound it
 * enforces).
 */
public enum AuditIncidentScopeStatus {
  PENDING,
  APPROVED
}
