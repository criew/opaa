package io.opaa.indexing.metadata;

import io.opaa.api.types.LibraryMetadataSchemaChangeKind;
import java.util.List;
import java.util.Optional;

/**
 * One library field with its configured value list - the shape every read of the schema returns.
 * The list is schema, not an aggregate over documents: it exists whether or not a document carries
 * a value, and it is visible to everyone who may use the library (metadata-schema.md,
 * Rechte-Invariante, Ausnahme für die konfigurierte Werteliste). Empty for a DATE or PATTERN field.
 */
public record LibraryMetadataFieldDefinition(
    LibraryMetadataField field,
    List<LibraryMetadataFieldValue> values,
    List<LibraryMetadataSchemaChangeView> pendingChanges) {

  /** A field with nothing running on it - every read that does not look at the Nachlauf. */
  public LibraryMetadataFieldDefinition(
      LibraryMetadataField field, List<LibraryMetadataFieldValue> values) {
    this(field, values, List.of());
  }

  /** Whether the whole field is retired and disappears with its last emptied document. */
  public boolean deletionPending() {
    return pendingChanges.stream()
        .anyMatch(change -> change.kind() == LibraryMetadataSchemaChangeKind.FIELD_DELETION);
  }

  /** The running mapping of {@code code}, if that list entry is retired. */
  public Optional<LibraryMetadataSchemaChangeView> pendingRemapOf(String code) {
    return pendingChanges.stream().filter(change -> code.equals(change.valueCode())).findFirst();
  }
}
