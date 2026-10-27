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
  /** The active chat model; optional, the model administration sets it later. */
  CHAT_MODEL(false),
  /**
   * The embedding model; its check is a probe embedding, whose vector size the completion creates
   * the vector table with (ADR-0060, Entscheidung 10). Optional: without it there is no vector
   * table until the model administration sets the first model (ADR-0061).
   */
  EMBEDDING_MODEL(false);

  private final boolean mandatory;

  InstallationStep(boolean mandatory) {
    this.mandatory = mandatory;
  }

  public boolean isMandatory() {
    return mandatory;
  }
}
