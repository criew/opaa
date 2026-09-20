package io.opaa.test;

import io.opaa.group.sync.DirectoryClient;
import io.opaa.group.sync.DirectoryGroup;
import io.opaa.group.sync.DirectorySnapshot;
import io.opaa.group.sync.DirectoryUnavailableException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The one seam between {@code DirectorySyncService} and an actual directory (see {@link
 * DirectoryClient}'s own Javadoc): a scriptable response instead of the production {@code
 * NoOpDirectoryClient}, published for the whole suite by {@code OpaaTestBeans}.
 *
 * <p>Since #1816 a run names the provider whose directory it reads, so a response can be scripted
 * per provider ({@link #respondWithFor}) - what a test of "two providers, only one of them
 * synchronised" needs. {@link #respondWith} scripts the answer every provider without one of its
 * own gets.
 *
 * <p>{@code OpaaTestBeanResetListener} calls {@link #reset()} before every test method, so no class
 * inherits whatever a sibling sharing the context last configured.
 */
public final class FakeDirectoryClient implements DirectoryClient {

  private DirectorySnapshot snapshot = new DirectorySnapshot(Instant.now(), List.of());
  private final Map<UUID, DirectorySnapshot> snapshotsByProvider = new HashMap<>();
  private DirectoryUnavailableException failure;
  private volatile Consumer<UUID> fetchGate = organizationId -> {};

  public void respondWith(DirectoryGroup... groups) {
    this.failure = null;
    this.snapshot = new DirectorySnapshot(Instant.now(), List.of(groups));
  }

  /** The answer this one provider's directory gives, whatever {@link #respondWith} set. */
  public void respondWithFor(UUID providerId, DirectoryGroup... groups) {
    this.failure = null;
    snapshotsByProvider.put(providerId, new DirectorySnapshot(Instant.now(), List.of(groups)));
  }

  public void failWith(String message) {
    this.failure = new DirectoryUnavailableException(message);
  }

  /**
   * Stands in for the one slow step of a real run: {@code gate} is invoked inside {@link
   * #fetchGroups}, so a test can hold a run open at exactly the point a production fetch would take
   * its time and observe what a second, concurrent caller sees.
   */
  public void gateFetchWith(Consumer<UUID> gate) {
    this.fetchGate = gate;
  }

  /** Restores the empty-response, no-failure, ungated default the reset listener relies on. */
  public void reset() {
    this.failure = null;
    this.snapshot = new DirectorySnapshot(Instant.now(), List.of());
    this.snapshotsByProvider.clear();
    this.fetchGate = organizationId -> {};
  }

  @Override
  public DirectorySnapshot fetchGroups(UUID organizationId, UUID providerId)
      throws DirectoryUnavailableException {
    fetchGate.accept(organizationId);
    if (failure != null) {
      throw failure;
    }
    return snapshotsByProvider.getOrDefault(providerId, snapshot);
  }
}
