package io.opaa.test;

import io.opaa.group.sync.DirectoryClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the production {@link DirectoryClient} bean with a {@link FakeDirectoryClient} - every
 * class importing this exact configuration (instead of declaring its own class-local
 * {@code @TestConfiguration}/{@code @Import}) contributes an identical entry to the Spring context
 * cache key (Issue #903, mirrors {@link OpaaIndexingMockConfiguration}), so those classes share one
 * context and one Testcontainers Postgres instead of each booting its own.
 *
 * <p><b>A class importing this configuration must also carry
 * {@code @TestExecutionListeners(listeners = DirectorySyncMockResetListener.class, mergeMode =
 * MERGE_WITH_DEFAULTS)}</b> - unlike {@link OpaaIndexingMockConfiguration}, this configuration is
 * not wrapped in its own meta-annotation that could wire the listener in once, so every importer
 * repeats the declaration. Without it, a test method that does not itself call {@code
 * respondWith()}/{@code failWith()} silently inherits whatever a previous test (in this class or a
 * sibling class sharing the same context) last left the shared {@link FakeDirectoryClient}
 * configured to return.
 */
// proxyBeanMethods left at its default (true), matching OpaaIndexingMockConfiguration - this class
// declares only the one @Bean method, so the CGLIB proxy this default builds has nothing to
// intercept and makes no behavioural difference here, but keeping both sibling configs identical
// avoids an unexplained divergence a reader would otherwise have to investigate.
@TestConfiguration
public class DirectorySyncMockConfiguration {

  @Bean
  @Primary
  FakeDirectoryClient fakeDirectoryClient() {
    return new FakeDirectoryClient();
  }
}
