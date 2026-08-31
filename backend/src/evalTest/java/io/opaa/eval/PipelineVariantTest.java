package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PipelineVariantTest {

  @Test
  void aBlankNameIsRejected() {
    assertThatThrownBy(
            () -> new PipelineVariant(" ", "desc", false, PipelineVariant.QueryOverrides.NONE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNullQueryOverridesNormalizesToNone() {
    var variant = new PipelineVariant("v", "desc", false, null);

    assertThat(variant.queryOverrides()).isEqualTo(PipelineVariant.QueryOverrides.NONE);
  }
}
