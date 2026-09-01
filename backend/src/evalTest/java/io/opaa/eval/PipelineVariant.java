package io.opaa.eval;

/**
 * A named, versioned pipeline-path configuration used as a Variantenvergleich subject (issue #1041,
 * docs/features/retrieval-benchmark.md §2, "Anforderungen an die Umsetzung"). Data, not code: a new
 * comparison is a new entry in a JSON file under {@code eval/variants/}, never a new Java class or
 * test method — see {@link VariantComparisonDataset}.
 *
 * <p><b>Deliberately no {@code @JsonIgnoreProperties(ignoreUnknown = true)}</b> here, on {@link
 * QueryOverrides} or on {@link VariantComparison} (issue #1041 review, Befund 2): every field this
 * schema recognizes is measurement-affecting or a prerequisite gate. A typo like {@code "mmrLamda"}
 * would otherwise be silently dropped, the variant would run unchanged against the production
 * configuration, and would surface as a legitimate-looking Δ0.000 comparison point — exactly the
 * silent degradation the specification forbids for a stated override. Jackson now fails the load
 * instead (see {@code VariantComparisonDatasetTest}).
 *
 * @param name stable identifier, unique within its {@link VariantComparison}.
 * @param description short statement of what the variant is for.
 * @param requiresReindex whether this variant would need a different index (embedding model,
 *     chunking) rather than only different query-time parameters. This harness measures
 *     query-parameter variants only; a variant declaring {@code true} here is reported "nicht
 *     ausgeführt" by {@link VariantPrerequisites} rather than silently measured against the wrong
 *     index (the specification's explicit requirement — a future issue can add the reindex path
 *     this field already anticipates).
 * @param queryOverrides parameter overrides applied on top of the production {@link
 *     io.opaa.query.QueryProperties}; every field left unset there means "use the production
 *     value".
 */
public record PipelineVariant(
    String name, String description, boolean requiresReindex, QueryOverrides queryOverrides) {

  public PipelineVariant {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("a pipeline variant must have a non-blank name");
    }
    if (queryOverrides == null) {
      queryOverrides = QueryOverrides.NONE;
    }
  }

  /**
   * Partial override of {@link io.opaa.query.QueryProperties}. Every field is nullable/boxed:
   * {@code null} means "inherit the production value", not zero. {@code topK} is deliberately not a
   * field here — the pipeline path's metric component names ({@code hitRateAt5}, {@code ndcgAt8},
   * …) are pinned to the production window (docs/features/retrieval-benchmark.md §1, "Folgen für
   * Messvertrag und Baselines", 4.), and a variant that changed it would need a new window and new
   * component names, not a silently relabeled report.
   */
  public record QueryOverrides(
      Integer fetchK,
      Double mmrLambda,
      Double similarityThreshold,
      Boolean queryDecompositionEnabled,
      Integer maxSubQueries,
      Integer maxChunksPerDocument,
      Boolean fullTextSearchEnabled) {

    /** The reference variant's overrides: every field unset, i.e. the production configuration. */
    public static final QueryOverrides NONE =
        new QueryOverrides(null, null, null, null, null, null, null);
  }
}
