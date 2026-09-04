package io.opaa.indexing.source;

import io.opaa.api.types.DocumentSourceType;

/**
 * The set of source types an indexing run can actually execute (ADR-0017). Deliberately narrower
 * than {@link DocumentSourceType}: {@code UPLOAD} is a document's origin, not a run type - it has
 * no {@link SourceIndexingExecutor} and must never be requestable via {@code
 * /api/v1/indexing/trigger}. Every value here has exactly one registered executor (see {@link
 * IndexingSourceExecutorRegistry}).
 */
public enum IndexingSourceType {
  FILESYSTEM,
  HTTP_DIRECTORY,
  RSS_FEED,
  CONFLUENCE
}
