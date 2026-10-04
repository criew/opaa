package io.opaa.api.types;

/**
 * The kind of value a connection profile may set for one connector settings key (ADR-0038,
 * "Konnektoreigene Vorgaben am Profil"); the administration form shows a field per kind.
 */
public enum ProfileDefaultKind {
  /** A single line of text. */
  TEXT,
  /** Yes or no. */
  BOOLEAN,
  /** One of the declared choices. */
  CHOICE
}
