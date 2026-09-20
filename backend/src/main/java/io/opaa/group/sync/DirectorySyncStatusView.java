package io.opaa.group.sync;

import java.util.UUID;

/**
 * One provider's status line of the directory run, as the management overview needs it (ADR-0036,
 * Entscheidung 3). {@code status} is null until that provider has run once; {@code pendingPlan} is
 * null unless a plan waits for a decision - its {@code createdAt} is the age that belongs in the
 * status line itself rather than on a subpage.
 */
public record DirectorySyncStatusView(
    UUID providerId,
    String providerDisplayName,
    boolean providerEnabled,
    boolean enabled,
    Integer intervalMinutes,
    DirectorySyncStatus status,
    DirectorySyncPendingPlan pendingPlan) {}
