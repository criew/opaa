package io.opaa.eval;

import io.opaa.query.QueryProperties;

/**
 * Applies a {@link PipelineVariant.QueryOverrides} on top of the production {@link QueryProperties}
 * (issue #1041). A field left {@code null} in the overrides keeps the production value unchanged;
 * {@code topK} and {@code permissionHistorySampleRate} are never overridden — the former because
 * the pipeline path's metric component names are pinned to it (see {@link
 * PipelineVariant.QueryOverrides}'s Javadoc), the latter because it governs a compliance sampling
 * decision unrelated to retrieval quality (docs/features/security-and-compliance.md) that a
 * retrieval variant has no business changing.
 */
final class VariantQueryProperties {

  private VariantQueryProperties() {}

  static QueryProperties apply(
      QueryProperties production, PipelineVariant.QueryOverrides overrides) {
    return new QueryProperties(
        production.topK(),
        overrides.fetchK() != null ? overrides.fetchK() : production.fetchK(),
        overrides.mmrLambda() != null ? overrides.mmrLambda() : production.mmrLambda(),
        overrides.similarityThreshold() != null
            ? overrides.similarityThreshold()
            : production.similarityThreshold(),
        production.permissionHistorySampleRate(),
        overrides.queryDecompositionEnabled() != null
            ? overrides.queryDecompositionEnabled()
            : production.queryDecompositionEnabled(),
        overrides.maxSubQueries() != null ? overrides.maxSubQueries() : production.maxSubQueries(),
        overrides.maxChunksPerDocument() != null
            ? overrides.maxChunksPerDocument()
            : production.maxChunksPerDocument(),
        overrides.fullTextSearchEnabled() != null
            ? overrides.fullTextSearchEnabled()
            : production.fullTextSearchEnabled());
  }
}
