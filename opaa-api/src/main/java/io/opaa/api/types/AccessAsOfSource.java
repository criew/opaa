package io.opaa.api.types;

/**
 * A rights source of the Stichtagsauskunft that carries no history yet (ADR-0036, Entscheidung 8).
 * Named in every answer so an empty result is read as "not on record" rather than as "nobody had
 * access" - the same honesty {@code beyondRetention} provides for the retention gap.
 */
public enum AccessAsOfSource {

  /** A system administrator reaches every library of their organization, unrecorded. */
  SYSTEM_ROLE
}
