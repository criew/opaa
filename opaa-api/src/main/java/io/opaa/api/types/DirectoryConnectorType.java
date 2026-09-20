package io.opaa.api.types;

/**
 * Which kind of directory a provider's group synchronisation reads (ADR-0036, Entscheidung 3).
 * Keycloak is the first and, so far, only one: its user id <em>is</em> the token's {@code sub}, so
 * a member's identity coincides with the OPAA account without a mapping rule. LDAP (with an
 * explicit mapping rule) and Microsoft Graph follow as their own connectors.
 */
public enum DirectoryConnectorType {
  KEYCLOAK
}
