package io.opaa.indexing.metadata;

import java.util.Set;

/**
 * The chunk metadata keys carrying a document's filterable core fields (ADR-0024): written by
 * {@code FileProcessingService#storeChunks} onto every chunk of a document and rewritten in place
 * by {@code VectorChunkStore#updateDocumentMetadata} when a value changes - both search paths can
 * then carry the same condition without re-embedding. The title is deliberately absent: it is not
 * filterable, and the Beleg reads it from the document.
 */
public final class CoreMetadataChunkKeys {

  public static final String DOCUMENT_TYPE = "doc_type";
  public static final String DOCUMENT_DATE = "doc_date";
  public static final String DOCUMENT_DATE_PRECISION = "doc_date_precision";

  public static final Set<String> ALL =
      Set.of(DOCUMENT_TYPE, DOCUMENT_DATE, DOCUMENT_DATE_PRECISION);

  private CoreMetadataChunkKeys() {}
}
