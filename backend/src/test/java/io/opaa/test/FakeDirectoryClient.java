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
 * DirectoryClient}'s own Javadoc): a scriptable response instead of the production {@code
 * NoOpDirectoryClient}, published for the whole suite by {@code OpaaTestBeans}.
 *
 * <p>{@code OpaaTestBeanResetListener} calls {@link #reset()} before every test method, so no class
 * inherits whatever a sibling sharing the context last configured via {@link #respondWith}/{@link
 * #failWith}.
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

  /** Restores the empty-response, no-failure default the reset listener relies on. */
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
