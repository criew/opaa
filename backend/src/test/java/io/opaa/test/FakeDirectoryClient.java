package io.opaa.test;

import io.opaa.group.sync.DirectoryClient;
import io.opaa.group.sync.DirectoryGroup;
import io.opaa.group.sync.DirectorySnapshot;
import io.opaa.group.sync.DirectoryUnavailableException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The one seam between {@code DirectorySyncService} and an actual directory (see {@link
 * DirectoryClient}'s own Javadoc) - shared by every test class that needs a scriptable directory
 * response instead of the production {@code NoOpDirectoryClient}, so those classes can carry an
 * identical {@link DirectorySyncMockConfiguration} import and therefore share one Spring context
 * (Issue #903).
 *
 * <p>{@link DirectorySyncMockResetListener} calls {@link #reset()} before every test method of a
 * class carrying it, so no class relies on inheriting whatever the previous test (in this class or
 * a sibling sharing the same context) last configured via {@link #respondWith}/{@link #failWith}.
 */
public final class FakeDirectoryClient implements DirectoryClient {

  private DirectorySnapshot snapshot = new DirectorySnapshot(Instant.now(), List.of());
  private DirectoryUnavailableException failure;

  public void respondWith(DirectoryGroup... groups) {
    this.failure = null;
    this.snapshot = new DirectorySnapshot(Instant.now(), List.of(groups));
  }

  public void failWith(String message) {
    this.failure = new DirectoryUnavailableException(message);
  }

  /**
   * Restores the empty-response, no-failure default {@link DirectorySyncMockResetListener} relies
   * on.
   */
  public void reset() {
    this.failure = null;
    this.snapshot = new DirectorySnapshot(Instant.now(), List.of());
  }

  @Override
  public DirectorySnapshot fetchGroups(UUID organizationId) throws DirectoryUnavailableException {
    if (failure != null) {
      throw failure;
    }
    return snapshot;
  }
}
