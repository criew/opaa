package io.opaa.indexing.metadata;

/**
 * Whether a {@link DocumentMetadataValue} row carries a value ({@code SET}) or records that a
 * person found there is none to find ({@code NOT_DETERMINABLE}, metadata-schema.md "Kein Wert
 * ermittelbar"). The plain empty state is the absence of a row, not a third constant.
 */
public enum MetadataValueState {
  SET,
  NOT_DETERMINABLE
}
