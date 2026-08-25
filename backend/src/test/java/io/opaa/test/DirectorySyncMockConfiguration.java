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
 */
@TestConfiguration(proxyBeanMethods = false)
public class DirectorySyncMockConfiguration {

  @Bean
  @Primary
  FakeDirectoryClient fakeDirectoryClient() {
    return new FakeDirectoryClient();
  }
}
