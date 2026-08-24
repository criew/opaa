package io.opaa.group.sync;

import java.time.Instant;
import java.util.List;

/**
 * The outcome of one directory synchronisation run, dry or applied. Domain counterpart of the
 * generated {@code DirectorySyncReportResponse}, mapped by {@code
 * io.opaa.api.DirectorySyncResponseMapper}.
 */
public record SyncReport(
    DirectorySyncOutcome outcome,
    Instant generatedAt,
    List<GroupChange> groupsCreated,
    List<GroupChange> groupsRenamed,
    List<GroupChange> groupsDissolved,
    List<MembershipChange> membershipChanges,
    int membershipsAdded,
    int membershipsRemoved,
    int unresolvedMemberCount,
    double changedFraction,
    double thresholdFraction,
    String message) {}
