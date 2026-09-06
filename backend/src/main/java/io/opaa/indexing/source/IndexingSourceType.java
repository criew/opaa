package io.opaa.indexing.source;

import io.opaa.api.types.DocumentSourceType;

/**
 * The set of source types an indexing run can actually execute (ADR-0017). Deliberately narrower
 * than {@link DocumentSourceType}: {@code UPLOAD} is a document's origin, not a run type - it has
 * no {@link SourceIndexingExecutor} and must never be requestable via {@code
 * /api/v1/indexing/trigger}. Every value here has exactly one registered executor (see {@link
 * IndexingSourceExecutorRegistry}), and every {@link DocumentSourceType#hasIndexingRun()
 * run-bearing} document source type has exactly one value here, under the same name.
 */
public enum IndexingSourceType {
  FILESYSTEM,
  HTTP_DIRECTORY,
  RSS_FEED,
  CONFLUENCE;

  /** The origin every document a run of this type stores carries - the same name, by contract. */
  public DocumentSourceType documentSourceType() {
    return DocumentSourceType.valueOf(name());
  }

  /**
   * The run type for {@code sourceType}.
   *
   * @throws IllegalArgumentException for a type without a run ({@link
   *     DocumentSourceType#hasIndexingRun()} is {@code false})
   */
  public static IndexingSourceType of(DocumentSourceType sourceType) {
    if (!sourceType.hasIndexingRun()) {
      throw new IllegalArgumentException("no indexing run exists for " + sourceType);
    }
    return valueOf(sourceType.name());
  }
}
