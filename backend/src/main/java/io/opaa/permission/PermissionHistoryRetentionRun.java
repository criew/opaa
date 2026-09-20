package io.opaa.permission;

import java.time.Instant;

/**
 * What one pass of {@link PermissionHistoryRetentionDeletionService} did: the cutoff it actually
 * reached - which is the configured period's target only once the forward-only cap has caught up -
 * and how many closed history rows it removed across every {@link PermissionHistorySweeper}.
 */
public record PermissionHistoryRetentionRun(Instant cutoff, long deletedRows) {}
