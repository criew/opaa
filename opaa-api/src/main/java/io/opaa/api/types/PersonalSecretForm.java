package io.opaa.api.types;

/**
 * The form of a personal secret a connector asks for (ADR-0038, "Anmeldearten"); a form for
 * entering it is built from it without knowing the connector.
 */
public enum PersonalSecretForm {
  /** One token or app password. */
  TOKEN,
  /** A user name and a password. */
  USERNAME_AND_PASSWORD
}
