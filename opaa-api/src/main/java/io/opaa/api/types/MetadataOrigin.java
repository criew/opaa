package io.opaa.api.types;

/**
 * Where a document metadata value came from (ADR-0024): {@code DETERMINISTIC} from a parser/regex
 * rule, {@code DERIVED} from a model call (carries a confidence), {@code MANUAL} set by a person
 * (never overwritten by extraction).
 */
public enum MetadataOrigin {
  DETERMINISTIC,
  DERIVED,
  MANUAL
}
