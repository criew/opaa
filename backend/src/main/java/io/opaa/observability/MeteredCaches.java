package io.opaa.observability;

import com.github.benmanes.caffeine.cache.Cache;
import java.util.Map;
import java.util.Set;

/**
 * A bean holding Caffeine caches whose hits, misses and evictions {@link CacheMetricsConfiguration}
 * publishes as {@code cache.*} with the tag {@code cache} (ADR-0071). Each cache records its
 * statistics ({@code Caffeine#recordStats()}); its name is a fixed word, never a key of the cache.
 */
public interface MeteredCaches {

  /** The caches of this bean by their fixed names. */
  Map<String, Cache<?, ?>> meteredCaches();

  /**
   * The names among {@link #meteredCaches()} whose size and evictions are not published, because
   * their number of entries counts people (one entry per open conversation); only hits and misses.
   */
  default Set<String> sizeWithheld() {
    return Set.of();
  }
}
