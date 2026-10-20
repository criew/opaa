package io.opaa.instance;

import java.util.Set;

/**
 * A cache bound to the {@link InvalidationBus} (ADR-0053, Entscheidung 5). Every bean of this type
 * is registered with the bus of its context.
 */
public interface InvalidationSubscriber {

  /** The topics whose announcement makes everything this cache holds stale. */
  Set<String> invalidationTopics();

  /**
   * Drops - or, for a cache that is pushed rather than loaded, reloads - everything this cache
   * holds. Called on the bus's thread for an announcement of one of {@link #invalidationTopics()},
   * when the bus loses its connection, on every failed attempt to restore it, and once more when it
   * listens again; so it must be idempotent, quick and must never throw for a reason the next call
   * would not repair.
   */
  void invalidateAll();
}
