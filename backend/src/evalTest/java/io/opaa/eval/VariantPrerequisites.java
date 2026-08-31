package io.opaa.eval;

import io.opaa.query.QueryProperties;
import java.util.Optional;

/**
 * Decides whether a {@link PipelineVariant} can actually be measured (issue #1041,
 * docs/features/retrieval-benchmark.md §2: "Varianten mit nicht erfüllten Voraussetzungen werden
 * übersprungen, nicht stillschweigend degradiert"). A variant with an unmet prerequisite is never
 * run — {@link VariantRunner} reports it as skipped instead of measuring a configuration that would
 * silently fall back to something other than what its name promises.
 */
final class VariantPrerequisites {

  private VariantPrerequisites() {}

  /**
   * @param effective the variant's {@link QueryProperties} after {@link
   *     VariantQueryProperties#apply} — prerequisites are checked against what would actually run,
   *     not against the declared overrides alone.
   * @return the skip reason, or empty if the variant can be measured.
   */
  static Optional<String> unmetReason(PipelineVariant variant, QueryProperties effective) {
    if (variant.requiresReindex()) {
      return Optional.of(
          "Diese Variante deklariert requiresReindex=true (Embedding-Modell- oder "
              + "Chunking-Änderung), aber diese Variantenmechanik führt noch keinen Reindex je "
              + "Variante aus (docs/features/retrieval-benchmark.md, Umsetzungsschnitt Schritt B) — "
              + "sie misst ausschließlich Query-Parameter-Varianten auf dem bereits indizierten "
              + "Korpus.");
    }
    if (effective.queryDecompositionEnabled()) {
      return Optional.of(
          "Diese Variante aktiviert query-decomposition-enabled, aber der Harness-Kontext hat kein "
              + "Chat-Modell (siehe PipelineHarnessSupport#requireMeasurableConfiguration) — eine "
              + "Zerlegung würde je Anfrage fehlschlagen und still auf Einzelanfragen-Retrieval "
              + "zurückfallen. Welches Modell der Pipeline-Pfad künftig dafür nutzen soll, ist offen "
              + "(docs/features/retrieval-benchmark.md, \"Offene Punkte\" 3).");
    }
    return Optional.empty();
  }
}
