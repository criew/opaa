package io.opaa.api.types;

/**
 * The stored state of a person's connected account (ADR-0041). Resting and a deactivated account
 * are never stored: they are derived from the account's usability at every use.
 */
public enum ConnectedAccountState {
  /** The secret is stored. */
  CONNECTED,
  /** The provider rejected the secret or it ran out; only connecting anew lifts it. */
  EXPIRED,
  /** Disconnected; the row is kept only while a private library runs on it. */
  DISCONNECTED
}
