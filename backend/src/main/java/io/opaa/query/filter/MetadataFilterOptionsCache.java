package io.opaa.query.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.api.types.AssetGrantSubjectType;
import io.opaa.asset.AssetChanged;
import io.opaa.asset.AssetGrantChanged;
import io.opaa.instance.BusBoundCache;
import io.opaa.instance.InvalidationBus;
import io.opaa.instance.InvalidationSubscriber;
import io.opaa.library.LibraryChanged;
import io.opaa.library.PrivateLibraryErased;
import io.opaa.metadata.LibraryMetadataSchemaChanged;
import io.opaa.permission.GroupMembershipChangeListener;
import io.opaa.permission.GroupMembershipResolver;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The per-person cache of {@link MetadataFilterOptions} the specification explicitly allows
 * (metadata-schema.md, Rechte-Invariante): keyed by the person <em>and</em> the search scope her
 * rights resolved to, so an entry can only ever be served for exactly the libraries it was built
 * over; discarded on every rights change that touches the person - a grant on a library, a
 * library's visibility, a group membership - through the same events and hooks the rights caches
 * themselves react to. Time-based expiry is the bound on staleness of the bestand itself (new
 * documents, corrected values), not the correctness mechanism for rights.
 *
 * <p>Bound to the {@link InvalidationBus} (ADR-0053, Entscheidung 5): a rights change on any
 * instance empties it on every instance through the triggers of the rights tables, and the events
 * without a table of their own (schema, asset, release, erasure) are announced from the transaction
 * that publishes them. While the bus does not listen, it is read past.
 */
@Component
public class MetadataFilterOptionsCache
    implements GroupMembershipChangeListener, InvalidationSubscriber {

  /** The topic of the changes this cache announces itself. */
  public static final String INVALIDATION_TOPIC = "metadata-filter-options";

  private final BusBoundCache<Key, MetadataFilterOptions> options;
  private final InvalidationBus bus;

  public MetadataFilterOptionsCache(MetadataFilterProperties properties, InvalidationBus bus) {
    Cache<Key, MetadataFilterOptions> cache =
        Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(properties.optionsCacheTtl())
            .build();
    this.options = new BusBoundCache<>(cache, bus);
    this.bus = bus;
  }

  @Override
  public Set<String> invalidationTopics() {
    return Set.of(GroupMembershipResolver.RIGHTS_TOPIC, INVALIDATION_TOPIC);
  }

  /** The cached options for this person and scope, computed via {@code loader} on a miss. */
  public MetadataFilterOptions get(
      UUID userId, Set<UUID> searchScope, Function<Set<UUID>, MetadataFilterOptions> loader) {
    return options.get(new Key(userId, Set.copyOf(searchScope)), key -> loader.apply(searchScope));
  }

  /** Whether an entry for this person and scope is currently held. */
  boolean contains(UUID userId, Set<UUID> searchScope) {
    return options.getIfPresent(new Key(userId, Set.copyOf(searchScope))) != null;
  }

  public void invalidateUser(UUID userId) {
    options.asMap().keySet().removeIf(key -> key.userId().equals(userId));
  }

  public void invalidateUsers(Collection<UUID> userIds) {
    Set<UUID> ids = Set.copyOf(userIds);
    options.asMap().keySet().removeIf(key -> ids.contains(key.userId()));
  }

  @Override
  public void invalidateAll() {
    options.invalidateAll();
  }

  /**
   * Announces the events below to every instance, inside the publishing transaction: delivered with
   * its commit, never after a rollback.
   */
  @TransactionalEventListener(
      classes = {
        LibraryMetadataSchemaChanged.class,
        AssetChanged.class,
        LibraryChanged.class,
        PrivateLibraryErased.class
      },
      phase = TransactionPhase.BEFORE_COMMIT,
      fallbackExecution = true)
  public void announce(Object event) {
    bus.publish(INVALIDATION_TOPIC);
  }

  /**
   * A grant changed: for a person, her entries go; for a group, every entry goes - resolving the
   * group's members here would be a second rights computation next to the one the search trusts.
   * After completion (commit or rollback), never inside the publisher's transaction: an eviction
   * before commit could be repopulated by a concurrent reader with the pre-commit rights.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)
  public void onGrantChanged(AssetGrantChanged event) {
    if (event.grant().getSubjectType() == AssetGrantSubjectType.USER) {
      invalidateUser(event.grant().getSubjectUserId());
    } else {
      invalidateAll();
    }
  }

  /**
   * A library's metadata field schema changed: the offered fields and their value lists are derived
   * from it, so every entry that could contain the library is stale. Emptying the whole cache is
   * the cheap and provably complete answer - the entries are rebuilt from the bestand on the next
   * opening of the filter interface, in each person's own rights context.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)
  public void onLibraryMetadataSchemaChanged(LibraryMetadataSchemaChanged event) {
    invalidateAll();
  }

  /** An asset was created: that reaches every person's scope. */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)
  public void onAssetChanged(AssetChanged event) {
    invalidateAll();
  }

  /** A library's release for Fremdzugaenge changed. */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)
  public void onLibraryChanged(LibraryChanged event) {
    invalidateAll();
  }

  /** A private library was erased: its field values must not be offered any more. */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onPrivateLibraryErased(PrivateLibraryErased event) {
    invalidateAll();
  }

  @Override
  public void onMembershipChanged(Collection<UUID> userIds) {
    invalidateUsers(userIds);
  }

  private record Key(UUID userId, Set<UUID> searchScope) {}
}
