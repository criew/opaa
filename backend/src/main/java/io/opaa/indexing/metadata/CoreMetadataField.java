package io.opaa.indexing.metadata;

/**
 * The three built-in core fields (metadata-schema.md, Teil II (a)), their persisted {@code
 * field_key} and the German label every display of the field uses. A library-defined field (#1071)
 * gets its own key namespace beside these; the keys here are pinned by {@code
 * chk_document_metadata_values_core_field_type} in migration 018.
 */
public enum CoreMetadataField {
  TITLE("title", "Titel"),
  DOCUMENT_TYPE("document_type", "Dokumentart"),
  DOCUMENT_DATE("document_date", "Datum/Stand");

  private final String key;
  private final String label;

  CoreMetadataField(String key, String label) {
    this.key = key;
    this.label = label;
  }

  public String key() {
    return key;
  }

  public String label() {
    return label;
  }
}
