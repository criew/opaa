package io.opaa.group.sync;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A single successful read of the directory's current state, as returned by {@link
 * DirectoryClient#fetchSnapshot}. An unreachable directory does not produce a snapshot at all - see
 * {@link DirectoryUnavailableException} - and an empty {@link #groups()} list is a valid snapshot
 * that {@link DirectorySyncService} treats specially precisely because it is indistinguishable from
 * a misconfigured connection at this layer (see #237's acceptance criteria).
 *
 * @param accounts every account of this provider's directory with its state (#1818), or {@code
 *     null} from a connector that does not report account status at all - then no account is ever
 *     locked or unlocked by the run. An <em>empty</em> list is something else entirely: the
 *     connector reports account status and found none, which aborts the run the same way an empty
 *     group list does.
 */
public record DirectorySnapshot(
    Instant fetchedAt, List<DirectoryGroup> groups, List<DirectoryAccount> accounts) {

  public DirectorySnapshot {
    Objects.requireNonNull(fetchedAt, "fetchedAt must not be null");
    groups = groups == null ? List.of() : List.copyOf(groups);
    accounts = accounts == null ? null : List.copyOf(accounts);
  }

  /** A snapshot of a directory whose connector reports no account status. */
  public DirectorySnapshot(Instant fetchedAt, List<DirectoryGroup> groups) {
    this(fetchedAt, groups, null);
  }

  public boolean reportsAccounts() {
    return accounts != null;
  }
}
