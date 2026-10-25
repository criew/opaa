package io.opaa.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * What the SIEM export carries of one audit log entry (ADR-0055, Entscheidung 2): kind, time,
 * affected object and outcome - never a pseudonym of an acting or affected person, a reason or a
 * payload. The one exception is the month seal of the checksum chain, whose month, entry count and
 * head hash go out with the {@code AUDIT_LOG_MONTH_SEALED} entry, read from the seal table.
 *
 * @param objectId {@code null} where the object itself would name a person
 * @param objectLabel {@code null} where the object itself would name a person
 * @param schemaVersion the version of the event contract the entry was written under ({@link
 *     AuditEventCatalog#SCHEMA_VERSION}), {@code null} for an entry from before it
 * @param seal the month seal this entry carries, {@code null} for every other entry
 */
record AuditExportEntry(
    UUID eventId,
    Instant recordedAt,
    UUID organizationId,
    String eventType,
    String objectType,
    String objectId,
    String objectLabel,
    String outcome,
    Short schemaVersion,
    Seal seal) {

  /** The seal of one month: the month, the number of chained entries and the head hash, hex. */
  record Seal(String month, long entryCount, String headHash) {}
}
