package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class VariantComparisonTest {

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
}
