package io.opaa.api.types;

/**
 * The type of a library metadata field (docs/features/metadata-schema.md, Teil II (b)
 * "Kontrolliertes Vokabular statt Freitext"): a choice from the field's own value list, a
 * year/date, or an identifier checked against a pattern carried by the field definition. There is
 * deliberately no free-text type - free text is a keyword with the appearance of structure.
 */
public enum LibraryMetadataFieldType {
  SELECT,
  DATE,
  PATTERN
}
