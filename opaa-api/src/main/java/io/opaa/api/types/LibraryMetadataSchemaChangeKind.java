package io.opaa.api.types;

/**
 * What a running schema change of a library does (metadata-schema.md, "Nachlauf im Betrieb",
 * #1361). Both kinds retire their subject first and work the affected documents off in Chargen; the
 * list entry respectively the field is removed only after the last document.
 */
public enum LibraryMetadataSchemaChangeKind {
  /**
   * Every document carrying one retired list value is rewritten onto another value or onto "leer".
   */
  VALUE_REMAP,
  /** Every document carrying any value of a retired field is emptied. */
  FIELD_DELETION
}
