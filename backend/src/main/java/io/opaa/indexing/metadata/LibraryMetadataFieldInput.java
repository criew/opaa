package io.opaa.indexing.metadata;

import io.opaa.api.types.LibraryMetadataFieldType;
import java.util.List;

/**
 * What a person states when defining a library field. {@code filter} and {@code contextPrefix} are
 * the two retrieval effects, at least one of which must be named - the Aufnahmeregel of
 * metadata-schema.md; {@code citationPosition} (1 or 2, or {@code null}) is the optional
 * Beleg-Anzeige, never a field's only effect.
 *
 * @param valuePattern required for {@link LibraryMetadataFieldType#PATTERN}, rejected otherwise
 * @param values the initial value list of a SELECT field, rejected for the other two types
 */
public record LibraryMetadataFieldInput(
    String fieldKey,
    String label,
    LibraryMetadataFieldType type,
    String valuePattern,
    boolean filter,
    boolean contextPrefix,
    Integer citationPosition,
    List<LibraryFieldValueInput> values) {

  public LibraryMetadataFieldInput {
    values = values == null ? List.of() : List.copyOf(values);
  }

  /** One entry of a SELECT field's value list: its stable code and its German label. */
  public record LibraryFieldValueInput(String code, String label) {}
}
