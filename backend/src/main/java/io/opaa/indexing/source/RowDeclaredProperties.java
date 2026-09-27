package io.opaa.indexing.source;

import io.opaa.indexing.format.DocumentProperties;
import io.opaa.knowledge.Document;

/**
 * Optional ability of a {@link SourceConnector}: the declared properties of its top-level documents
 * are what the stored row already holds, so the metadata backfill re-extracts them without a
 * download. A connector without it has its remote documents marked for their next run instead.
 */
public interface RowDeclaredProperties {

  /** The properties the ingest declared for top-level {@code document}, rebuilt from its row. */
  DocumentProperties declaredProperties(Document document);
}
