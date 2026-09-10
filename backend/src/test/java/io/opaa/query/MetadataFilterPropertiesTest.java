package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * {@code opaa.query.metadata-filter.library-field-offer-threshold} is wired in the real {@code
 * application.yml} to {@code OPAA_QUERY_METADATA_FILTER_LIBRARY_FIELD_THRESHOLD} the same way its
 * two neighbours are. This test boots the actual application configuration (not a hand-written
 * property key) via {@link SpringApplicationBuilder}, with a {@link
 * SystemEnvironmentPropertySource} carrying only the variable under test standing in for the real
 * OS environment, proving the environment variable reaches {@link MetadataFilterProperties}.
 */
class MetadataFilterPropertiesTest {

  @Test
  void bindsLibraryFieldOfferThresholdFromTheEnvironmentVariable() {
    try (ConfigurableApplicationContext context =
        run(Map.of("OPAA_QUERY_METADATA_FILTER_LIBRARY_FIELD_THRESHOLD", "0.55"))) {
      assertThat(context.getBean(MetadataFilterProperties.class).libraryFieldOfferThreshold())
          .isEqualTo(0.55);
    }
  }

  @Test
  void defaultsLibraryFieldOfferThresholdWhenTheEnvironmentVariableIsUnset() {
    try (ConfigurableApplicationContext context = run(Map.of())) {
      assertThat(context.getBean(MetadataFilterProperties.class).libraryFieldOfferThreshold())
          .isEqualTo(0.75);
    }
  }

  /**
   * Boots {@link Config} with the real {@code application.yml} on the classpath, after replacing
   * the {@code systemEnvironment} property source with exactly the given entries - the real OS/JVM
   * environment stays out of the test, so a bound value only holds if this test set it.
   */
  private static ConfigurableApplicationContext run(Map<String, String> environmentVariables) {
    return new SpringApplicationBuilder(Config.class)
        .web(WebApplicationType.NONE)
        .initializers(
            context ->
                context
                    .getEnvironment()
                    .getPropertySources()
                    .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            new HashMap<>(environmentVariables))))
        .run();
  }

  @EnableConfigurationProperties(MetadataFilterProperties.class)
  private static class Config {}
}
