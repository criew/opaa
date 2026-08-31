package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.query.QueryProperties;
import org.junit.jupiter.api.Test;

class VariantPrerequisitesTest {

  private static final QueryProperties PRODUCTION_LIKE =
      new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 2);

  private static PipelineVariant variant(boolean requiresReindex) {
    return new PipelineVariant("v", "desc", requiresReindex, PipelineVariant.QueryOverrides.NONE);
  }

  @Test
  void aVariantWithoutUnmetPrerequisitesCanRun() {
    assertThat(VariantPrerequisites.unmetReason(variant(false), PRODUCTION_LIKE)).isEmpty();
  }

  @Test
  void aReindexRequiringVariantIsSkipped() {
    var reason = VariantPrerequisites.unmetReason(variant(true), PRODUCTION_LIKE);

    assertThat(reason).isPresent();
    assertThat(reason.get()).contains("requiresReindex");
  }

  @Test
  void aVariantThatEnablesDecompositionIsSkippedForLackOfAChatModel() {
    var decompositionOn = new QueryProperties(8, 25, 1.0, 0.3, 1.0, true, 3, 2);

    var reason = VariantPrerequisites.unmetReason(variant(false), decompositionOn);

    assertThat(reason).isPresent();
    assertThat(reason.get()).contains("query-decomposition-enabled");
  }
}
