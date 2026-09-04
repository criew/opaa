package io.opaa.indexing.metadata;

/**
 * The three built-in core fields (metadata-schema.md, Teil II (a)) and their persisted {@code
 * field_key}. A library-defined field (#1071) gets its own key namespace beside these; the keys
 * here are pinned by {@code chk_document_metadata_values_core_field_type} in migration 018.
 */
public enum CoreMetadataField {
  TITLE("title"),
  DOCUMENT_TYPE("document_type"),
  DOCUMENT_DATE("document_date");

  private final String key;

  CoreMetadataField(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }
}
