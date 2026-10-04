package io.opaa.api.types;

/** Why a connection ended: the cause in the connection log and on the connection itself. */
public enum ConnectionEndCause {
  /** The person disconnected it. */
  SELF,
  /** The system administration disconnected every connection of the profile. */
  EMERGENCY,
  /** The profile's server address changed, so its secrets no longer fit. */
  ADDRESS_CHANGED,
  /** The profile's app registration changed, so its secrets no longer fit. */
  REGISTRATION_CHANGED,
  /** The person's account was deactivated. */
  ACCOUNT_DEACTIVATED
}
