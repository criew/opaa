package io.opaa.audit;

/**
 * The closed list of purposes an anlassbezogene Klärung
 * (docs/features/security-and-compliance.md#zugriffswege-was-es-gibt-und-was-es-nicht-gibt) may be
 * requested for. Mirrored by a database check constraint; keep both in sync.
 *
 * <p>The specification's Zweckausschluss ("arbeitsrechtliche, disziplinarische und
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
