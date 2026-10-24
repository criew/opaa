package io.opaa.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * What the SIEM export carries of one audit log entry (ADR-0055, Entscheidung 2): kind, time,
 * affected object and outcome - never a pseudonym of an acting or affected person, a reason or a
 * payload.
 *
 * @param objectId {@code null} where the object itself would name a person
 * @param objectLabel {@code null} where the object itself would name a person
 */
record AuditExportEntry(
    UUID eventId,
    Instant recordedAt,
    UUID organizationId,
    String eventType,
    String objectType,
    String objectId,
    String objectLabel,
    String outcome) {}
