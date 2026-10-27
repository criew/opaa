package io.opaa.installation;

/**
 * The steps of the installation this version knows (ADR-0060, Entscheidung 6). The installation
 * completes only when every mandatory step is saved and successfully checked, and every optional
 * step either skipped (not saved) or successfully checked as well.
 */
public enum InstallationStep {
  /** The first system administration account, the local emergency account of ADR-0033. */
  FIRST_ADMIN(true),
  /** Local accounts only, or additionally an OIDC identity provider as the default provider. */
  SIGN_IN(true),
  /** The active chat model. */
  CHAT_MODEL(true),
  /**
   * The embedding model; its check is a probe embedding, whose vector size the completion creates
   * the vector table with (ADR-0060, Entscheidung 10).
   */
  EMBEDDING_MODEL(true),
  /**
   * The mail server, optional: the completion stores it and switches sending on; skipped, sending
   * stays off (ADR-0033, Entscheidung 10).
   */
  MAIL(false);

  private final boolean mandatory;

  InstallationStep(boolean mandatory) {
    this.mandatory = mandatory;
  }

  public boolean isMandatory() {
    return mandatory;
  }
}
