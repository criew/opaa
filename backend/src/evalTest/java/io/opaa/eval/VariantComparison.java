package io.opaa.eval;

import io.opaa.query.QueryProperties;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A declarative, gepaarte Variantenvergleich (issue #1041, docs/features/retrieval-benchmark.md
 * §2): a named set of {@link PipelineVariant}s, all measured against the same golden dataset and
 * the same, already-indexed corpus, with one of them designated the reference variant every other
 * variant's delta is computed against.
 *
 * <p><b>Deliberately no {@code @JsonIgnoreProperties(ignoreUnknown = true)}</b> — see {@link
 * PipelineVariant}'s Javadoc for why a typo in a hand-authored comparison file must fail the load
 * rather than silently produce a comparison that never differs from production.
 *
 * @param name stable identifier; also used verbatim as part of the variant report's file name
 *     ({@code variant-report-<name>.json}), so it must not contain a path separator.
 * @param domain the {@link EvalDomainConfig#name()} this comparison runs against — the corpus and
 *     golden dataset the harness has already indexed and loaded when it runs a comparison.
 * @param referenceVariant the {@link PipelineVariant#name()} of the comparison's reference variant.
 *     Must be present among {@link #variants()}; {@link #requireExecutableReference} additionally
 *     checks that it would actually run under a given production configuration.
 */
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
    if (name.contains("/") || name.contains("\\")) {
      throw new IllegalArgumentException(
          "variant comparison name '"
              + name
              + "' must not contain a path separator — it becomes part of the report file name"
              + " (build/eval-reports/variant-report-<name>.json)");
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

  /**
   * Fails fast if {@link #reference()} would not actually run under {@code
   * productionQueryProperties} — every prerequisite {@link VariantPrerequisites} checks (issue
   * #1041 review, Befund 3/9). Cannot be enforced in the compact constructor above: the check needs
   * the harness's production {@link QueryProperties}, which is not known when a comparison is
   * merely parsed from its JSON file. Callers invoke this immediately after loading, before paying
   * for the (tens-of-minutes) corpus indexing the comparison would otherwise only fail after — the
   * same reasoning as {@code PipelineHarnessSupport#requireMeasurableConfiguration}.
   */
  public void requireExecutableReference(QueryProperties productionQueryProperties) {
    PipelineVariant reference = reference();
    QueryProperties effective =
        VariantQueryProperties.apply(productionQueryProperties, reference.queryOverrides());
    VariantPrerequisites.unmetReason(reference, effective)
        .ifPresent(
            reason -> {
              throw new IllegalStateException(
                  "Referenzvariante '"
                      + referenceVariant
                      + "' des Variantenvergleichs '"
                      + name
                      + "' ist nicht ausführbar: "
                      + reason
                      + " Jedes Delta dieses Berichts ist gegen sie gepaart, ein Bericht ohne "
                      + "ausführbare Referenzvariante ist sinnlos.");
            });
  }
}
