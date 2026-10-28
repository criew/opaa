package io.opaa.installation;

import io.opaa.common.Feature;
import io.opaa.common.FeatureFlags;

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
   * The transcription model of speech input, optional: the completion switches the role on through
   * the write path of the model administration; skipped, speech input stays off. Offered only while
   * the feature speech-input is on (ADR-0060, Entscheidung 16).
   */
  TRANSCRIPTION_MODEL(false, Feature.SPEECH_INPUT),
  /**
   * The mail server, optional: the completion stores it and switches sending on; skipped, sending
   * stays off (ADR-0033, Entscheidung 10).
   */
  MAIL(false),
  /**
   * The texts, accent colour and colour scheme of the branding, optional: the completion stores
   * them with the rules of the branding administration; skipped, the OPAA default stays. Images are
   * uploaded in the administration only (ADR-0060, Entscheidung 15).
   */
  BRANDING(false);

  private final boolean mandatory;
  private final Feature feature;

  InstallationStep(boolean mandatory) {
    this(mandatory, null);
  }

  InstallationStep(boolean mandatory, Feature feature) {
    this.mandatory = mandatory;
    this.feature = feature;
  }

  public boolean isMandatory() {
    return mandatory;
  }

  /**
   * Whether this installation offers the step: a step of a switched-off feature is neither listed
   * nor saved, checked or completed, and its part of an answer is ignored.
   */
  public boolean isOfferedWith(FeatureFlags features) {
    return feature == null || features.isEnabled(feature);
  }
}
