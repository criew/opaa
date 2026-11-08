package io.opaa.llm;

/**
 * The states of an optional model role - rerank, transcription, text recognition (ADR-0065,
 * Entscheidung 6): deliberately switched off, switched on and usable, and switched on but broken.
 * The last one is split into two constants because the two need different remedies, but both are a
 * Störungsmeldung and never a Fußnote.
 */
public enum OptionalRoleState {

  /**
   * The role's switch in the model administration, or the operation's limit of the role, is off. An
   * explicit statement, not a configuration gap.
   */
  DISABLED,

  /** Switched on, configured, and the endpoint answered the last probe or call. */
  READY,

  /** Switched on, but no usable endpoint or model is configured for the role. */
  UNCONFIGURED,

  /** Switched on and configured, but the endpoint did not answer, refused the key or failed. */
  UNREACHABLE;

  /** Whether the role sends data out in this state: switched on and configured. */
  public boolean sends() {
    return this == READY || this == UNREACHABLE;
  }
}
