package io.opaa.indexing.metadata;

import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.MetadataOrigin;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The effective core fields of one document as the Beleg and the chunk metadata see them: every
 * field nullable, each with the origin of its value. A {@code NOT_DETERMINABLE} row reads as empty
 * here (metadata-schema.md: for filter and Beleg it behaves like a Leerwert).
 */
public record CoreMetadata(
    String title,
    MetadataOrigin titleOrigin,
    String documentTypeCode,
    String documentTypeLabel,
    MetadataOrigin documentTypeOrigin,
    LocalDate documentDate,
    DatePrecision documentDatePrecision,
    MetadataOrigin documentDateOrigin) {

  public static final CoreMetadata EMPTY =
      new CoreMetadata(null, null, null, null, null, null, null, null);

  public boolean isEmpty() {
    return title == null && documentTypeCode == null && documentDate == null;
  }

  /**
   * The filterable values under {@link CoreMetadataChunkKeys} - only the keys with a value, so an
   * empty field is the absence of the key on the chunk exactly as it is the absence of the row.
   */
  public Map<String, Object> chunkMetadata() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    if (documentTypeCode != null) {
      metadata.put(CoreMetadataChunkKeys.DOCUMENT_TYPE, documentTypeCode);
    }
    if (documentDate != null) {
      metadata.put(CoreMetadataChunkKeys.DOCUMENT_DATE, documentDate.toString());
      metadata.put(CoreMetadataChunkKeys.DOCUMENT_DATE_PRECISION, documentDatePrecision.name());
    }
    return metadata;
  }
}
