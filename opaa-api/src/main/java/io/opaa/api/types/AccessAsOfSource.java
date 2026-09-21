package io.opaa.api.types;

/**
 * A rights source of the Stichtagsauskunft that carries no history yet (ADR-0036, Entscheidung 8
 * lists eight sources; four of them are historised today). Named in every answer so an empty result
 * is read as "not on record" rather than as "nobody had access" - the same honesty {@code
 * beyondRetention} provides for the retention gap.
 */
public enum AccessAsOfSource {

  /** A system administrator reaches every library of their organization, unrecorded. */
  SYSTEM_ROLE,

  /** Ownership of an asset or a space (#1819). */
  OWNERSHIP,

  /** A capability (#1813) - it opens no content, but it is a source the ADR lists. */
  CAPABILITY,

  /** Whether an account was active or blocked at the time (#1818). */
  ACCOUNT_STATE
}
