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

  @Override
  public DirectorySnapshot fetchGroups(UUID organizationId) throws DirectoryUnavailableException {
    if (failure != null) {
      throw failure;
    }
    return snapshot;
  }
}
