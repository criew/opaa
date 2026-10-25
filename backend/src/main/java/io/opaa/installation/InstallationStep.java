package io.opaa.installation;

/**
 * The steps of the installation this version knows (ADR-0060, Entscheidung 6). Each is mandatory:
 * the installation completes only when every one of them is saved and successfully checked.
 */
public enum InstallationStep {
  /** The first system administration account, the local emergency account of ADR-0033. */
  FIRST_ADMIN,
  /** Local accounts only, or additionally an OIDC identity provider as the default provider. */
  SIGN_IN,
  /** The active chat model. */
  CHAT_MODEL;

  public boolean isMandatory() {
    return true;
  }
}
