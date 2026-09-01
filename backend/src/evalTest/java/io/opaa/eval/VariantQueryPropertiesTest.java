package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.query.QueryProperties;
import org.junit.jupiter.api.Test;

class VariantQueryPropertiesTest {

  private static final QueryProperties PRODUCTION =
      new QueryProperties(8, 25, 1.0, 0.3, 0.5, false, 3, 2, true);

  @Test
  void emptyOverridesReproduceTheProductionConfigurationExactly() {
    QueryProperties effective =
        VariantQueryProperties.apply(PRODUCTION, PipelineVariant.QueryOverrides.NONE);

    assertThat(effective).isEqualTo(PRODUCTION);
  }

  @Test
  void anOverriddenFieldReplacesOnlyThatField() {
    var overrides = new PipelineVariant.QueryOverrides(null, 0.7, null, null, null, null, null);

    QueryProperties effective = VariantQueryProperties.apply(PRODUCTION, overrides);

    assertThat(effective.mmrLambda()).isEqualTo(0.7);
    assertThat(effective.topK()).isEqualTo(PRODUCTION.topK());
    assertThat(effective.fetchK()).isEqualTo(PRODUCTION.fetchK());
    assertThat(effective.similarityThreshold()).isEqualTo(PRODUCTION.similarityThreshold());
    assertThat(effective.queryDecompositionEnabled())
        .isEqualTo(PRODUCTION.queryDecompositionEnabled());
    assertThat(effective.maxSubQueries()).isEqualTo(PRODUCTION.maxSubQueries());
    assertThat(effective.maxChunksPerDocument()).isEqualTo(PRODUCTION.maxChunksPerDocument());
  }

  @Test
  void permissionHistorySampleRateIsNeverOverridden() {
    var overrides = new PipelineVariant.QueryOverrides(null, null, null, null, null, null, null);

    QueryProperties effective = VariantQueryProperties.apply(PRODUCTION, overrides);

    assertThat(effective.permissionHistorySampleRate())
        .isEqualTo(PRODUCTION.permissionHistorySampleRate());
  }
}
