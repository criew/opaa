package io.opaa.permission;

import java.time.Instant;
import java.util.UUID;

/**
 * The note an object carries in its sharing view after a transfer touched it ("übertragen am
 * 14.03.2026, Vorgang …", ADR-0036 Entscheidung 10).
 *
 * <p>{@code sourceLabel} is the source group's name and is {@code null} when the source was a
 * person - a hint repeated at many objects that a named colleague left the house, outside every
 * retention period, is exactly what this null stands for.
 */
public record PermissionTransferMark(UUID transferId, Instant transferredAt, String sourceLabel) {}
