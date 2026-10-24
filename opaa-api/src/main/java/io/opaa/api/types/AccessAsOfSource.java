package io.opaa.api.types;

/**
 * A rights source of the Stichtagsauskunft without a history for the requested window (ADR-0036,
 * Entscheidung 8). Named in the answer so an empty result is read as "not on record" rather than as
 * "nobody had access" - the same honesty {@code beyondRetention} provides for the retention gap.
 */
public enum AccessAsOfSource {

  /** A system administrator reaches every library of their organization, unrecorded. */
  SYSTEM_ROLE,

  /**
   * Named for a window that begins before the account-state history became complete: until then
   * only the locks of the directory synchronisation were recorded, not a local account's lock or
   * expiry or a switched-off sign-in provider.
   */
  ACCOUNT_STATE
}
