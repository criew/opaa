package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.query.QueryProperties;
import org.junit.jupiter.api.Test;

class VariantPrerequisitesTest {

  private static final QueryProperties PRODUCTION_LIKE =
      new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 2, true, 50);

  private static PipelineVariant variant(boolean requiresReindex) {
    return new PipelineVariant("v", "desc", requiresReindex, PipelineVariant.QueryOverrides.NONE);
  }

  @Test
  void aVariantWithoutUnmetPrerequisitesCanRun() {
    assertThat(VariantPrerequisites.unmetReason(variant(false), PRODUCTION_LIKE, true)).isEmpty();
  }

  @Test
  void aReindexRequiringVariantIsSkipped() {
    var reason = VariantPrerequisites.unmetReason(variant(true), PRODUCTION_LIKE, true);

    assertThat(reason).isPresent();
    assertThat(reason.get()).contains("requiresReindex");
  }

  @Test
  void aVariantThatEnablesDecompositionIsSkippedForLackOfAChatModel() {
    var decompositionOn = new QueryProperties(8, 25, 1.0, 0.3, 1.0, true, 3, 2, true, 50);

    var reason = VariantPrerequisites.unmetReason(variant(false), decompositionOn, false);

    assertThat(reason).isPresent();
    assertThat(reason.get()).contains("query-decomposition-enabled");
  }

  /**
   * Issue #1085: with the pinned chat model installed, the same variant is measurable — the
   * prerequisite constrains the missing model, not the decomposition itself.
   */
  @Test
  void aVariantThatEnablesDecompositionRunsOnceAChatModelIsAvailable() {
    var decompositionOn = new QueryProperties(8, 25, 1.0, 0.3, 1.0, true, 3, 2, true, 50);

    assertThat(VariantPrerequisites.unmetReason(variant(false), decompositionOn, true)).isEmpty();
    assertThat(VariantPrerequisites.unmetReason(variant(false), decompositionOn, true, true, false))
        .isEmpty();
  }

  /** The full overload keeps the chat-model prerequisite ahead of the later, index-bound ones. */
  @Test
  void theFullOverloadStillSkipsADecomposingVariantWithoutAChatModel() {
    var decompositionOn = new QueryProperties(8, 25, 1.0, 0.3, 1.0, true, 3, 2, true, 50);

    var reason =
        VariantPrerequisites.unmetReason(variant(false), decompositionOn, false, true, false);

    assertThat(reason).isPresent();
    assertThat(reason.get()).contains("Chat-Modell");
  }

  /**
   * Issue #1049: over an incomplete full-text backfill the gate keeps the measured library out of
   * the lexical path entirely - the variant would measure the vector-only configuration under the
   * name of the hybrid one, and its Δ0.000 against a vector-only reference would read as "the
   * lexical path changes nothing".
   */
  @Test
  void aHybridVariantIsSkippedWhileTheFullTextBackfillIsIncomplete() {
    var reason =
        VariantPrerequisites.unmetReason(variant(false), PRODUCTION_LIKE, true, false, false);

    assertThat(reason).isPresent();
    assertThat(reason.get()).contains("Backfill");
  }

  /** The same variant with a complete backfill runs, and a vector-only variant always does. */
  @Test
  void theBackfillPrerequisiteOnlyConstrainsAVariantThatUsesTheLexicalPath() {
    var vectorOnly = new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 2, false, 50);

    assertThat(VariantPrerequisites.unmetReason(variant(false), PRODUCTION_LIKE, true, true, false))
        .isEmpty();
    assertThat(VariantPrerequisites.unmetReason(variant(false), vectorOnly, true, false, false))
        .isEmpty();
  }

  /**
   * Issue #1050: only a variant that states a rerank window claims to rerank. The shipped
   * configuration carries a window of 50 with the role switched off - reading the effective value
   * instead of the declared override would skip every variant of every comparison.
   */
  @Test
  void theRerankPrerequisiteOnlyConstrainsAVariantThatDeclaresAWindow() {
    assertThat(VariantPrerequisites.unmetReason(variant(false), PRODUCTION_LIKE, true, true, false))
        .isEmpty();
    assertThat(
            VariantPrerequisites.unmetReason(rerankVariant(50), PRODUCTION_LIKE, true, true, true))
        .isEmpty();
    assertThat(
            VariantPrerequisites.unmetReason(rerankVariant(0), PRODUCTION_LIKE, true, true, false))
        .isEmpty();
  }

  @Test
  void aRerankingVariantIsSkippedWithoutAUsableRerankRole() {
    var reason =
        VariantPrerequisites.unmetReason(rerankVariant(50), PRODUCTION_LIKE, true, true, false);

    assertThat(reason).isPresent();
    assertThat(reason.get()).contains("Rerank-Modellrolle");
  }

  private static PipelineVariant rerankVariant(int window) {
    return new PipelineVariant(
        "rerank-" + window,
        "desc",
        false,
        new PipelineVariant.QueryOverrides(null, null, null, null, null, null, null, window));
  }
}
