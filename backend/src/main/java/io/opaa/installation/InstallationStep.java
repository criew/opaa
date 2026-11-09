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
  EMBEDDING_MODEL(false),
  /**
   * The rerank model, optional: its check is the probe of the rerank model administration, and the
   * completion stores it there and switches reranking on; skipped, reranking stays off.
   */
  RERANK_MODEL(false),
  /**
   * The transcription model of speech input, optional: the completion switches the role on through
   * the write path of the model administration; skipped, speech input stays off. Offered only while
   * the feature speech-input is on (ADR-0060, Entscheidung 16).
   */
  TRANSCRIPTION_MODEL(false, Limit.SPEECH_INPUT),
  /**
   * The text recognition (OCR) model, optional: the completion switches the role on through the
   * write path of the model administration; skipped, text recognition stays off. Offered only while
   * the operation releases text recognition (ADR-0060, Entscheidung 18).
   */
  TEXT_RECOGNITION_MODEL(false, Limit.TEXT_RECOGNITION),
  /**
   * The clamd daemon of the operator, optional: the completion stores it in the settings of the
   * malware scan and switches the scan on; skipped, the scan stays off (ADR-0060, Entscheidung 19).
   */
  MALWARE_SCAN(false),
  /**
   * The mail server, optional: the completion stores it and switches sending on; skipped, sending
   * stays off (ADR-0033, Entscheidung 10).
   */
  MAIL(false),
  /**
   * The texts and accent colour of the branding, optional: the completion stores them with the
   * rules of the branding administration; skipped, the OPAA default stays. Images are uploaded in
   * the administration only (ADR-0060, Entscheidung 15).
   */
  BRANDING(false);

  /** The limit of the operation a step depends on (ADR-0060, Entscheidung 2). */
  enum Limit {
    /** {@code opaa.features.speech-input.enabled}, on by default. */
    SPEECH_INPUT,
    /** {@code opaa.ocr.enabled}, off by default. */
    TEXT_RECOGNITION
  }

  private final boolean mandatory;
  private final Limit limit;

  InstallationStep(boolean mandatory) {
    this(mandatory, null);
  }

  InstallationStep(boolean mandatory, Limit limit) {
    this.mandatory = mandatory;
    this.limit = limit;
  }

  public boolean isMandatory() {
    return mandatory;
  }

  /** The limit of the operation the step depends on, {@code null} for none. */
  Limit limit() {
    return limit;
  }
}
