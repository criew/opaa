package io.opaa.audit;

/**
 * What {@code audit_incident_scope_monthly_counts} counts of a grant of the anlassbezogene Klärung.
 * Mirrored by the check constraint {@code chk_audit_incident_scope_monthly_counts_transition}; a
 * rejection is no transition, because a grant has no rejected state ({@code
 * AuditIncidentScopeStatus}).
 */
enum AuditIncidentScopeTransition {
  /** The request, in the month it was made. */
  REQUESTED,
  /** The approval, in the month it was given. */
  APPROVED,
  /**
   * The end of the usability of an approval, in the month of its {@code usable_until}; counted at
   * approval, since that end is fixed then and nothing revokes or extends it.
   */
  EXPIRED
}
