package io.opaa.api.types;

/**
 * Whether a group is in effect (#1978, ADR-0036 Entscheidungen 2 and 3). The constants are declared
 * in the order that decides when several conditions apply at once; only {@link #ACTIVE} is a new
 * grant target, existing grants stay in every state.
 */
public enum GroupState {
  ACTIVE,
  NOT_RELEASED,
  UNMAINTAINED,
  PROVIDER_DISABLED,
  DISSOLVED
}
