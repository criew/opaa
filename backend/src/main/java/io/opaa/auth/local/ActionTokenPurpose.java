package io.opaa.auth.local;

/** What a single-use link does (ADR-0033, Entscheidungen 3, 11 and 12). */
public enum ActionTokenPurpose {
  /** Invitation: the person sets the first password. */
  SET_PASSWORD,
  /** "Forgot password" or an administrator's reset. */
  RESET_PASSWORD,
  /** Self-registration: the person confirms the address. */
  VERIFY_EMAIL,
  /** Handover of the local account to a provider identity, redeemed by the person. */
  HANDOVER
}
