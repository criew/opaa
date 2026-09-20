package io.opaa.api.types;

/**
 * Which mechanism maintains one identity provider's groups - exactly one per provider (ADR-0036,
 * Entscheidung 2 and 3). Derived from the provider row, never stored twice: a directory run that is
 * switched on is {@link #DIRECTORY}, a non-empty groups claim is {@link #TOKEN}, and a provider
 * with neither contributes no groups at all.
 */
public enum GroupMechanism {

  /** The groups claim of this provider's tokens, re-read on every sign-in. */
  TOKEN,

  /** A scheduled pull from this provider's directory, with the four safeguards of #1816. */
  DIRECTORY,

  /** Neither - this provider brings no groups. */
  NONE
}
