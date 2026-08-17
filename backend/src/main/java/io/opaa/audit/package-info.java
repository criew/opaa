/**
 * The append-only audit trail (#391, decision #355, see
 * docs/features/security-and-compliance.md#revisionssicheres-protokoll): storage for {@link
 * io.opaa.audit.AuditLogEntry} and the separately held actor pseudonym mapping ({@link
 * io.opaa.audit.AuditActorPseudonym}). Callers write through {@link
 * io.opaa.audit.AuditLogService#record} (directly, or via {@link io.opaa.audit.AuditEventRecorder},
 * #392's convenience entry point); nothing in this package updates or deletes an entry once written
 * - see {@link io.opaa.audit.AuditLogRepository}'s Javadoc for how that is enforced both at the
 * application level and, more importantly, at the database level (migration 017).
 *
 * <p>#393 adds the revision read path on top of that store: {@link io.opaa.audit.AuditQueryService}
 * is the single funnel every read goes through - exactly the four access paths the specification
 * allows (docs/features/security-and-compliance.md#zugriffswege-was-es-gibt-und-was-es-nicht-gibt)
 * plus the one personenbezogene exception ({@link io.opaa.audit.AuditIncidentScopeService}, the
 * anlassbezogene Klärung under the Vier-Augen-Prinzip). {@code io.opaa.api.AuditController} exposes
 * it, restricted to {@code SystemRole.AUDITOR}. The retention deletion remains a separate, later
 * issue.
 */
package io.opaa.audit;
