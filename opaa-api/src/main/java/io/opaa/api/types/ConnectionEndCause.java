package io.opaa.api.types;

/**
 * Why a connection ended: the cause in the connection log and on the connection itself. Every event
 * but {@code CONNECTED} and {@code RECONNECTED} carries one.
 */
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
  ACCOUNT_DEACTIVATED,
  /** The profile was deleted. */
  PROFILE_DELETED,
  /** The provider rejected the secret. */
  PROVIDER_REJECTED,
  /** The secret passed the expiry the provider named. */
  SECRET_EXPIRED
}
