package io.opaa.group.sync;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A single successful read of the directory's current group list, as returned by {@link
 * DirectoryClient#fetchGroups}. An unreachable directory does not produce a snapshot at all - see
 * {@link DirectoryUnavailableException} - and an empty {@link #groups()} list is a valid snapshot
 * that {@link DirectorySyncService} treats specially precisely because it is indistinguishable from
 * a misconfigured connection at this layer (see #237's acceptance criteria).
 */
public record DirectorySnapshot(Instant fetchedAt, List<DirectoryGroup> groups) {

  public DirectorySnapshot {
    Objects.requireNonNull(fetchedAt, "fetchedAt must not be null");
    groups = groups == null ? List.of() : List.copyOf(groups);
  }
}
