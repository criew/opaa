package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@code opaa.query.metadata-filter.library-field-offer-threshold} is wired in {@code
 * application.yml} to {@code OPAA_QUERY_METADATA_FILTER_LIBRARY_FIELD_THRESHOLD} the same way its
 * two neighbours are - this test binds the identical placeholder expression to prove the
 * environment variable actually reaches {@link MetadataFilterProperties}.
 */
class MetadataFilterPropertiesTest {

  private static final String LIBRARY_FIELD_THRESHOLD_KEY =
      "opaa.query.metadata-filter.library-field-offer-threshold";
  private static final String LIBRARY_FIELD_THRESHOLD_PLACEHOLDER =
      LIBRARY_FIELD_THRESHOLD_KEY + "=${OPAA_QUERY_METADATA_FILTER_LIBRARY_FIELD_THRESHOLD:0.75}";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  void bindsLibraryFieldOfferThresholdFromTheEnvironmentVariable() {
    contextRunner
        .withSystemProperties("OPAA_QUERY_METADATA_FILTER_LIBRARY_FIELD_THRESHOLD=0.55")
        .withPropertyValues(LIBRARY_FIELD_THRESHOLD_PLACEHOLDER)
        .run(
            context ->
                assertThat(
                        context
                            .getBean(MetadataFilterProperties.class)
                            .libraryFieldOfferThreshold())
                    .isEqualTo(0.55));
  }

  @Test
  void defaultsLibraryFieldOfferThresholdWhenTheEnvironmentVariableIsUnset() {
    contextRunner
        .withPropertyValues(LIBRARY_FIELD_THRESHOLD_PLACEHOLDER)
        .run(
            context ->
                assertThat(
                        context
                            .getBean(MetadataFilterProperties.class)
                            .libraryFieldOfferThreshold())
                    .isEqualTo(0.75));
  }

  @EnableConfigurationProperties(MetadataFilterProperties.class)
  private static class Config {}
}
