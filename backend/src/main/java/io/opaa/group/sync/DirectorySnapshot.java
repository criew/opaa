package io.opaa.group.sync;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A single successful read of the directory's current group list and account list, as returned by
 * {@link DirectoryClient#fetchDirectory}. An unreachable directory does not produce a snapshot at
 * all - see {@link DirectoryUnavailableException} - and an empty {@link #groups()} list is a valid
 * snapshot that {@link DirectorySyncService} treats specially precisely because it is
 * indistinguishable from a misconfigured connection at this layer (see #237's acceptance criteria).
 *
 * <p><b>Groups and accounts come from one reading, on purpose</b> (#1818). Two calls would be two
 * snapshots of two instants, and a plan could then present a group reading and apply a block that
 * belonged to a different one - a confirmation about something nobody was shown.
 *
 * @param accounts the directory's <b>complete</b> account list, or {@code null} when this connector
 *     does not read accounts at all. The distinction is load-bearing in both directions: a non-null
 *     list is authoritative, so an account missing from it has departed and is blocked, while
 *     {@code null} means this run leaves every account state untouched. The only productive
 *     connector today ({@code KeycloakDirectoryConnector}) always reports a list; a connector whose
 *     access may read groups but not the user directory reports {@code null} rather than an empty
 *     list, which would claim every account had departed. An empty list from a connector that does
 *     report is a different matter - {@link DirectorySyncService} treats it as the same implausible
 *     answer an empty group list is.
 */
public record DirectorySnapshot(
    Instant fetchedAt, List<DirectoryGroup> groups, List<DirectoryAccount> accounts) {

  public DirectorySnapshot {
    Objects.requireNonNull(fetchedAt, "fetchedAt must not be null");
    groups = groups == null ? List.of() : List.copyOf(groups);
    accounts = accounts == null ? null : List.copyOf(accounts);
  }

  /** Whether this connector reported an account list at all - see {@link #accounts()}. */
  public boolean reportsAccounts() {
    return accounts != null;
  }
}
