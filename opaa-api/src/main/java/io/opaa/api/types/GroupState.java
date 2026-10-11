package io.opaa.api.types;

/**
 * Whether a group is in effect (ADR-0036 Entscheidungen 2, 3 and 14). The constants are declared in
 * the order that decides when several conditions apply at once; only {@link #ACTIVE} is a new grant
 * target, existing grants stay in every state.
 */
public enum GroupState {
  ACTIVE,
  NOT_RELEASED,
  /** An internal group deactivated for the time being; it takes effect for nobody. */
  DEACTIVATED,
  UNMAINTAINED,
  PROVIDER_DISABLED,
  DISSOLVED
}
