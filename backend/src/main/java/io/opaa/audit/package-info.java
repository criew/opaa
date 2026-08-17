/**
 * The append-only audit trail (#391, decision #355, see
 * docs/features/security-and-compliance.md#revisionssicheres-protokoll): storage for {@link
 * io.opaa.audit.AuditLogEntry} and the separately held actor pseudonym mapping ({@link
 * io.opaa.audit.AuditActorPseudonym}). Callers write through {@link
 * io.opaa.audit.AuditLogService#record}; nothing in this package updates or deletes an entry once
 * written - see {@link io.opaa.audit.AuditLogRepository}'s Javadoc for how that is enforced both at
 * the application level and, more importantly, at the database level (migration 017).
 *
 * <p>This package only provides the store and the record shape. Emitting events from the services
 * that change grants, spaces, libraries, groups and accounts, the query/access path for reading
 * entries back out, and the retention deletion are all separate, later issues (#391's "Nicht in
 * diesem Vorgang").
 */
package io.opaa.audit;
