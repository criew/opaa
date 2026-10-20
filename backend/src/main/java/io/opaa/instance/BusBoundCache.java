package io.opaa.instance;

import com.github.benmanes.caffeine.cache.Cache;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * A Caffeine cache bound to the {@link InvalidationBus} (ADR-0053, Entscheidung 5). While the bus
 * does not listen it reads past the cache without storing anything. A load that a drop overtook is
 * returned to its caller but not kept: a value read before the change committed never outlives the
 * announcement of that change.
 */
public final class BusBoundCache<K, V> {

  private final Cache<K, V> cache;
  private final InvalidationBus bus;
  private final AtomicLong generation = new AtomicLong();

  public BusBoundCache(Cache<K, V> cache, InvalidationBus bus) {
    this.cache = Objects.requireNonNull(cache, "cache");
    this.bus = Objects.requireNonNull(bus, "bus");
  }

  public V get(K key, Function<? super K, ? extends V> loader) {
    if (!bus.isListening()) {
      return loader.apply(key);
    }
    long before = generation.get();
    V value = cache.get(key, loader);
    if (generation.get() != before) {
      cache.invalidate(key);
    }
    return value;
  }

  /**
   * The value held for {@code key}, without loading; {@code null} while the bus does not listen.
   */
  public V getIfPresent(K key) {
    return bus.isListening() ? cache.getIfPresent(key) : null;
  }

  public void invalidate(K key) {
    cache.invalidate(key);
  }

  public void invalidateAll(Iterable<? extends K> keys) {
    cache.invalidateAll(keys);
  }

  /** Drops every entry, including those whose load is still running. */
  public void invalidateAll() {
    generation.incrementAndGet();
    cache.invalidateAll();
  }

  /** The underlying map, for removals by a predicate over the keys. */
  public ConcurrentMap<K, V> asMap() {
    return cache.asMap();
  }
}
