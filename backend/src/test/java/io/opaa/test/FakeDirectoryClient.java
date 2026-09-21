package io.opaa.test;

import io.opaa.group.sync.DirectoryAccount;
import io.opaa.group.sync.DirectoryClient;
import io.opaa.group.sync.DirectoryGroup;
import io.opaa.group.sync.DirectorySnapshot;
import io.opaa.group.sync.DirectoryUnavailableException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The one seam between {@code DirectorySyncService} and an actual directory (see {@link
 * DirectoryClient}'s own Javadoc): a scriptable response instead of the production {@code
 * ProviderDirectoryClient}, published for the whole suite by {@code OpaaTestBeans}.
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
  private final Set<UUID> brokenProviders = new HashSet<>();
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

  /**
   * A directory that reports account status too (#1818). The two {@code respondWith} methods above
   * script a connector that reports none, which is what every test predating #1818 exercises - and
   * what leaves the account state untouched.
   */
  public void respondWith(List<DirectoryGroup> groups, List<DirectoryAccount> accounts) {
    this.failure = null;
    this.snapshot = new DirectorySnapshot(Instant.now(), groups, accounts);
  }

  /** The same for one provider, whatever {@link #respondWith} set. */
  public void respondWithFor(
      UUID providerId, List<DirectoryGroup> groups, List<DirectoryAccount> accounts) {
    this.failure = null;
    snapshotsByProvider.put(providerId, new DirectorySnapshot(Instant.now(), groups, accounts));
  }

  public void failWith(String message) {
    this.failure = new DirectoryUnavailableException(message);
  }

  /**
   * Makes this one provider's fetch fail the way a defective connector would - with an unchecked
   * exception rather than the declared {@link DirectoryUnavailableException}, which the run
   * handles. What a caller does with an exception it does not expect is the point of the test.
   */
  public void breakFor(UUID providerId) {
    brokenProviders.add(providerId);
  }

  /**
   * Stands in for the one slow step of a real run: {@code gate} is invoked inside {@link
   * #fetchSnapshot}, so a test can hold a run open at exactly the point a production fetch would
   * take its time and observe what a second, concurrent caller sees.
   */
  public void gateFetchWith(Consumer<UUID> gate) {
    this.fetchGate = gate;
  }

  /** Restores the empty-response, no-failure, ungated default the reset listener relies on. */
  public void reset() {
    this.failure = null;
    this.snapshot = new DirectorySnapshot(Instant.now(), List.of());
    this.snapshotsByProvider.clear();
    this.brokenProviders.clear();
    this.fetchGate = organizationId -> {};
  }

  @Override
  public DirectorySnapshot fetchSnapshot(UUID organizationId, UUID providerId)
      throws DirectoryUnavailableException {
    fetchGate.accept(organizationId);
    if (brokenProviders.contains(providerId)) {
      throw new IllegalStateException("simulated connector defect for provider " + providerId);
    }
    if (failure != null) {
      throw failure;
    }
    return snapshotsByProvider.getOrDefault(providerId, snapshot);
  }
}
