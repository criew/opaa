package io.opaa.test;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Registers {@code opaa.indexing.filesystem.allowlist} for every class carrying {@link
 * OpaaIndexingIntegrationTest}, pointing at the single, process-wide {@link
 * OpaaIndexingTestDirectory#BASE_DIR}.
 *
 * <p>A class-local {@code @DynamicPropertySource} method would key that class to its own Spring
 * context regardless of a shared meta-annotation (see {@link OpaaIntegrationTest}'s Javadoc); this
 * initializer is declared exactly once, in the meta-annotation's own infrastructure, so every class
 * sharing this signature contributes the identical initializer class to the context cache key
 * instead.
 */
final class OpaaIndexingFilesystemAllowlistInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  @Override
  public void initialize(ConfigurableApplicationContext applicationContext) {
    // Not TestPropertySourceUtils.addInlinedPropertiesToEnvironment: it parses its arguments as
    // java.util.Properties "key=value" text, which unescapes a lone backslash - silently mangling
    // a Windows absolute path (e.g. "C:\Users\..." loses every backslash). A MapPropertySource
    // carries the path as an opaque String instead, sidestepping that parsing entirely.
    applicationContext
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "opaaIndexingFilesystemAllowlist",
                Map.of(
                    "opaa.indexing.filesystem.allowlist",
                    OpaaIndexingTestDirectory.BASE_DIR.toAbsolutePath().toString())));
  }
}
