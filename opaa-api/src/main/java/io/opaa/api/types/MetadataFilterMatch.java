package io.opaa.api.types;

/**
 * How a retrieved document related to the active metadata filter: every filtered field carried a
 * matching value, or at least one had none and the Leerwert rule kept the document.
 */
public enum MetadataFilterMatch {
  MATCHED,
  NO_VALUE
}
