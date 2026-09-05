package io.opaa.indexing.metadata;

import io.opaa.api.types.LibraryMetadataFieldType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Detached library field definitions for tests that never touch the database - the mapper unit test
 * and the filter translation tests (#1071). Lives in the entity's own package because a field is
 * only constructible through its package-private constructor, which keeps every production path
 * going through {@link LibraryMetadataFieldService}.
 */
public final class LibraryMetadataFieldTestFixtures {

  private LibraryMetadataFieldTestFixtures() {}

  public static LibraryMetadataFieldDefinition definition(
      String fieldKey,
      String label,
      LibraryMetadataFieldType type,
      String valuePattern,
      boolean filter,
      boolean contextPrefix,
      Integer citationPosition,
      List<String> codes) {
    return definition(
        UUID.randomUUID(),
        fieldKey,
        label,
        type,
        valuePattern,
        filter,
        contextPrefix,
        citationPosition,
        codes);
  }

  public static LibraryMetadataFieldDefinition definition(
      UUID libraryId,
      String fieldKey,
      String label,
      LibraryMetadataFieldType type,
      String valuePattern,
      boolean filter,
      boolean contextPrefix,
      Integer citationPosition,
      List<String> codes) {
    LibraryMetadataField field =
        new LibraryMetadataField(libraryId, fieldKey, type, valuePattern, 10);
    field.apply(label, filter, contextPrefix, citationPosition);
    List<LibraryMetadataFieldValue> values = new ArrayList<>();
    int sortOrder = 0;
    for (String code : codes) {
      sortOrder += 10;
      values.add(new LibraryMetadataFieldValue(field.getId(), code, "Wert " + code, sortOrder));
    }
    return new LibraryMetadataFieldDefinition(field, values);
  }
}
