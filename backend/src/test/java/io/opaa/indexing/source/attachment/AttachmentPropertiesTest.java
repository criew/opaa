package io.opaa.indexing.source.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link AttachmentProperties} binds {@code maxDepth} under the general {@code
 * opaa.indexing.attachments.max-depth} key - the old, connector-specific {@code
 * opaa.indexing.mail.max-attachment-depth} no longer has any effect on it.
 */
class AttachmentPropertiesTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  void bindsMaxDepthFromTheGeneralKey() {
    contextRunner
        .withPropertyValues("opaa.indexing.attachments.max-depth=2")
        .run(
            context ->
                assertThat(context.getBean(AttachmentProperties.class).maxDepth()).isEqualTo(2));
  }

  @Test
  void defaultsToFiveWhenUnset() {
    contextRunner.run(
        context -> assertThat(context.getBean(AttachmentProperties.class).maxDepth()).isEqualTo(5));
  }

  @Test
  void bindsTheSharedCountAndSizeLimitsAndDefaultsThem() {
    contextRunner
        .withPropertyValues(
            "opaa.indexing.attachments.max-per-parent=3",
            "opaa.indexing.attachments.max-size-bytes=1024")
        .run(
            context -> {
              AttachmentProperties properties = context.getBean(AttachmentProperties.class);
              assertThat(properties.limits().maxPerParent()).isEqualTo(3);
              assertThat(properties.limits().maxSizeBytes()).isEqualTo(1024L);
            });
    contextRunner.run(
        context -> {
          AttachmentProperties properties = context.getBean(AttachmentProperties.class);
          assertThat(properties.limits().maxPerParent()).isEqualTo(10);
          assertThat(properties.limits().maxSizeBytes()).isEqualTo(20_971_520L);
        });
  }

  @Test
  void theOldMailSpecificKeyNoLongerHasAnyEffect() {
    contextRunner
        .withPropertyValues("opaa.indexing.mail.max-attachment-depth=2")
        .run(
            context ->
                assertThat(context.getBean(AttachmentProperties.class).maxDepth()).isEqualTo(5));
  }

  @EnableConfigurationProperties(AttachmentProperties.class)
  private static class Config {}
}
