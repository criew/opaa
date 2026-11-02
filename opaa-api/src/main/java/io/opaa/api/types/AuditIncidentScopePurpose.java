package io.opaa.api.types;

/**
 * The closed list of purposes an anlassbezogene Klärung may be requested for. Mirrored by the
 * database check constraint {@code chk_audit_incident_scope_grants_purpose}; keep both in sync.
 *
 * <p>The Zweckausschluss of ADR-0068 (decision 2) ("arbeitsrechtliche, disziplinarische und
 * leistungsbezogene Fragen") is enforced here by omission, not by a runtime check against an
 * excluded-values list: there is no value on this enum a caller could even select for such a
 * purpose.
 */
public enum AuditIncidentScopePurpose {
  SECURITY_INCIDENT,
  UNAUTHORIZED_ACCESS_SUSPICION,
  DATA_BREACH_INVESTIGATION,
  EXTERNAL_AUDIT_OR_INSPECTION
}
