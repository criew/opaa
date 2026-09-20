package io.opaa.group.sync;

import java.time.Instant;
import java.util.UUID;

/**
 * A pending plan with its stored report decoded - what the management shows to decide on. The
 * report is the one the run presented, read back from the row rather than recomputed, so the
 * decision is made about exactly what was shown (ADR-0036, Entscheidung 3).
 */
public record PendingPlanView(
    UUID id,
    UUID providerId,
    Instant createdAt,
    double changedFraction,
    int membershipsRemoved,
    SyncReport report) {}
