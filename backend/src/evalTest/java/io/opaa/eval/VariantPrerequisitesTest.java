package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.query.QueryProperties;
import org.junit.jupiter.api.Test;

class VariantPrerequisitesTest {

  private static final QueryProperties PRODUCTION_LIKE =
      new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 2, true);

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
    var decompositionOn = new QueryProperties(8, 25, 1.0, 0.3, 1.0, true, 3, 2, true);

    var reason = VariantPrerequisites.unmetReason(variant(false), decompositionOn);

    assertThat(reason).isPresent();
    assertThat(reason.get()).contains("query-decomposition-enabled");
  }

  /**
   * Issue #1049: over an incomplete full-text backfill the gate keeps the measured library out of
   * the lexical path entirely - the variant would measure the vector-only configuration under the
   * name of the hybrid one, and its Δ0.000 against a vector-only reference would read as "the
   * lexical path changes nothing".
   */
  @Test
  void aHybridVariantIsSkippedWhileTheFullTextBackfillIsIncomplete() {
    var reason = VariantPrerequisites.unmetReason(variant(false), PRODUCTION_LIKE, false);

    assertThat(reason).isPresent();
    assertThat(reason.get()).contains("Backfill");
  }

  /** The same variant with a complete backfill runs, and a vector-only variant always does. */
  @Test
  void theBackfillPrerequisiteOnlyConstrainsAVariantThatUsesTheLexicalPath() {
    var vectorOnly = new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 2, false);

    assertThat(VariantPrerequisites.unmetReason(variant(false), PRODUCTION_LIKE, true)).isEmpty();
    assertThat(VariantPrerequisites.unmetReason(variant(false), vectorOnly, false)).isEmpty();
  }
}
