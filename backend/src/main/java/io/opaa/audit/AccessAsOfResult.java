package io.opaa.audit;

import io.opaa.api.types.AccessAsOfObjectType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One page of the Stichtagsauskunft about one named object (#1822, ADR-0036 Entscheidung 8). {@code
 * beyondRetention} is the distinction the reconstruction cannot make on its own: before {@code
 * retentionCutoff} the intervals are deleted, so an empty answer there is "no longer on record",
 * not "no access" (#1833).
 */
public record AccessAsOfResult(
    AccessAsOfObjectType objectType,
    UUID objectId,
    String objectName,
    Instant from,
    Instant to,
    Instant retentionCutoff,
    boolean beyondRetention,
    List<AccessAsOfEntry> entries,
    int page,
    int size,
    long totalElements,
    int totalPages) {}
