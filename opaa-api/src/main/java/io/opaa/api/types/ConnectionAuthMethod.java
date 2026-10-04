package io.opaa.api.types;

/**
 * How a connection signs in at its source (docs/features/connector-connections.md, "Anmeldearten").
 * A connector reports the methods it offers; a profile picks one of them.
 */
public enum ConnectionAuthMethod {
  /** Anonymous access, no secret at all. */
  NONE,
  /** App password, personal token or user name and password. */
  PERSONAL_SECRET,
  /** Authorization code with PKCE. */
  OAUTH,
  /** The application signs in with client id and secret of the profile, without a person. */
  CLIENT_CREDENTIALS,
  /** A signed JWT assertion (RFC 7523) with a service account key. */
  SERVICE_ACCOUNT_KEY;

  /** Whether the method needs an app registration (client id, secret, tenant) on the profile. */
  public boolean usesAppRegistration() {
    return this == OAUTH || this == CLIENT_CREDENTIALS || this == SERVICE_ACCOUNT_KEY;
  }

  /**
   * Whether the method needs an app registration only a profile holds, so a connector offering it
   * requires profiles (ADR-0038). A service account key carries its registration in the key.
   */
  public boolean requiresProfile() {
    return this == OAUTH || this == CLIENT_CREDENTIALS;
  }

  /** Whether the method asks for scopes. */
  public boolean usesScopes() {
    return this == OAUTH || this == CLIENT_CREDENTIALS;
  }
}
