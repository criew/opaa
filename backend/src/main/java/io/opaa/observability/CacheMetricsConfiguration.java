package io.opaa.observability;

import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds every cache of the {@link MeteredCaches} beans to the registry: {@code cache_gets_total}
 * with {@code result} {@code hit} or {@code miss}, {@code cache_evictions_total} and {@code
 * cache_size}, each with {@code cache}; a cache whose size is withheld gets only its hits and
 * misses. Per instance (ADR-0053): every instance has its own caches.
 */
@Configuration(proxyBeanMethods = false)
public class CacheMetricsConfiguration {

  @Bean
  MeterBinder meteredCachesBinder(ObjectProvider<MeteredCaches> owners) {
    return registry ->
        owners
            .orderedStream()
            .forEach(
                owner ->
                    owner
                        .meteredCaches()
                        .forEach(
                            (name, cache) -> {
                              if (owner.sizeWithheld().contains(name)) {
                                hitsAndMisses(registry, cache, name);
                              } else {
                                CaffeineCacheMetrics.monitor(registry, cache, name);
                              }
                            }));
  }

  private static void hitsAndMisses(MeterRegistry registry, Cache<?, ?> cache, String name) {
    FunctionCounter.builder("cache.gets", cache, c -> c.stats().hitCount())
        .tags("cache", name, "result", "hit")
        .description("The number of times cache lookup methods have returned a cached value.")
        .register(registry);
    FunctionCounter.builder("cache.gets", cache, c -> c.stats().missCount())
        .tags("cache", name, "result", "miss")
        .description("The number of times cache lookup methods have returned an uncached value.")
        .register(registry);
  }
}
