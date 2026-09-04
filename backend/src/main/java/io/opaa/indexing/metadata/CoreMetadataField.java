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

  /** The core field behind {@code key}, or empty for anything else (a library field, a typo). */
  public static java.util.Optional<CoreMetadataField> fromKey(String key) {
    for (CoreMetadataField field : values()) {
      if (field.key.equals(key)) {
        return java.util.Optional.of(field);
      }
    }
    return java.util.Optional.empty();
  }

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
