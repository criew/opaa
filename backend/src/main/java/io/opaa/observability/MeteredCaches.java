package io.opaa.observability;

import com.github.benmanes.caffeine.cache.Cache;
import java.util.Map;

/**
 * A bean holding Caffeine caches whose hits, misses and evictions {@link CacheMetricsConfiguration}
 * publishes as {@code cache.*} with the tag {@code cache} (ADR-0071). Each cache records its
 * statistics ({@code Caffeine#recordStats()}); its name is a fixed word, never a key of the cache.
 * A cache keyed by person, token or conversation is not one: its figures follow active persons.
 */
public interface MeteredCaches {

  /** The caches of this bean by their fixed names. */
  Map<String, Cache<?, ?>> meteredCaches();
}
