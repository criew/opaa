package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.query.QueryProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class VariantComparisonTest {

  private static final QueryProperties PRODUCTION_LIKE =
      new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 2, true, 50);

  private static PipelineVariant variant(String name) {
    return new PipelineVariant(name, "desc", false, PipelineVariant.QueryOverrides.NONE);
  }

  @Test
  void rejectsAnEmptyVariantList() {
    assertThatThrownBy(() -> new VariantComparison("c", "desc", "domain", "a", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no variants");
  }

  @Test
  void rejectsDuplicateVariantNames() {
    assertThatThrownBy(
            () ->
                new VariantComparison(
                    "c", "desc", "domain", "a", List.of(variant("a"), variant("a"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("more than once");
  }

  @Test
  void rejectsAReferenceVariantThatIsNotAmongTheVariants() {
    assertThatThrownBy(
            () ->
                new VariantComparison(
                    "c", "desc", "domain", "missing", List.of(variant("a"), variant("b"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing");
  }

  @Test
  void referenceReturnsTheDeclaredReferenceVariant() {
    var comparison =
        new VariantComparison("c", "desc", "domain", "b", List.of(variant("a"), variant("b")));

    assertThat(comparison.reference().name()).isEqualTo("b");
  }

  @Test
  void rejectsANameContainingAPathSeparator() {
    assertThatThrownBy(
            () -> new VariantComparison("../evil", "desc", "domain", "a", List.of(variant("a"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("path separator");
    assertThatThrownBy(
            () -> new VariantComparison("evil\\name", "desc", "domain", "a", List.of(variant("a"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("path separator");
  }

  /**
   * Issue #1041 review, Befund 3/9: {@link VariantComparison#requireExecutableReference} is the
   * fail-fast check that lets the harness reject a broken comparison before indexing rather than
   * after — see {@code RetrievalEvaluationHarnessTest#loadAndValidateVariantComparison}.
   */
  @Test
  void requireExecutableReferenceAcceptsAnExecutableReferenceVariant() {
    var comparison =
        new VariantComparison("c", "desc", "domain", "a", List.of(variant("a"), variant("b")));

    assertThatCode(() -> comparison.requireExecutableReference(PRODUCTION_LIKE))
        .doesNotThrowAnyException();
  }

  @Test
  void requireExecutableReferenceRejectsAReindexRequiringReferenceVariant() {
    var reindexReference =
        new PipelineVariant("a", "desc", true, PipelineVariant.QueryOverrides.NONE);
    var comparison =
        new VariantComparison("c", "desc", "domain", "a", List.of(reindexReference, variant("b")));

    assertThatThrownBy(() -> comparison.requireExecutableReference(PRODUCTION_LIKE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Referenzvariante");
  }

  @Test
  void requireExecutableReferenceRejectsAReferenceVariantThatEnablesDecomposition() {
    var decompositionReference =
        new PipelineVariant(
            "a",
            "desc",
            false,
            new PipelineVariant.QueryOverrides(null, null, null, true, null, null, null, null));
    var comparison =
        new VariantComparison(
            "c", "desc", "domain", "a", List.of(decompositionReference, variant("b")));

    assertThatThrownBy(() -> comparison.requireExecutableReference(PRODUCTION_LIKE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Referenzvariante");
  }

  /**
   * Issue #1041 review (second round): a broken override must fail fast for <b>every</b> variant,
   * not only the reference — otherwise a non-reference variant's mistake would only surface once
   * {@link VariantRunner} reaches it mid-run, well after the corpus indexing this check exists to
   * happen before.
   */
  @Test
  void requireExecutableReferenceRejectsAnInvalidOverrideOnAnyVariantNotOnlyTheReference() {
    var invalidNonReference =
        new PipelineVariant(
            "b",
            "desc",
            false,
            // fetchK=5 is below PRODUCTION_LIKE's topK=8 — QueryProperties' own compact
            // constructor rejects that combination.
            new PipelineVariant.QueryOverrides(5, null, null, null, null, null, null, null));
    var comparison =
        new VariantComparison(
            "c", "desc", "domain", "a", List.of(variant("a"), invalidNonReference));

    assertThatThrownBy(() -> comparison.requireExecutableReference(PRODUCTION_LIKE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'b'")
        .hasMessageContaining("ungültige Konfiguration");
  }
}
