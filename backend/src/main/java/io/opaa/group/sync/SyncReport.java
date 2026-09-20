package io.opaa.group.sync;

import io.opaa.api.types.DirectorySyncOutcome;
import java.time.Instant;
import java.util.List;

/**
 * The outcome of one directory synchronisation run, dry or applied. Domain counterpart of the
 * generated {@code DirectorySyncReportResponse}, mapped by {@code
 * io.opaa.api.DirectorySyncResponseMapper}.
 *
 * <p>{@code unmaintainedTokenGroups} names the provider's token groups while its directory run is
 * on - "no longer maintained" (ADR-0036, Entscheidung 3). They keep their frozen membership and are
 * never touched by a run: a change of mechanism revokes nothing silently.
 */
public record SyncReport(
    DirectorySyncOutcome outcome,
    Instant generatedAt,
    List<GroupChange> groupsCreated,
    List<GroupChange> groupsRenamed,
    List<GroupChange> groupsDissolved,
    List<GroupChange> unmaintainedTokenGroups,
    List<MembershipChange> membershipChanges,
    int membershipsAdded,
    int membershipsRemoved,
    int unresolvedMemberCount,
    double changedFraction,
    double thresholdFraction,
    String message) {}
