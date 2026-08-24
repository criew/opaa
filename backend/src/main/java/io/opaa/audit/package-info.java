/**
 * The append-only audit trail (see docs/features/security-and-compliance.md#revisionssicheres-
 * protokoll): storage for {@link io.opaa.audit.AuditLogEntry} and the separately held actor
 * pseudonym mapping ({@link io.opaa.audit.AuditActorPseudonym}). Callers write through {@link
 * io.opaa.audit.AuditLogService#record} (directly, or via {@link
 * io.opaa.audit.AuditEventRecorder}'s convenience entry point); nothing in this package updates or
 * deletes an entry once written - see {@link io.opaa.audit.AuditLogRepository}'s Javadoc for how
 * that is enforced both at the application level and, more importantly, at the database level.
 *
 * <p>{@link io.opaa.audit.AuditQueryService} is the single funnel every read goes through - the
 * four access paths the specification allows
 * (docs/features/security-and-compliance.md#zugriffswege-was-es-gibt-und-was-es-nicht-gibt) plus
 * the one personenbezogene exception ({@link io.opaa.audit.AuditIncidentScopeService}, the
 * anlassbezogene Klärung under the Vier-Augen-Prinzip). {@code io.opaa.api.AuditController} exposes
 * it, restricted to {@code SystemRole.AUDITOR}.
 *
 * <p>Retention and automatic deletion sit on top of that: {@link
 * io.opaa.audit.AuditRetentionSettingsService} reads and changes the single, system-wide retention
 * period (1-10 years, default 3), and {@link io.opaa.audit.AuditRetentionScheduler} runs {@link
 * io.opaa.audit.AuditRetentionDeletionService} monthly, which is the only caller of a database
 * function that drops expired partitions - the one and only path anything in this codebase can
 * remove a row from {@code audit_log} through, and one that only ever drops a complete, fully
 * expired monthly partition, never an individual row.
 */
package io.opaa.audit;
