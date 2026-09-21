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
 *
 * <p>{@code accountsLocked}/{@code accountsUnlocked} are the accounts whose access this run takes
 * away or gives back (#1818); {@code accountLocksWithheld} names the administrator a run did not
 * lock because no login-capable one would have remained (ADR-0036, Entscheidung 6) - withheld, and
 * said so, rather than silently left out.
 */
public record SyncReport(
    DirectorySyncOutcome outcome,
    Instant generatedAt,
    List<GroupChange> groupsCreated,
    List<GroupChange> groupsRenamed,
    List<GroupChange> groupsDissolved,
    List<GroupChange> unmaintainedTokenGroups,
    List<MembershipChange> membershipChanges,
    List<UserRef> accountsLocked,
    List<UserRef> accountsUnlocked,
    List<UserRef> accountLocksWithheld,
    int membershipsAdded,
    int membershipsRemoved,
    int unresolvedMemberCount,
    double changedFraction,
    double thresholdFraction,
    String message) {}
