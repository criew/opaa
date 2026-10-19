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
 * <p>{@code accountsBlocked}, {@code accountsReactivated} and {@code accountsProtected} name the
 * people, not only their number (#1818) - a plan that says "40 Konten" and a plan that says who is
 * among them are not the same decision. {@code accountsProtected} is the account a block was left
 * out for because it is the last login-capable system administrator (ADR-0036, Entscheidung 6,
 * Schutzregel 2); it is reported rather than silently omitted.
 */
public record SyncReport(
    DirectorySyncOutcome outcome,
    Instant generatedAt,
    List<GroupChange> groupsCreated,
    List<GroupChange> groupsRenamed,
    List<GroupChange> groupsDissolved,
    List<GroupChange> unmaintainedTokenGroups,
    List<MembershipChange> membershipChanges,
    List<UserRef> accountsBlocked,
    List<UserRef> accountsReactivated,
    List<UserRef> accountsProtected,
    int membershipsAdded,
    int membershipsRemoved,
    int unresolvedMemberCount,
    double changedFraction,
    double thresholdFraction,
    String message) {}
