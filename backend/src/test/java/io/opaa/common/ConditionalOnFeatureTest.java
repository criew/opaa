package io.opaa.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link ConditionalOnFeature} registers a bean unless its flag is switched off: a missing property
 * counts as on, on classes and on {@code @Bean} methods alike.
 */
class ConditionalOnFeatureTest {

  static class MetadataLeaf {}

  static class PromptLibrariesLeaf {}

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnFeature(Feature.METADATA)
  static class MetadataConfiguration {
    @Bean
    MetadataLeaf metadataLeaf() {
      return new MetadataLeaf();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class BeanMethodConfiguration {
    @Bean
    @ConditionalOnFeature(Feature.PROMPT_LIBRARIES)
    PromptLibrariesLeaf promptLibrariesLeaf() {
      return new PromptLibrariesLeaf();
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(MetadataConfiguration.class, BeanMethodConfiguration.class);

  @Test
  void aMissingPropertyKeepsTheBeans() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(MetadataLeaf.class);
          assertThat(context).hasSingleBean(PromptLibrariesLeaf.class);
        });
  }

  @Test
  void trueKeepsTheBeans() {
    runner
        .withPropertyValues(
            "opaa.features.metadata.enabled=true", "opaa.features.prompt-libraries.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(MetadataLeaf.class);
              assertThat(context).hasSingleBean(PromptLibrariesLeaf.class);
            });
  }

  @Test
  void falseRemovesTheAnnotatedClass() {
    runner
        .withPropertyValues("opaa.features.metadata.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(MetadataLeaf.class);
              assertThat(context).doesNotHaveBean(MetadataConfiguration.class);
              assertThat(context).hasSingleBean(PromptLibrariesLeaf.class);
            });
  }

  @Test
  void falseRemovesTheAnnotatedBeanMethod() {
    runner
        .withPropertyValues("opaa.features.prompt-libraries.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(PromptLibrariesLeaf.class);
              assertThat(context).hasSingleBean(MetadataLeaf.class);
            });
  }

  @Test
  void theEnvironmentVariableOfTheShippedApplicationYmlRemovesTheBean() {
    runner
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withSystemProperties("OPAA_FEATURES_METADATA_ENABLED=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(MetadataLeaf.class);
              assertThat(context).hasSingleBean(PromptLibrariesLeaf.class);
            });
  }
}
