package io.opaa.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A declarative, gepaarte Variantenvergleich (issue #1041, docs/features/retrieval-benchmark.md
 * §2): a named set of {@link PipelineVariant}s, all measured against the same golden dataset and
 * the same, already-indexed corpus, with one of them designated the reference variant every other
 * variant's delta is computed against.
 *
 * @param domain the {@link EvalDomainConfig#name()} this comparison runs against — the corpus and
 *     golden dataset the harness has already indexed and loaded when it runs a comparison.
 * @param referenceVariant the {@link PipelineVariant#name()} of the comparison's reference variant.
 *     Must be present among {@link #variants()} and must be executable (not declare {@link
 *     PipelineVariant#requiresReindex()} and not enable query decomposition) — every delta in the
 *     report is paired against it, so a skipped reference variant would leave every other variant's
 *     delta undefined.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VariantComparison(
    String name,
    String description,
    String domain,
    String referenceVariant,
    List<PipelineVariant> variants) {

  public VariantComparison {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("a variant comparison must have a non-blank name");
    }
    if (variants == null || variants.isEmpty()) {
      throw new IllegalArgumentException("variant comparison '" + name + "' declares no variants");
    }
    Set<String> names = new LinkedHashSet<>();
    for (PipelineVariant variant : variants) {
      if (!names.add(variant.name())) {
        throw new IllegalArgumentException(
            "variant comparison '"
                + name
                + "' declares the variant name '"
                + variant.name()
                + "' more than once");
      }
    }
    if (referenceVariant == null || !names.contains(referenceVariant)) {
      throw new IllegalArgumentException(
          "variant comparison '"
              + name
              + "' declares referenceVariant='"
              + referenceVariant
              + "', which is not among its variant names "
              + names);
    }
    variants = List.copyOf(variants);
  }

  /**
   * The variant named {@link #referenceVariant()} — always present, enforced by the constructor.
   */
  public PipelineVariant reference() {
    return variants.stream()
        .filter(v -> v.name().equals(referenceVariant))
        .findFirst()
        .orElseThrow();
  }
}
