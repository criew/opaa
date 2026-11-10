package io.opaa.observability;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds every cache of the {@link MeteredCaches} beans to the registry: {@code cache_gets_total}
 * with {@code result} {@code hit} or {@code miss}, {@code cache_evictions_total} and {@code
 * cache_size}, each with {@code cache}. Per instance (ADR-0053): every instance has its own caches.
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
                            (name, cache) -> CaffeineCacheMetrics.monitor(registry, cache, name)));
  }
}
