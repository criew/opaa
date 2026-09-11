package io.opaa.test;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Registers the two filesystem paths every class of this suite shares: the {@code FILESYSTEM}
 * quellentyp's allowlist and the upload storage directory.
 *
 * <p>The allowlist keeps the {@code dev} profile's {@code /data,/tmp} entries (fixtures elsewhere
 * in the suite name literal {@code /data/...} paths) and adds {@link OpaaTestDirectory#BASE_DIR},
 * so a class writing real files puts them under its own subdirectory of that base. A path outside
 * all three is still refused, which is what the allowlist tests assert.
 *
 * <p>Declared once, in the meta-annotation's own infrastructure: a class-local
 * {@code @DynamicPropertySource} would key that class to its own Spring context regardless of the
 * shared meta-annotation (see {@link OpaaIntegrationTest}'s Javadoc).
 */
final class OpaaTestPathInitializer
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
                "opaaTestPaths",
                Map.of(
                    "opaa.indexing.filesystem.allowlist",
                    "/data,/tmp," + OpaaTestDirectory.BASE_DIR.toAbsolutePath(),
                    "opaa.upload.storage-path",
                    OpaaTestDirectory.UPLOAD_STORAGE_DIR.toAbsolutePath().toString())));
  }
}
