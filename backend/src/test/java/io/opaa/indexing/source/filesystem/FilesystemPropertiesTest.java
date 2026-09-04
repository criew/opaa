package io.opaa.indexing.source.filesystem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * #1271: {@link FilesystemProperties} binds under {@code opaa.indexing.filesystem.allowlist} - the
 * old, flat {@code opaa.indexing.filesystem-allowlist} key (pre-#1271) no longer has any effect.
 */
class FilesystemPropertiesTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  void bindsTheAllowlistFromTheNewNamespacedKey() {
    contextRunner
        .withPropertyValues("opaa.indexing.filesystem.allowlist=/data,/tmp")
        .run(
            context ->
                assertThat(context.getBean(FilesystemProperties.class).allowlist())
                    .containsExactly("/data", "/tmp"));
  }

  @Test
  void theOldFlatKeyNoLongerHasAnyEffect() {
    contextRunner
        .withPropertyValues("opaa.indexing.filesystem-allowlist=/data,/tmp")
        .run(
            context ->
                assertThat(context.getBean(FilesystemProperties.class).allowlist())
                    .isEqualTo(List.of()));
  }

  @EnableConfigurationProperties(FilesystemProperties.class)
  private static class Config {}
}
