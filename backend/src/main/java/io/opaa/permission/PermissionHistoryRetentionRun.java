package io.opaa.permission;

import java.time.Instant;

/**
 * What one pass of {@link PermissionHistoryRetentionDeletionService} did: the cutoff it deleted by
 * - the configured period's target only once the forward-only cap has caught up - and how many
 * closed history rows it removed across every {@link PermissionHistorySweeper}.
 *
 * <p>Not the same as the recorded {@code last_cutoff}: after a lengthening this cutoff lies behind
 * that high-water mark, and the pass deletes nothing.
 */
public record PermissionHistoryRetentionRun(Instant cutoff, long deletedRows) {}
