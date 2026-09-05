package io.opaa.query;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.group.GroupMembershipChangeListener;
import io.opaa.indexing.metadata.LibraryMetadataSchemaChanged;
import io.opaa.library.GrantChanged;
import io.opaa.library.LibraryChanged;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The per-person cache of {@link MetadataFilterOptions} (#1070) the specification explicitly allows
 * (metadata-schema.md, Rechte-Invariante): keyed by the person <em>and</em> the search scope her
 * rights resolved to, so an entry can only ever be served for exactly the libraries it was built
 * over; discarded on every rights change that touches the person - a grant on a library, a
 * library's visibility, a group membership - through the same events and hooks the rights caches
 * themselves react to. Time-based expiry is the bound on staleness of the bestand itself (new
 * documents, corrected values), not the correctness mechanism for rights.
 *
 * <p>Process-local like {@code GroupMembershipResolver}'s cache (ADR-0021).
 */
@Component
public class MetadataFilterOptionsCache implements GroupMembershipChangeListener {

  private final Cache<Key, MetadataFilterOptions> options;

  public MetadataFilterOptionsCache(MetadataFilterProperties properties) {
    this.options =
        Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(properties.optionsCacheTtl())
            .build();
  }

  /** The cached options for this person and scope, computed via {@code loader} on a miss. */
  public MetadataFilterOptions get(
      UUID userId, Set<UUID> searchScope, Function<Set<UUID>, MetadataFilterOptions> loader) {
    return options.get(new Key(userId, Set.copyOf(searchScope)), key -> loader.apply(searchScope));
  }

  /** Whether an entry for this person and scope is currently held - for tests of the eviction. */
  public boolean contains(UUID userId, Set<UUID> searchScope) {
    return options.getIfPresent(new Key(userId, Set.copyOf(searchScope))) != null;
  }

  public void invalidateUser(UUID userId) {
    options.asMap().keySet().removeIf(key -> key.userId().equals(userId));
  }

  public void invalidateUsers(Collection<UUID> userIds) {
    Set<UUID> ids = Set.copyOf(userIds);
    options.asMap().keySet().removeIf(key -> ids.contains(key.userId()));
  }

  public void invalidateAll() {
    options.invalidateAll();
  }

  /**
   * A grant changed: for a person, her entries go; for a group, every entry goes - resolving the
   * group's members here would be a second rights computation next to the one the search trusts.
   * After completion (commit or rollback), never inside the publisher's transaction: an eviction
   * before commit could be repopulated by a concurrent reader with the pre-commit rights.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)
  public void onGrantChanged(GrantChanged event) {
    if (event.grant().getSubjectType() == PermissionSubjectType.USER) {
      invalidateUser(event.grant().getSubjectUserId());
    } else {
      invalidateAll();
    }
  }

  /**
   * A library's metadata field schema changed (#1071): the offered fields and their value lists are
   * derived from it, so every entry that could contain the library is stale. Emptying the whole
   * cache is the cheap and provably complete answer - the entries are rebuilt from the bestand on
   * the next opening of the filter interface, in each person's own rights context.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)
  public void onLibraryMetadataSchemaChanged(LibraryMetadataSchemaChanged event) {
    invalidateAll();
  }

  /** A library's visibility (or existence) changed: that reaches every person's scope. */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)
  public void onLibraryChanged(LibraryChanged event) {
    invalidateAll();
  }

  @Override
  public void onMembershipChanged(Collection<UUID> userIds) {
    invalidateUsers(userIds);
  }

  private record Key(UUID userId, Set<UUID> searchScope) {}
}
