package io.opaa.permission;

import java.time.Instant;

/**
 * What one pass of {@link PermissionHistoryRetentionDeletionService} did: the cutoff it deleted by
 * - always the configured period's own - and how many closed history rows it removed across every
 * {@link PermissionHistorySweeper}.
 *
 * <p>Not the same as the recorded {@code last_cutoff}: after a lengthening this cutoff lies before
 * that high-water mark, and the pass deletes nothing.
 */
public record PermissionHistoryRetentionRun(Instant cutoff, long deletedRows) {}
