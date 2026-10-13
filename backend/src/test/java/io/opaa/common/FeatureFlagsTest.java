package io.opaa.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The installation-wide feature flags under {@code opaa.features.*}: every flag is on unless the
 * installation switches it off, through the shipped {@code application.yml} and its environment
 * variables as well as through any other property source.
 */
class FeatureFlagsTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(FeatureProperties.class)
  @Import(FeatureFlags.class)
  static class FlagsConfiguration {}

  private final ApplicationContextRunner bare =
      new ApplicationContextRunner().withUserConfiguration(FlagsConfiguration.class);

  private final ApplicationContextRunner withShippedApplicationYml =
      bare.withInitializer(new ConfigDataApplicationContextInitializer());

  @Test
  void everyFlagIsOnWithoutAnyConfiguration() {
    bare.run(
        context -> {
          FeatureFlags flags = context.getBean(FeatureFlags.class);
          for (Feature feature : Feature.values()) {
            assertThat(flags.isEnabled(feature)).as(feature.propertyKey()).isTrue();
          }
          assertThat(flags.asMap()).containsOnlyKeys(Feature.values()).doesNotContainValue(false);
        });
  }

  @Test
  void everyFlagIsOnWithTheShippedApplicationYml() {
    withShippedApplicationYml.run(
        context -> {
          FeatureFlags flags = context.getBean(FeatureFlags.class);
          assertThat(flags.asMap()).containsOnlyKeys(Feature.values()).doesNotContainValue(false);
        });
  }

  @Test
  void theEnvironmentVariablesOfTheShippedApplicationYmlSwitchFlagsOff() {
    withShippedApplicationYml
        .withSystemProperties(
            "OPAA_FEATURES_PROMPT_LIBRARIES_ENABLED=false", "OPAA_FEATURES_METADATA_ENABLED=false")
        .run(
            context -> {
              FeatureFlags flags = context.getBean(FeatureFlags.class);
              assertThat(flags.isEnabled(Feature.PROMPT_LIBRARIES)).isFalse();
              assertThat(flags.isEnabled(Feature.METADATA)).isFalse();
            });
  }

  @Test
  void oneFlagSwitchedOffLeavesTheOthersOn() {
    bare.withPropertyValues("opaa.features.metadata.enabled=false")
        .run(
            context -> {
              FeatureFlags flags = context.getBean(FeatureFlags.class);
              assertThat(flags.isEnabled(Feature.METADATA)).isFalse();
              assertThat(flags.isEnabled(Feature.PROMPT_LIBRARIES)).isTrue();
            });
  }

  @Test
  void anUnknownKeyUnderTheFeaturesPrefixDoesNotStopTheStart() {
    bare.withPropertyValues("opaa.features.unknown-thing.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(FeatureFlags.class).asMap()).doesNotContainValue(false);
            });
  }

  @Test
  void theShippedApplicationYmlNamesAnEnvironmentVariableForEveryFlag() {
    String[] variables =
        Arrays.stream(Feature.values())
            .map(
                feature ->
                    "OPAA_FEATURES_"
                        + feature.propertyKey().toUpperCase().replace('-', '_')
                        + "_ENABLED=false")
            .toArray(String[]::new);
    withShippedApplicationYml
        .withSystemProperties(variables)
        .run(
            context ->
                assertThat(context.getBean(FeatureFlags.class).asMap())
                    .containsOnlyKeys(Feature.values())
                    .doesNotContainValue(true));
  }

  @Test
  void thePropertyKeyIsTheKebabCaseOfTheConstant() {
    assertThat(Feature.PROMPT_LIBRARIES.propertyKey()).isEqualTo("prompt-libraries");
    assertThat(Feature.METADATA.propertyKey()).isEqualTo("metadata");
  }
}
