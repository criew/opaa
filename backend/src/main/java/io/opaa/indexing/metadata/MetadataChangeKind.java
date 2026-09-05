package io.opaa.indexing.metadata;

import java.util.Optional;

/**
 * The planned schema change whose Folgekosten are asked for before it is saved (metadata-schema.md,
 * "Der Reindex-Preis, ehrlich ausgewiesen"). The domain twin of the API's {@code
 * MetadataChangeKind}, so no service reads a generated DTO type.
 */
public enum MetadataChangeKind {
  /** The field starts appearing in the Kontextpraefix - every chunk of a document with a value. */
  CONTEXT_PREFIX_ENABLED,
  /** The field stops appearing there - the same chunks, the same price. */
  CONTEXT_PREFIX_DISABLED,
  /** The field is deleted with its values, its chunk keys and its value list. */
  FIELD_REMOVED,
  /** One value of the list is removed with a confirmed mapping onto another value or "leer". */
  VALUE_REMOVED,
  /** The list is extended - no rueckwirkung on stored values, and therefore free. */
  VALUE_ADDED;

  public static Optional<MetadataChangeKind> fromName(String name) {
    for (MetadataChangeKind kind : values()) {
      if (kind.name().equals(name)) {
        return Optional.of(kind);
      }
    }
    return Optional.empty();
  }
}
