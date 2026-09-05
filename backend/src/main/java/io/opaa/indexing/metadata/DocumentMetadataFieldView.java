package io.opaa.indexing.metadata;

import io.opaa.api.types.LibraryMetadataFieldType;

/**
 * One schema field of a document as the metadata view shows it: key and German label, the current
 * row ({@code null} when empty), the German display form and, for a manual value, the display name
 * of the person who set it ({@code null} when the account no longer exists). {@code
 * libraryFieldType} is set exactly for a library field (#1071) and tells a client which editor to
 * offer; a core field leaves it {@code null}.
 */
public record DocumentMetadataFieldView(
    String fieldKey,
    String label,
    LibraryMetadataFieldType libraryFieldType,
    MetadataValueSnapshot value,
    String displayValue,
    String actorDisplayName) {

  public static DocumentMetadataFieldView ofCore(
      CoreMetadataField field,
      MetadataValueSnapshot value,
      String displayValue,
      String actorDisplayName) {
    return new DocumentMetadataFieldView(
        field.key(), field.label(), null, value, displayValue, actorDisplayName);
  }

  public static DocumentMetadataFieldView ofLibraryField(
      LibraryMetadataField field,
      MetadataValueSnapshot value,
      String displayValue,
      String actorDisplayName) {
    return new DocumentMetadataFieldView(
        field.documentFieldKey(),
        field.getLabel(),
        field.getType(),
        value,
        displayValue,
        actorDisplayName);
  }
}
