package io.opaa.revision;

import io.opaa.api.types.AccessAsOfObjectType;
import io.opaa.api.types.AccessAsOfSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One page of the Stichtagsauskunft about one named object (#1822, ADR-0036 Entscheidung 8), with
 * the two gaps the reconstruction cannot close on its own stated rather than left to the reader:
 * before {@code retentionCutoff} the intervals are deleted, so an empty answer there is "no longer
 * on record" (#1833), and {@code sourcesNotCovered} names the rights sources that carry no history
 * yet - an empty answer is therefore never a proof that nobody had access.
 */
public record AccessAsOfResult(
    AccessAsOfObjectType objectType,
    UUID objectId,
    String objectName,
    Instant from,
    Instant to,
    Instant retentionCutoff,
    boolean beyondRetention,
    List<AccessAsOfSource> sourcesNotCovered,
    List<AccessAsOfEntry> entries,
    int page,
    int size,
    long totalElements,
    int totalPages) {}
