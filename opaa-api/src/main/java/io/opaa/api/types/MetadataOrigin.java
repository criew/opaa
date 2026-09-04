package io.opaa.api.types;

/**
 * Where a document metadata value came from (docs/features/metadata-schema.md, "Jeder Wert trägt
 * seine Herkunft"): {@code DETERMINISTIC} from a parser/regex rule, {@code DERIVED} from a model
 * call (carries a confidence), {@code MANUAL} set by a person (never overwritten by extraction).
 */
public enum MetadataOrigin {
  DETERMINISTIC,
  DERIVED,
  MANUAL
}
