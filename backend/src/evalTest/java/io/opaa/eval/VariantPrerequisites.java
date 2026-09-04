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
   * The prerequisites decidable before the corpus is indexed — everything that follows from the
   * variant's declaration and its effective {@link QueryProperties} alone. {@link
   * VariantComparison#requireExecutableReference} uses this overload, because at that point no
   * index exists to ask about the full-text index.
   *
   * @param effective the variant's {@link QueryProperties} after {@link
   *     VariantQueryProperties#apply} — prerequisites are checked against what would actually run,
   *     not against the declared overrides alone.
   * @param chatModelAvailable whether this run has a systemwide active chat model at all (issue
   *     #1085, {@link EvalChatModel}) — without one, {@code QueryDecompositionService#decompose}
   *     fails per query and falls back to single-query retrieval.
   * @return the skip reason, or empty if the variant can be measured.
   */
  static Optional<String> unmetReason(
      PipelineVariant variant, QueryProperties effective, boolean chatModelAvailable) {
    if (variant.requiresReindex()) {
      return Optional.of(
          "Diese Variante deklariert requiresReindex=true (Embedding-Modell- oder "
              + "Chunking-Änderung), aber diese Variantenmechanik führt noch keinen Reindex je "
              + "Variante aus (docs/features/retrieval-benchmark.md, Umsetzungsschnitt Schritt B) — "
              + "sie misst ausschließlich Query-Parameter-Varianten auf dem bereits indizierten "
              + "Korpus.");
    }
    if (effective.queryDecompositionEnabled() && !chatModelAvailable) {
      return Optional.of(
          "Diese Variante aktiviert query-decomposition-enabled, aber dieser Lauf hat kein aktives "
              + "Chat-Modell (siehe EvalChatModel und "
              + "PipelineHarnessSupport#requireMeasurableConfiguration) — eine Zerlegung würde je "
              + "Anfrage fehlschlagen und still auf Einzelanfragen-Retrieval zurückfallen.");
    }
    return Optional.empty();
  }

  /**
   * The full set of prerequisites, including the one only an existing index can answer (issue
   * #1049): a variant that runs the lexical path needs the measured library's full-text index to be
   * complete, because chunks missing from it are invisible to that path. Such a variant would
   * measure a diminished lexical contribution under a name that promises the full hybrid one — and
   * a Δ near zero against the vector-only reference would read as "the lexical path changes
   * nothing", the strongest possible wrong conclusion this comparison could produce.
   *
   * <p>Since issue #1050 it also covers the rerank role: a variant that <b>declares</b> a non-zero
   * rerank candidate window needs a usable rerank model role, for exactly the same reason - it
   * would otherwise measure the configuration without reranking under the name of the one with it.
   * Deliberately the declared override, not the effective value: the shipped configuration carries
   * a candidate window of 50 with the role switched off, and a variant that merely inherits it
   * claims nothing about reranking.
   */
  static Optional<String> unmetReason(
      PipelineVariant variant,
      QueryProperties effective,
      boolean chatModelAvailable,
      boolean fullTextIndexComplete,
      boolean rerankRoleUsable) {
    Optional<String> earlier = unmetReason(variant, effective, chatModelAvailable);
    if (earlier.isPresent()) {
      return earlier;
    }
    Integer declaredRerankWindow = variant.queryOverrides().rerankCandidateCount();
    if (declaredRerankWindow != null && declaredRerankWindow > 0 && !rerankRoleUsable) {
      return Optional.of(
          "Diese Variante lässt die Rerank-Stufe laufen, aber die Rerank-Modellrolle ist in "
              + "diesem Lauf nicht nutzbar (ausgeschaltet, unbelegt oder nicht erreichbar – "
              + "siehe io.opaa.llm.RerankModelRole#status). Die Variante würde die "
              + "Konfiguration ohne Reranking unter dem Namen der mit Reranking messen.");
    }
    if (effective.fullTextSearchEnabled() && !fullTextIndexComplete) {
      return Optional.of(
          "Diese Variante lässt den lexikalischen Pfad laufen, aber der Volltextindex der "
              + "gemessenen Bibliothek ist unvollständig — die fehlenden Abschnitte sind für "
              + "diesen Pfad unsichtbar. Die Variante würde einen geschmälerten lexikalischen "
              + "Beitrag unter dem Namen der vollen hybriden Konfiguration messen.");
    }
    return Optional.empty();
  }
}
