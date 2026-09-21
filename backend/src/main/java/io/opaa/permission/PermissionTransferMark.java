package io.opaa.permission;

import java.time.Instant;
import java.util.UUID;

/**
 * The note an object carries in its sharing view after a transfer touched it ("übertragen am
 * 14.03.2026, Vorgang …", ADR-0036 Entscheidung 10) - resolved <b>for one caller</b>.
 *
 * <p>{@code sourceLabel} is the source group's name and is {@code null} whenever the caller may not
 * learn it: because the source was a person (a hint repeated at many objects that a named colleague
 * left the house is exactly what this null prevents), because the group is protected ({@code
 * sourceProtected} then says so without naming it), or because the group is not visible to this
 * caller at all (ADR-0036, Entscheidung 9).
 */
public record PermissionTransferMark(
    UUID transferId, Instant transferredAt, String sourceLabel, boolean sourceProtected) {}
